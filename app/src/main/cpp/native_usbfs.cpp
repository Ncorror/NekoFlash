#include <jni.h>
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <linux/usbdevice_fs.h>
#include <memory>
#include <mutex>
#include <new>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include <unordered_set>
#include <vector>

#ifndef USBDEVFS_URB_TYPE_BULK
#define USBDEVFS_URB_TYPE_BULK 3
#endif

#ifndef USBDEVFS_REAPURBNDELAY
#define USBDEVFS_REAPURBNDELAY _IOW('U', 13, void*)
#endif

#ifndef USBDEVFS_DISCARDURB
#define USBDEVFS_DISCARDURB _IO('U', 11)
#endif

#ifndef USBDEVFS_RESET
#define USBDEVFS_RESET _IO('U', 20)
#endif

namespace {
constexpr int64_t STAGE_PREFLIGHT = 1;
constexpr int64_t STAGE_OPEN = 2;
constexpr int64_t STAGE_READ = 3;
constexpr int64_t STAGE_SUBMIT = 4;
constexpr int64_t STAGE_REAP = 5;
constexpr int64_t STAGE_TIMEOUT = 6;
constexpr int64_t STAGE_STATUS = 7;
constexpr int64_t STAGE_LENGTH = 8;
constexpr int64_t STAGE_DONE = 10;
constexpr int64_t STAGE_CANCELLED = 11;
constexpr int64_t STAGE_HARD_TIMEOUT = 12;
constexpr int64_t STAGE_DRAIN = 13;
constexpr int64_t DRAIN_NOT_NEEDED = 0;
constexpr int64_t DRAIN_REAPED = 1;
constexpr int64_t DRAIN_FAILED = 2;

// This backend remains diagnostic-only. Queue depth and block size remain
// deliberately bounded while cancellation and URB lifetime handling are made
// fail-closed.
constexpr int MAX_USBFS_BULK_WRITE_SIZE = 256 * 1024;
constexpr int MIN_USBFS_BULK_WRITE_SIZE = 16 * 1024;
constexpr int MIN_PIPELINE_DEPTH = 1;
constexpr int MAX_PIPELINE_DEPTH = 2;
constexpr useconds_t REAP_POLL_SLEEP_US = 2 * 1000;  // 2 ms, avoids 0.25 ms busy-loop.
constexpr int64_t DRAIN_TIMEOUT_MS = 5 * 1000;

struct TransferControl {
    explicit TransferControl(int64_t transfer_token) : token(transfer_token) {}
    const int64_t token;
    std::atomic<bool> cancelled{false};
};

std::mutex g_transfer_mutex;
std::shared_ptr<TransferControl> g_active_transfer;
std::unordered_set<int64_t> g_pre_cancelled_tokens;
bool g_backend_poisoned = false;

int64_t now_ms() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000LL +
           static_cast<int64_t>(ts.tv_nsec) / 1000000LL;
}

jlongArray make_result(JNIEnv* env, int64_t success, int64_t confirmed_bytes,
                       int64_t stop_errno, int64_t status, int64_t actual,
                       int64_t elapsed_ms, int64_t stage,
                       int64_t submitted_bytes = 0,
                       int64_t pending_at_stop = 0,
                       int64_t last_completion_age_ms = 0,
                       int64_t drain_state = DRAIN_NOT_NEEDED,
                       int64_t drain_errno = 0,
                       int64_t backend_poisoned = 0,
                       int64_t kernel_ioctl_errno = 0) {
    const jlong values[14] = {
        static_cast<jlong>(success),
        static_cast<jlong>(confirmed_bytes),
        static_cast<jlong>(stop_errno),
        static_cast<jlong>(status),
        static_cast<jlong>(actual),
        static_cast<jlong>(elapsed_ms),
        static_cast<jlong>(stage),
        static_cast<jlong>(submitted_bytes),
        static_cast<jlong>(pending_at_stop),
        static_cast<jlong>(last_completion_age_ms),
        static_cast<jlong>(drain_state),
        static_cast<jlong>(drain_errno),
        static_cast<jlong>(backend_poisoned),
        static_cast<jlong>(kernel_ioctl_errno)
    };
    jlongArray out = env->NewLongArray(14);
    if (out != nullptr) env->SetLongArrayRegion(out, 0, 14, values);
    return out;
}

bool read_exact(int file_fd, unsigned char* buffer, size_t size, int* err) {
    size_t total = 0;
    while (total < size) {
        const ssize_t read_result = read(file_fd, buffer + total, size - total);
        if (read_result < 0) {
            if (errno == EINTR) continue;
            *err = errno;
            return false;
        }
        if (read_result == 0) {
            // The payload was truncated or changed after the caller measured it.
            *err = EIO;
            return false;
        }
        total += static_cast<size_t>(read_result);
    }
    return true;
}

struct UrbSlot {
    usbdevfs_urb* urb = nullptr;
    unsigned char* buffer = nullptr;
    bool pending = false;
    int requested = 0;
};

int find_slot_by_urb(const std::vector<UrbSlot>& slots, const void* completed) {
    for (size_t index = 0; index < slots.size(); ++index) {
        if (slots[index].urb == completed) return static_cast<int>(index);
    }
    return -1;
}

void free_slots(std::vector<UrbSlot>& slots) {
    for (auto& slot : slots) {
        delete[] slot.buffer;
        slot.buffer = nullptr;
        delete slot.urb;
        slot.urb = nullptr;
    }
}

/**
 * Cancel every submitted URB and reap every completion before memory is freed.
 *
 * USBDEVFS_DISCARDURB only requests cancellation; the corresponding URB is
 * still returned through REAPURB. Therefore pending is intentionally not
 * cleared until its completion has been reaped.
 */
bool discard_and_reap_all(int usb_fd, std::vector<UrbSlot>& slots,
                          int* pending_count, int* drain_errno) {
    for (auto& slot : slots) {
        if (!slot.pending || slot.urb == nullptr) continue;
        if (ioctl(usb_fd, USBDEVFS_DISCARDURB, slot.urb) < 0) {
            // ENOENT normally means it completed between the last poll and the
            // discard request and is already waiting in the completion queue.
            if (errno != ENOENT && errno != EINVAL && *drain_errno == 0) {
                *drain_errno = errno;
            }
        }
    }

    const int64_t drain_started_ms = now_ms();
    while (*pending_count > 0) {
        void* completed = nullptr;
        if (ioctl(usb_fd, USBDEVFS_REAPURBNDELAY, &completed) < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN) {
                if (now_ms() - drain_started_ms > DRAIN_TIMEOUT_MS) {
                    if (*drain_errno == 0) *drain_errno = ETIMEDOUT;
                    return false;
                }
                usleep(REAP_POLL_SLEEP_US);
                continue;
            }
            if (*drain_errno == 0) *drain_errno = errno;
            return false;
        }
        if (completed == nullptr) {
            if (*drain_errno == 0) *drain_errno = EPROTO;
            return false;
        }

        // Compare the pointer value before dereferencing it. This also safely
        // drains any foreign/stale completion that may already be queued.
        const int index = find_slot_by_urb(slots, completed);
        if (index < 0) continue;

        UrbSlot& slot = slots[static_cast<size_t>(index)];
        if (!slot.pending) continue;
        slot.pending = false;
        --(*pending_count);
    }
    return true;
}

int clamp_block_size(int requested) {
    if (requested <= 0) return MIN_USBFS_BULK_WRITE_SIZE;
    if (requested > MAX_USBFS_BULK_WRITE_SIZE) return MAX_USBFS_BULK_WRITE_SIZE;
    if (requested < MIN_USBFS_BULK_WRITE_SIZE) return MIN_USBFS_BULK_WRITE_SIZE;
    return (requested / MIN_USBFS_BULK_WRITE_SIZE) * MIN_USBFS_BULK_WRITE_SIZE;
}

int clamp_pipeline_depth(int requested) {
    if (requested < MIN_PIPELINE_DEPTH) return MIN_PIPELINE_DEPTH;
    if (requested > MAX_PIPELINE_DEPTH) return MAX_PIPELINE_DEPTH;
    return requested;
}

std::shared_ptr<TransferControl> register_transfer(int64_t token, int* error) {
    std::lock_guard<std::mutex> lock(g_transfer_mutex);
    if (g_backend_poisoned) {
        *error = EUCLEAN;
        return nullptr;
    }
    if (g_active_transfer != nullptr) {
        *error = EBUSY;
        return nullptr;
    }
    auto control = std::make_shared<TransferControl>(token);
    if (g_pre_cancelled_tokens.erase(token) > 0) {
        control->cancelled.store(true, std::memory_order_release);
    }
    g_active_transfer = control;
    return control;
}

void unregister_transfer(const std::shared_ptr<TransferControl>& control) {
    std::lock_guard<std::mutex> lock(g_transfer_mutex);
    if (g_active_transfer == control) g_active_transfer.reset();
    g_pre_cancelled_tokens.erase(control->token);
}

void poison_backend() {
    std::lock_guard<std::mutex> lock(g_transfer_mutex);
    g_backend_poisoned = true;
}
}  // namespace

// Clears a wedged bulk OUT endpoint by forcing the device to re-enumerate.
// Mirrors upstream fastboot LinuxUsbTransport::Reset(): ioctl(fd, USBDEVFS_RESET, 0).
// The onyx bootloader wedges its OUT endpoint after an aborted DATA transfer and a
// plain re-handshake does not clear it; only a device reset does. Returns 0 on success,
// otherwise the positive errno (or -1 for an invalid fd). After a successful reset the
// caller's UsbDeviceConnection is invalidated and the device must be re-enumerated.
extern "C" JNIEXPORT jint JNICALL
Java_ru_forum_adbfastboottool_NativeUsbfsBackend_nativeUsbfsResetDevice(
    JNIEnv* /*env*/, jobject /*thiz*/, jint fd) {
    if (fd < 0) return static_cast<jint>(-1);
    const int ret = ioctl(static_cast<int>(fd), USBDEVFS_RESET, 0);
    if (ret != 0) {
        const int saved = errno;
        return static_cast<jint>(saved > 0 ? saved : -1);
    }
    return static_cast<jint>(0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ru_forum_adbfastboottool_NativeUsbfsBackend_nativeCancelTransfer(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong transfer_token) {
    const int64_t token = static_cast<int64_t>(transfer_token);
    if (token <= 0) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(g_transfer_mutex);
    if (g_active_transfer != nullptr && g_active_transfer->token == token) {
        g_active_transfer->cancelled.store(true, std::memory_order_release);
        return JNI_TRUE;
    }

    // Handles the narrow race where Kotlin has published the token but the
    // blocking JNI function has not registered it yet.
    if (g_pre_cancelled_tokens.size() >= 64U) {
        g_pre_cancelled_tokens.clear();
    }
    g_pre_cancelled_tokens.insert(token);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_forum_adbfastboottool_NativeUsbfsBackend_nativeBackendState(
    JNIEnv* env, jobject /*thiz*/) {
    int64_t poisoned = 0;
    int64_t active = 0;
    int64_t token = 0;
    {
        std::lock_guard<std::mutex> lock(g_transfer_mutex);
        poisoned = g_backend_poisoned ? 1 : 0;
        active = g_active_transfer != nullptr ? 1 : 0;
        token = g_active_transfer != nullptr ? g_active_transfer->token : 0;
    }
    const jlong values[3] = {
        static_cast<jlong>(poisoned),
        static_cast<jlong>(active),
        static_cast<jlong>(token)
    };
    jlongArray out = env->NewLongArray(3);
    if (out != nullptr) env->SetLongArrayRegion(out, 0, 3, values);
    return out;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_forum_adbfastboottool_NativeUsbfsBackend_nativeBulkOutUrb(
    JNIEnv* env,
    jobject /*thiz*/,
    jint fd,
    jint endpoint_address,
    jstring payload_path,
    jlong total_bytes,
    jint block_bytes,
    jint pipeline_depth,
    jint stall_timeout_ms,
    jint hard_timeout_ms,
    jlong transfer_token) {

    const int64_t started = now_ms();
    if (fd < 0 || payload_path == nullptr || total_bytes <= 0 || block_bytes <= 0 ||
        stall_timeout_ms <= 0 || hard_timeout_ms <= 0 || hard_timeout_ms < stall_timeout_ms ||
        transfer_token <= 0 || (endpoint_address & 0x80) != 0 || (endpoint_address & 0x0F) == 0) {
        return make_result(env, 0, 0, EINVAL, 0, 0, 0, STAGE_PREFLIGHT);
    }

    int register_errno = 0;
    const auto control = register_transfer(static_cast<int64_t>(transfer_token), &register_errno);
    if (control == nullptr) {
        return make_result(env, 0, 0, register_errno, 0, 0,
                           now_ms() - started, STAGE_PREFLIGHT);
    }

    const char* path_chars = env->GetStringUTFChars(payload_path, nullptr);
    if (path_chars == nullptr) {
        unregister_transfer(control);
        return make_result(env, 0, 0, ENOMEM, 0, 0,
                           now_ms() - started, STAGE_PREFLIGHT);
    }

    const int payload_fd = open(path_chars, O_RDONLY | O_CLOEXEC);
    const int open_errno = errno;
    env->ReleaseStringUTFChars(payload_path, path_chars);
    if (payload_fd < 0) {
        unregister_transfer(control);
        return make_result(env, 0, 0, open_errno, 0, 0,
                           now_ms() - started, STAGE_OPEN);
    }

    struct stat payload_stat{};
    if (fstat(payload_fd, &payload_stat) < 0) {
        const int stat_errno = errno;
        close(payload_fd);
        unregister_transfer(control);
        return make_result(env, 0, 0, stat_errno, 0, 0,
                           now_ms() - started, STAGE_OPEN);
    }
    if (!S_ISREG(payload_stat.st_mode) ||
        static_cast<int64_t>(payload_stat.st_size) != static_cast<int64_t>(total_bytes)) {
        close(payload_fd);
        unregister_transfer(control);
        return make_result(env, 0, 0, EINVAL, 0, 0,
                           now_ms() - started, STAGE_OPEN);
    }

    const int usb_fd = dup(fd);
    if (usb_fd < 0) {
        const int dup_errno = errno;
        close(payload_fd);
        unregister_transfer(control);
        return make_result(env, 0, 0, dup_errno, 0, 0,
                           now_ms() - started, STAGE_OPEN);
    }

    const int safe_block = clamp_block_size(block_bytes);
    const int safe_depth = clamp_pipeline_depth(pipeline_depth);
    std::vector<UrbSlot> slots(static_cast<size_t>(safe_depth));
    bool allocation_ok = true;
    for (auto& slot : slots) {
        slot.urb = new (std::nothrow) usbdevfs_urb();
        slot.buffer = new (std::nothrow) unsigned char[static_cast<size_t>(safe_block)];
        if (slot.urb == nullptr || slot.buffer == nullptr) {
            allocation_ok = false;
            break;
        }
    }
    if (!allocation_ok) {
        free_slots(slots);
        close(usb_fd);
        close(payload_fd);
        unregister_transfer(control);
        return make_result(env, 0, 0, ENOMEM, 0, 0,
                           now_ms() - started, STAGE_PREFLIGHT);
    }

    int64_t file_offset = 0;       // bytes read and submitted
    int64_t completed_total = 0;  // bytes confirmed by reaped URBs
    int pending_count = 0;
    int last_errno = 0;
    int last_kernel_ioctl_errno = 0;
    int last_status = 0;
    int last_actual = 0;
    int64_t last_progress_ms = now_ms();

    auto finish = [&](bool success, int error_code, int status, int actual,
                      int64_t stage, int kernel_ioctl_errno = 0) -> jlongArray {
        const int pending_at_stop = pending_count;
        const int64_t stop_ms = now_ms();
        const int64_t last_completion_age_ms =
            (stop_ms - last_progress_ms) > 0 ? (stop_ms - last_progress_ms) : 0;
        int drain_state = pending_count > 0 ? DRAIN_REAPED : DRAIN_NOT_NEEDED;
        int drain_errno = 0;
        bool safe_to_free = true;
        if (pending_count > 0) {
            if (!discard_and_reap_all(usb_fd, slots, &pending_count, &drain_errno)) {
                // Never free memory that may still be referenced by usbfs. Keep
                // the small allocation intentionally leaked and poison the
                // backend so another native transfer cannot start in-process.
                safe_to_free = false;
                drain_state = DRAIN_FAILED;
                poison_backend();
                success = false;
                error_code = drain_errno != 0 ? drain_errno : EIO;
                stage = STAGE_DRAIN;
            }
        }
        if (safe_to_free) free_slots(slots);
        close(usb_fd);
        close(payload_fd);
        unregister_transfer(control);
        bool backend_poisoned = false;
        {
            std::lock_guard<std::mutex> lock(g_transfer_mutex);
            backend_poisoned = g_backend_poisoned;
        }
        return make_result(env, success ? 1 : 0, completed_total, error_code,
                           status, actual, now_ms() - started, stage,
                           file_offset, pending_at_stop, last_completion_age_ms,
                           drain_state, drain_errno, backend_poisoned ? 1 : 0,
                           kernel_ioctl_errno);
    };

    auto cancellation_stage = [&]() -> int64_t {
        if (control->cancelled.load(std::memory_order_acquire)) return STAGE_CANCELLED;
        if (now_ms() - started > static_cast<int64_t>(hard_timeout_ms)) {
            return STAGE_HARD_TIMEOUT;
        }
        return 0;
    };

    auto submit_slot = [&](int index) -> bool {
        if (file_offset >= total_bytes) return false;
        UrbSlot& slot = slots[static_cast<size_t>(index)];
        if (slot.pending) return false;
        if (control->cancelled.load(std::memory_order_acquire)) {
            last_errno = ECANCELED;
            return false;
        }
        if (now_ms() - started > static_cast<int64_t>(hard_timeout_ms)) {
            last_errno = ETIMEDOUT;
            return false;
        }

        const int64_t remaining = static_cast<int64_t>(total_bytes) - file_offset;
        const int requested = remaining < safe_block ? static_cast<int>(remaining) : safe_block;
        if (!read_exact(payload_fd, slot.buffer, static_cast<size_t>(requested), &last_errno)) {
            return false;
        }
        if (control->cancelled.load(std::memory_order_acquire)) {
            last_errno = ECANCELED;
            return false;
        }
        if (now_ms() - started > static_cast<int64_t>(hard_timeout_ms)) {
            last_errno = ETIMEDOUT;
            return false;
        }

        std::memset(slot.urb, 0, sizeof(*slot.urb));
        slot.urb->type = USBDEVFS_URB_TYPE_BULK;
        slot.urb->endpoint = static_cast<unsigned char>(endpoint_address & 0xFF);
        slot.urb->status = 0;
        slot.urb->flags = 0;
        slot.urb->buffer = slot.buffer;
        slot.urb->buffer_length = requested;
        slot.urb->actual_length = 0;
        slot.urb->start_frame = 0;
        slot.urb->number_of_packets = 0;
        slot.urb->error_count = 0;
        slot.urb->signr = 0;
        slot.urb->usercontext = reinterpret_cast<void*>(static_cast<intptr_t>(index + 1));
        slot.requested = requested;

        if (ioctl(usb_fd, USBDEVFS_SUBMITURB, slot.urb) < 0) {
            last_errno = errno;
            last_kernel_ioctl_errno = last_errno;
            return false;
        }

        slot.pending = true;
        ++pending_count;
        file_offset += requested;
        return true;
    };

    auto submit_failure_stage = [&](int64_t before_offset) -> int64_t {
        if (last_errno == ECANCELED) return STAGE_CANCELLED;
        if (last_errno == ETIMEDOUT) return STAGE_HARD_TIMEOUT;
        return (last_errno == 0 && before_offset == file_offset) ? STAGE_READ : STAGE_SUBMIT;
    };

    for (int index = 0; index < safe_depth && file_offset < total_bytes; ++index) {
        const int64_t early_stage = cancellation_stage();
        if (early_stage != 0) {
            return finish(false, early_stage == STAGE_CANCELLED ? ECANCELED : ETIMEDOUT,
                          last_status, last_actual, early_stage);
        }
        const int64_t before = file_offset;
        if (!submit_slot(index)) {
            const int64_t stage = submit_failure_stage(before);
            return finish(false, last_errno, 0, 0, stage, last_kernel_ioctl_errno);
        }
    }

    while (completed_total < total_bytes || pending_count > 0) {
        const int64_t stop_stage = cancellation_stage();
        if (stop_stage != 0) {
            return finish(false, stop_stage == STAGE_CANCELLED ? ECANCELED : ETIMEDOUT,
                          last_status, last_actual, stop_stage);
        }

        void* completed = nullptr;
        if (ioctl(usb_fd, USBDEVFS_REAPURBNDELAY, &completed) == 0) {
            if (completed == nullptr) {
                return finish(false, EPROTO, 0, 0, STAGE_REAP);
            }

            // Do not dereference the returned pointer until it is proven to be
            // one of the currently allocated URBs.
            const int index = find_slot_by_urb(slots, completed);
            if (index < 0) {
                return finish(false, EPROTO, 0, 0, STAGE_REAP);
            }

            UrbSlot& slot = slots[static_cast<size_t>(index)];
            if (!slot.pending) {
                return finish(false, EPROTO, 0, 0, STAGE_REAP);
            }

            const auto* completed_urb = slot.urb;
            last_status = completed_urb->status;
            last_actual = completed_urb->actual_length;
            slot.pending = false;
            --pending_count;

            if (last_status != 0) {
                return finish(false, 0, last_status, last_actual, STAGE_STATUS);
            }
            if (last_actual != slot.requested) {
                completed_total += last_actual;
                return finish(false, 0, last_status, last_actual, STAGE_LENGTH);
            }

            completed_total += slot.requested;
            last_progress_ms = now_ms();

            if (file_offset < total_bytes) {
                const int64_t before = file_offset;
                if (!submit_slot(index)) {
                    const int64_t stage = submit_failure_stage(before);
                    return finish(false, last_errno, 0, 0, stage, last_kernel_ioctl_errno);
                }
            }
            continue;
        }

        last_errno = errno;
        if (last_errno != EAGAIN && last_errno != EINTR) {
            last_kernel_ioctl_errno = last_errno;
            return finish(false, last_errno, 0, 0, STAGE_REAP, last_kernel_ioctl_errno);
        }

        const int64_t current_ms = now_ms();
        if (current_ms - last_progress_ms > static_cast<int64_t>(stall_timeout_ms)) {
            return finish(false, ETIMEDOUT, last_status, last_actual, STAGE_TIMEOUT);
        }
        if (current_ms - started > static_cast<int64_t>(hard_timeout_ms)) {
            return finish(false, ETIMEDOUT, last_status, last_actual, STAGE_HARD_TIMEOUT);
        }
        usleep(REAP_POLL_SLEEP_US);
    }

    return finish(true, 0, last_status, last_actual, STAGE_DONE);
}
