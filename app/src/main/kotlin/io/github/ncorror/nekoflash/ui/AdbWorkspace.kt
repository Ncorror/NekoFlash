package io.github.ncorror.nekoflash.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.ncorror.nekoflash.R
import io.github.ncorror.nekoflash.adb.AdbCommandState
import io.github.ncorror.nekoflash.adb.AdbFileState
import io.github.ncorror.nekoflash.adb.AdbForwardController
import io.github.ncorror.nekoflash.adb.AdbForwardEntry
import io.github.ncorror.nekoflash.adb.AdbForwardState
import io.github.ncorror.nekoflash.adb.AdbLinkState
import io.github.ncorror.nekoflash.adb.AdbRawServiceState
import io.github.ncorror.nekoflash.adb.AdbRebootState
import io.github.ncorror.nekoflash.adb.AdbTerminalState
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.protocol.adb.AdbPeerMode
import io.github.ncorror.nekoflash.protocol.adb.AdbSyncDestination
import io.github.ncorror.nekoflash.usb.api.UsbSession

/**
 * Состояние ADB-соединения для конкретной сессии.
 *
 * Показывается только у этой сессии: соединение принадлежит одному поколению,
 * и показывать его состояние рядом с чужим устройством означало бы сказать
 * неправду о том, к чему относится «подключено».
 */
@Composable
internal fun AdbLinkSection(
    session: UsbSession,
    adbLink: AdbLinkState,
    adbCommand: AdbCommandState,
    terminal: AdbTerminalState,
    terminalActions: TerminalActions,
    files: AdbFileState,
    fileActions: FileActions,
    onAdbConnect: () -> Unit,
    onAdbDisconnect: () -> Unit,
    onRunCommand: (String) -> Unit,
    reboot: RebootPanel,
    rawService: RawServicePanel,
    forward: ForwardPanel,
) {
    val linkForThisSession = adbLink.takeIf { it.generationOrNull() == session.generation }
    val connected = linkForThisSession is AdbLinkState.Connected
    val busy = linkForThisSession is AdbLinkState.Connecting ||
        linkForThisSession is AdbLinkState.WaitingForAuthorization

    LabelledValue(
        label = stringResource(R.string.adb_state_label),
        value = adbLinkText(linkForThisSession),
    )
    if (linkForThisSession is AdbLinkState.Connected) {
        LabelledValue(
            label = stringResource(R.string.adb_features_label),
            value = linkForThisSession.features.sorted().joinToString(", ").ifEmpty {
                stringResource(R.string.adb_features_none)
            },
        )
    }
    Button(
        onClick = if (connected) onAdbDisconnect else onAdbConnect,
        enabled = !busy,
    ) {
        Text(
            stringResource(
                if (connected) R.string.adb_disconnect else R.string.adb_connect,
            ),
        )
    }
    Text(
        text = stringResource(R.string.adb_connect_hint),
        style = MaterialTheme.typography.bodySmall,
    )
    if (connected) {
        ShellSection(command = adbCommand, onRunCommand = onRunCommand)
        TerminalSection(terminal = terminal, actions = terminalActions)
        FilesSection(files = files, actions = fileActions)
        RebootSection(panel = reboot)
        RawServiceSection(panel = rawService)
        ForwardSection(panel = forward)
    }
}

/**
 * Произвольный сервис: состояние вызова и само действие.
 *
 * Собраны в один объект по той же причине, что и [RebootPanel].
 */
data class RawServicePanel(
    val state: AdbRawServiceState = AdbRawServiceState.None,
    val onCall: (String) -> Unit = {},
)

/**
 * Проброс портов: список живых пробросов и два действия над ними.
 *
 * Собраны в один объект по той же причине, что и [RebootPanel].
 */
data class ForwardPanel(
    val state: AdbForwardState = AdbForwardState.None,
    /** Завести проброс: локальный порт и адрес на устройстве. */
    val onAdd: (Int, String) -> Unit = { _, _ -> },
    val onRemove: (Int) -> Unit = {},
)

/**
 * Перезагрузка: состояние запроса и само действие.
 *
 * Состояние и действие здесь в одном объекте, в отличие от файлов и терминала,
 * и причина не в красоте. Список аргументов экрана уже однажды разъехался — об
 * этом сказано прямо в `NekoFlashApp`, — и каждая лишняя пара строк в нём это
 * ещё один шанс разъехаться снова.
 */
data class RebootPanel(
    val state: AdbRebootState = AdbRebootState.None,
    val onReboot: (String) -> Unit = {},
)

/** Действия с файлами устройства. */
data class FileActions(
    val onDescribe: (String) -> Unit = {},
    val onRead: (String) -> Unit = {},
    /** Записать файл заданного размера в байтах. */
    val onWrite: (String, Long) -> Unit = { _, _ -> },
)

/**
 * Размеры файлов, которые приложение умеет записать само.
 *
 * Оба нужны аппаратному гейту `07` §6.34: малый проходит одним блоком,
 * большой — двумястами пятьюдесятью шестью, и его же хватает, чтобы успеть
 * выдернуть кабель посреди передачи.
 */
private const val SMALL_WRITE_BYTES = 4L * 1024L
private const val LARGE_WRITE_BYTES = 16L * 1024L * 1024L

/**
 * Операции с файлами устройства: проверка пути, чтение и запись.
 *
 * Живая оболочка им больше не мешает: у каждой операции свой логический поток
 * и свой ящик (`docs/adr/0004_CONCURRENT_ADB_DISPATCHER_RU.md`). Гасит кнопки
 * только своя же незаконченная операция — показать два результата этот экран
 * пока не умеет, и это ограничение вида, а не возможности.
 */
@Composable
private fun FilesSection(files: AdbFileState, actions: FileActions) {
    val path = remember { mutableStateOf("") }
    val idle = files !is AdbFileState.Busy

    LabelledValue(label = stringResource(R.string.files_label), value = fileStateText(files))
    OutlinedTextField(
        value = path.value,
        onValueChange = { text -> path.value = text },
        label = { Text(stringResource(R.string.files_path_hint)) },
        singleLine = true,
        enabled = idle,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { actions.onDescribe(path.value) }, enabled = idle && path.value.isNotBlank()) {
        Text(stringResource(R.string.files_describe))
    }
    Button(onClick = { actions.onRead(path.value) }, enabled = idle && path.value.isNotBlank()) {
        Text(stringResource(R.string.files_read))
    }
    Button(
        onClick = { actions.onWrite(path.value, SMALL_WRITE_BYTES) },
        enabled = idle && path.value.isNotBlank(),
    ) {
        Text(stringResource(R.string.files_write_small))
    }
    Button(
        onClick = { actions.onWrite(path.value, LARGE_WRITE_BYTES) },
        enabled = idle && path.value.isNotBlank(),
    ) {
        Text(stringResource(R.string.files_write_large))
    }
    Text(
        text = stringResource(R.string.files_note),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        text = stringResource(R.string.files_write_note),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun fileStateText(files: AdbFileState): String = when (files) {
    AdbFileState.None -> ""
    is AdbFileState.Busy -> stringResource(R.string.files_busy, files.path)
    is AdbFileState.Read ->
        pluralStringResource(R.plurals.files_read_done, files.bytes.toQuantity(), files.bytes, files.sha256)

    is AdbFileState.Written ->
        pluralStringResource(R.plurals.files_write_done, files.bytes.toQuantity(), files.bytes, files.sha256)

    is AdbFileState.WriteFailed -> writeFailedText(files)
    is AdbFileState.Failed -> stringResource(R.string.files_failed, files.reason)
    is AdbFileState.Described -> describedText(files)
}

/**
 * Текст неудачной записи.
 *
 * Формулировка выбирается по состоянию назначения, а не по причине отказа:
 * оператору важнее всего, что теперь с файлом на устройстве.
 */
@Composable
private fun writeFailedText(files: AdbFileState.WriteFailed): String = stringResource(
    when (files.destination) {
        AdbSyncDestination.UNTOUCHED -> R.string.files_write_failed_untouched
        AdbSyncDestination.COMMITTED -> R.string.files_write_failed_committed
        AdbSyncDestination.UNKNOWN -> R.string.files_write_failed_unknown
    },
    files.reason,
    files.path,
)

@Composable
private fun describedText(files: AdbFileState.Described): String = when {
    !files.stat.exists -> stringResource(R.string.files_missing, files.path)
    files.stat.directory -> stringResource(R.string.files_directory, files.path)
    files.stat.regularFile ->
        pluralStringResource(R.plurals.files_file, files.stat.size.toQuantity(), files.path, files.stat.size)

    else ->
        pluralStringResource(R.plurals.files_object, files.stat.size.toQuantity(), files.path, files.stat.size)
}

/**
 * Число для выбора формы множественного числа.
 *
 * Формы выбираются целым числом, а размер файла — длинное. Значения больше
 * двух гигабайт прижимаются к пределу: во всех поддерживаемых языках такие
 * числа попадают в ту же форму, что и предел, а само число выводится
 * отдельным аргументом и остаётся точным.
 */
private fun Long.toQuantity(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/** Действия интерактивной оболочки. Собраны вместе, чтобы не плодить параметры. */
data class TerminalActions(
    val onStart: () -> Unit = {},
    val onSend: (String) -> Unit = {},
    val onInterrupt: () -> Unit = {},
    val onStop: () -> Unit = {},
)

/**
 * Интерактивная оболочка.
 *
 * Пока сессия жива, она занимает текущий production reader, поэтому одиночные
 * команды в это время недоступны — и об этом сказано на экране, а не оставлено
 * догадкам. Интерактивные записи при этом идут отдельным writer executor и не
 * блокируют UI.
 */
@Composable
private fun TerminalSection(terminal: AdbTerminalState, actions: TerminalActions) {
    val input = remember { mutableStateOf("") }

    LabelledValue(
        label = stringResource(R.string.terminal_label),
        value = when {
            terminal.output.isNotBlank() -> terminal.output
            terminal.ended != null -> stringResource(R.string.terminal_ended, terminal.ended)
            terminal.closing -> stringResource(R.string.terminal_closing)
            terminal.active && !terminal.ready -> stringResource(R.string.terminal_opening)
            else -> stringResource(R.string.terminal_waiting)
        },
    )
    if (terminal.active) {
        OutlinedTextField(
            value = input.value,
            onValueChange = { text -> input.value = text },
            label = { Text(stringResource(R.string.terminal_input_hint)) },
            singleLine = true,
            enabled = terminal.ready,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                actions.onSend(input.value)
                input.value = ""
            },
            enabled = terminal.ready && input.value.isNotBlank(),
        ) {
            Text(stringResource(R.string.terminal_send))
        }
        Button(onClick = actions.onInterrupt, enabled = terminal.ready) {
            Text(stringResource(R.string.terminal_interrupt))
        }
        Button(onClick = actions.onStop, enabled = !terminal.closing) {
            Text(stringResource(R.string.terminal_stop))
        }
    } else {
        Button(onClick = actions.onStart) {
            Text(stringResource(R.string.terminal_start))
        }
    }
    Text(
        text = stringResource(R.string.terminal_note),
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * Вызов произвольного сервиса ADB.
 *
 * Имя сервиса вводится целиком — `shell:ls`, `sync:`, `exec:id`, `track-devices`.
 * Списка нет намеренно (`01` §3): какие сервисы существуют, знает устройство.
 *
 * Набранный здесь `reboot:` ведёт себя как кнопка перезагрузки: устройство
 * уходит с шины, не ответив, и показывать это отказом было бы неправдой.
 */
@Composable
private fun RawServiceSection(panel: RawServicePanel) {
    val service = remember { mutableStateOf("") }
    val running = panel.state is AdbRawServiceState.Running

    LabelledValue(
        label = stringResource(R.string.raw_service_label),
        value = rawServiceText(panel.state),
    )
    OutlinedTextField(
        value = service.value,
        onValueChange = { text -> service.value = text },
        label = { Text(stringResource(R.string.raw_service_hint)) },
        singleLine = true,
        enabled = !running,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { panel.onCall(service.value) },
        enabled = !running && service.value.isNotBlank(),
    ) {
        Text(stringResource(R.string.raw_service_run))
    }
    (panel.state as? AdbRawServiceState.Finished)?.takeIf { it.text.isNotEmpty() }?.let { done ->
        Text(text = done.text, style = MaterialTheme.typography.bodySmall)
    }
    Text(
        text = stringResource(R.string.raw_service_note),
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Размер ответа называется всегда: он известен даже тогда, когда текст бессмыслен. */
@Composable
/**
 * Проброс портов.
 *
 * Порт разбирается здесь, а не в контроллере: пустое или нечисловое поле — это
 * ещё не набранное значение, а не отказ, и заводить ради него состояние ошибки
 * значило бы ругаться на человека, который ещё печатает.
 */
@Composable
private fun ForwardSection(panel: ForwardPanel) {
    val port = remember { mutableStateOf("") }
    val address = remember { mutableStateOf("") }

    LabelledValue(
        label = stringResource(R.string.forward_label),
        value = panel.state.forwards.size.takeIf { it > 0 }?.toString()
            ?: stringResource(R.string.forward_none),
    )
    OutlinedTextField(
        value = port.value,
        onValueChange = { text -> port.value = text },
        label = { Text(stringResource(R.string.forward_port_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = address.value,
        onValueChange = { text -> address.value = text },
        label = { Text(stringResource(R.string.forward_address_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { panel.onAdd(port.value.trim().toIntOrNull() ?: 0, address.value) },
        enabled = address.value.isNotBlank(),
    ) {
        Text(stringResource(R.string.forward_add))
    }
    panel.state.forwards.forEach { entry -> ForwardRow(entry = entry, onRemove = panel.onRemove) }
    panel.state.failure?.let { failure ->
        Text(
            text = stringResource(R.string.forward_failed, failure),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Text(
        text = stringResource(R.string.forward_note, AdbForwardController.MAX_CONNECTIONS),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun ForwardRow(entry: AdbForwardEntry, onRemove: (Int) -> Unit) {
    Text(
        text = stringResource(
            R.string.forward_entry,
            entry.localPort,
            entry.address,
            entry.live,
            entry.served,
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    entry.lastEnd?.let { end ->
        Text(
            text = stringResource(R.string.forward_last_end, end),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Button(onClick = { onRemove(entry.localPort) }) {
        Text(stringResource(R.string.forward_remove))
    }
}

private fun rawServiceText(state: AdbRawServiceState): String = when (state) {
    AdbRawServiceState.None -> stringResource(R.string.raw_service_none)
    is AdbRawServiceState.Running -> stringResource(R.string.raw_service_running, state.service)
    is AdbRawServiceState.Finished ->
        stringResource(R.string.raw_service_finished, state.service, state.bytes)

    is AdbRawServiceState.OneWay ->
        stringResource(R.string.raw_service_one_way, state.service, state.evidence)

    is AdbRawServiceState.Failed ->
        stringResource(R.string.raw_service_failed, state.service, state.reason)
}

/**
 * Перезагрузка устройства.
 *
 * Успех здесь выглядит как обрыв: устройство уходит с шины, и подключение
 * закрывается. Поэтому состояние запроса показывается отдельно от состояния
 * соединения — иначе «отключилось» затёрло бы «перезагружается».
 *
 * Цель — свободная строка. Списка целей нет намеренно (`01` §3): какие из них
 * существуют, знает устройство, и его отказ — это ответ, а не наша ошибка.
 */
@Composable
private fun RebootSection(panel: RebootPanel) {
    val target = remember { mutableStateOf("") }
    val running = panel.state is AdbRebootState.Running

    LabelledValue(label = stringResource(R.string.reboot_label), value = rebootText(panel.state))
    OutlinedTextField(
        value = target.value,
        onValueChange = { text -> target.value = text },
        label = { Text(stringResource(R.string.reboot_target_hint)) },
        singleLine = true,
        enabled = !running,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { panel.onReboot(target.value) }, enabled = !running) {
        Text(stringResource(R.string.reboot_run))
    }
    Text(
        text = stringResource(R.string.reboot_note),
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * Что показать про перезагрузку.
 *
 * У отказа состояние устройства выносится вперёд: оператору сначала важно,
 * тронуто ли устройство, и только потом — почему не получилось.
 */
@Composable
private fun rebootText(reboot: AdbRebootState): String = when (reboot) {
    AdbRebootState.None -> stringResource(R.string.reboot_none)
    is AdbRebootState.Running -> stringResource(R.string.reboot_running, reboot.service())
    is AdbRebootState.Accepted -> stringResource(R.string.reboot_accepted, reboot.service)
    is AdbRebootState.Failed -> stringResource(
        R.string.reboot_failed,
        reboot.device.name,
        reboot.reason,
    )
}

/** Пустая цель — обычная перезагрузка; показываем это словом, а не пустотой. */
@Composable
private fun AdbRebootState.Running.service(): String =
    target.ifBlank { stringResource(R.string.reboot_target_system) }

/**
 * Неинтерактивная оболочка.
 *
 * Показывается только у подключённого устройства: команда без соединения
 * никуда не уйдёт, а кнопка, которая ничего не делает, врёт о состоянии.
 *
 * Живая интерактивная оболочка команде больше не мешает: у неё свой логический
 * поток. Гасит кнопку только предыдущая незаконченная команда — на экране один
 * слот результата.
 */
@Composable
private fun ShellSection(
    command: AdbCommandState,
    onRunCommand: (String) -> Unit,
) {
    val input = remember { mutableStateOf("") }
    val running = command is AdbCommandState.Running

    OutlinedTextField(
        value = input.value,
        onValueChange = { text -> input.value = text },
        label = { Text(stringResource(R.string.shell_label)) },
        placeholder = { Text(stringResource(R.string.shell_hint)) },
        singleLine = true,
        enabled = !running,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { onRunCommand(input.value) },
        enabled = !running && input.value.isNotBlank(),
    ) {
        Text(stringResource(R.string.shell_run))
    }
    when (command) {
        AdbCommandState.None -> Unit
        is AdbCommandState.Running -> LabelledValue(
            label = stringResource(R.string.shell_label),
            value = stringResource(R.string.shell_running, command.command),
        )

        is AdbCommandState.Finished -> {
            LabelledValue(
                label = command.command,
                value = command.output.ifBlank { stringResource(R.string.shell_empty_output) },
            )
            if (command.errorOutput.isNotBlank()) {
                LabelledValue(
                    label = stringResource(R.string.shell_stderr),
                    value = command.errorOutput,
                )
            }
            Text(
                text = command.exitCode?.let { code -> stringResource(R.string.shell_exit_code, code) }
                    ?: stringResource(R.string.shell_exit_unknown),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        is AdbCommandState.Failed -> LabelledValue(
            label = command.command,
            value = stringResource(R.string.shell_failed, command.reason),
        )
    }
    Text(
        text = stringResource(R.string.shell_note),
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun AdbLinkState.generationOrNull(): SessionGeneration? = when (this) {
    AdbLinkState.Idle -> null
    is AdbLinkState.Connecting -> generation
    is AdbLinkState.WaitingForAuthorization -> generation
    is AdbLinkState.Connected -> generation
    is AdbLinkState.Failed -> generation
}

@Composable
private fun adbLinkText(state: AdbLinkState?): String = when (state) {
    null, AdbLinkState.Idle -> stringResource(R.string.adb_state_idle)
    is AdbLinkState.Connecting -> stringResource(R.string.adb_state_connecting)
    is AdbLinkState.WaitingForAuthorization -> stringResource(R.string.adb_state_waiting)
    is AdbLinkState.Connected ->
        stringResource(R.string.adb_state_connected, localizedPeerMode(state.peerMode))

    is AdbLinkState.Failed ->
        stringResource(R.string.adb_state_failed, state.reason.name, state.detail)
}

@Composable
private fun localizedPeerMode(mode: AdbPeerMode): String = stringResource(
    when (mode) {
        AdbPeerMode.DEVICE -> R.string.peer_mode_device
        AdbPeerMode.RECOVERY -> R.string.peer_mode_recovery
        AdbPeerMode.SIDELOAD -> R.string.peer_mode_sideload
        AdbPeerMode.UNKNOWN -> R.string.peer_mode_unknown
    },
)
