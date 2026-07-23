package ru.forum.adbfastboottool

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Welcome / onboarding screen.
 *
 * The original user-owned artwork remains an immutable centerCrop background.
 * The risk acknowledgement is persisted, but entry authorization is session-scoped:
 * every cold/full app restart returns here. Exported USB attach intents cannot bypass
 * the gate. Storage and risk acceptance are mandatory; notification
 * and battery permissions remain visible recommendations.
 */
class WelcomeActivity : AppCompatActivity() {

    private lateinit var checkbox: CheckBox
    private lateinit var btnPrimaryAction: Button
    private lateinit var tvStorageChip: TextView
    private lateinit var tvNotificationsChip: TextView
    private lateinit var tvBatteryChip: TextView
    private lateinit var tvWelcomeStatus: TextView
    private var pendingUsbAttach = false

    private val settingsLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
            refreshGateState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pendingUsbAttach = intent.getBooleanExtra(EXTRA_PENDING_USB_ATTACH, false)
        if (OnboardingGate.canEnterMain(this)) {
            launchMainAfterGate()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) { ThemePalette.extractAndCache(applicationContext) }
        setContentView(R.layout.activity_welcome)

        checkbox = findViewById(R.id.checkboxAgree)
        btnPrimaryAction = findViewById(R.id.btnPrimaryAction)
        tvStorageChip = findViewById(R.id.tvStorageChip)
        tvNotificationsChip = findViewById(R.id.tvNotificationsChip)
        tvBatteryChip = findViewById(R.id.tvBatteryChip)
        tvWelcomeStatus = findViewById(R.id.tvWelcomeStatus)

        checkbox.isChecked = OnboardingGate.isCompleted(this)
        checkbox.setOnCheckedChangeListener { _, _ -> refreshGateState() }
        btnPrimaryAction.setOnClickListener { handlePrimaryAction() }
        tvStorageChip.setOnClickListener { openStoragePermissionSettings() }
        tvNotificationsChip.setOnClickListener { requestNotificationPermissionOrSettings() }
        tvBatteryChip.setOnClickListener { openBatteryOptimizationSettings() }
        findViewById<android.view.View>(R.id.riskRow).setOnClickListener {
            if (checkbox.isEnabled) checkbox.isChecked = !checkbox.isChecked
        }

        refreshGateState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingUsbAttach = pendingUsbAttach || intent.getBooleanExtra(EXTRA_PENDING_USB_ATTACH, false)
    }

    override fun onResume() {
        super.onResume()
        if (::checkbox.isInitialized) refreshGateState()
    }

    private fun handlePrimaryAction() {
        val status = PermissionGate.status(this)
        if (!status.allRequiredGranted) {
            requestNextRequiredPermission()
            return
        }
        if (!checkbox.isChecked) {
            Toast.makeText(this, getString(R.string.onboarding_confirm_risk_first), Toast.LENGTH_SHORT).show()
            return
        }
        if (!OnboardingGate.complete(this)) {
            Toast.makeText(this, getString(R.string.onboarding_gate_save_failed), Toast.LENGTH_LONG).show()
            refreshGateState()
            return
        }
        launchMainAfterGate()
    }

    private fun launchMainAfterGate() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_STARTUP_SCAN_AFTER_WELCOME, pendingUsbAttach)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    /** Updates compact permission chips and the mandatory storage/risk gate. */
    private fun refreshGateState() {
        val status = PermissionGate.status(this)
        val requiredReady = status.allRequiredGranted

        chip(tvStorageChip, getString(R.string.perm_storage), status.storage, required = true)
        chip(tvNotificationsChip, getString(R.string.perm_notifications), status.notifications, required = false)
        chip(tvBatteryChip, getString(R.string.perm_battery_opt), status.batteryOptIgnored, required = false)

        checkbox.isEnabled = requiredReady

        val welcomeReady = status.storage && checkbox.isChecked
        tvWelcomeStatus.text = when {
            !status.storage -> getString(R.string.onboarding_need_storage_short)
            !checkbox.isChecked -> getString(R.string.onboarding_confirm_risk_short)
            else -> getString(R.string.onboarding_ready_short)
        }
        tvWelcomeStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (welcomeReady) R.drawable.ic_status_check_green else R.drawable.ic_status_warning_amber,
            0,
            0,
            0
        )
        tvWelcomeStatus.setTextColor(
            getColor(if (welcomeReady) R.color.log_success else R.color.log_warning)
        )

        btnPrimaryAction.text = if (requiredReady) {
            getString(R.string.onboarding_enter_button)
        } else {
            getString(R.string.onboarding_grant_button)
        }
        btnPrimaryAction.alpha = if (welcomeReady || !requiredReady) 1.0f else 0.78f
    }

    private fun chip(view: TextView, label: String, granted: Boolean, required: Boolean) {
        val iconRes = when {
            granted -> R.drawable.ic_status_check_green
            required -> R.drawable.ic_status_warning_amber
            else -> R.drawable.ic_status_optional_gray
        }
        view.text = label
        view.alpha = if (granted) 1.0f else 0.88f
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
        view.setTextColor(
            getColor(
                when {
                    granted -> R.color.text_primary
                    required -> R.color.log_warning
                    else -> R.color.text_secondary
                }
            )
        )
    }

    /** Only storage blocks entry. Notifications and battery are recommendations. */
    private fun requestNextRequiredPermission() {
        if (!PermissionGate.hasStorage(this)) {
            openStoragePermissionSettings()
            return
        }
        refreshGateState()
    }

    private fun requestNotificationPermissionOrSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || PermissionGate.hasNotifications(this)) {
            openNotificationSettings()
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val requestCount = prefs.getInt(PREF_NOTIFICATION_REQUEST_COUNT, 0)
        val permanentlyDenied = requestCount >= 2 &&
            !shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)
        if (permanentlyDenied) {
            openNotificationSettings()
            Toast.makeText(this, getString(R.string.onboarding_notifications_open_settings), Toast.LENGTH_LONG).show()
            return
        }

        prefs.edit().putInt(PREF_NOTIFICATION_REQUEST_COUNT, requestCount + 1).apply()
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
    }


    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        }
        if (runCatching { settingsLauncher.launch(intent) }.isFailure) {
            openApplicationDetails(getString(R.string.perm_open_settings_manually))
        }
    }

    private fun openStoragePermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                settingsLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            } catch (_: Exception) {
                try {
                    settingsLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: Exception) {
                    openApplicationDetails(getString(R.string.perm_open_settings_manually))
                }
            }
        } else if (PermissionGate.hasStorage(this)) {
            openApplicationDetails(getString(R.string.permission_status_granted))
        } else {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                REQ_STORAGE
            )
        }
    }

    private fun openBatteryOptimizationSettings() {
        val appLabel = applicationInfo.loadLabel(packageManager).toString()
        val candidates = buildList {
            add(Intent().apply {
                component = ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
                putExtra("package_name", packageName)
                putExtra("package_label", appLabel)
            })
            add(Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST").apply {
                putExtra("package_name", packageName)
                putExtra("package_label", appLabel)
            })
            add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
            add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            })
        }

        var launched = false
        for (candidate in candidates) {
            val resolvable = runCatching {
                candidate.resolveActivity(packageManager) != null
            }.getOrDefault(false)
            if (!resolvable) continue

            if (runCatching { settingsLauncher.launch(candidate) }.isSuccess) {
                launched = true
                break
            }
        }

        Toast.makeText(
            this,
            if (launched) getString(R.string.onboarding_miui_battery_fallback_toast)
            else getString(R.string.perm_open_settings_manually),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun openApplicationDetails(message: String) {
        try {
            settingsLauncher.launch(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (_: Exception) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshGateState()

        if (requestCode == REQ_NOTIFICATIONS &&
            grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            val requestCount = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(PREF_NOTIFICATION_REQUEST_COUNT, 0)
            if (requestCount >= 2 &&
                !shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)
            ) {
                openNotificationSettings()
                Toast.makeText(this, getString(R.string.onboarding_notifications_open_settings), Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val EXTRA_PENDING_USB_ATTACH = "nekoflash_pending_usb_attach"
        const val EXTRA_STARTUP_SCAN_AFTER_WELCOME = "nekoflash_startup_scan_after_welcome"

        private const val PREFS_NAME = "nekoflash_onboarding"
        private const val PREF_NOTIFICATION_REQUEST_COUNT = "notification_request_count"
        private const val REQ_STORAGE = 200
        private const val REQ_NOTIFICATIONS = 201
    }
}
