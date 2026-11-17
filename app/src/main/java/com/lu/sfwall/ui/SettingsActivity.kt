package com.lu.sfwall.ui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.transition.Transition
import android.transition.TransitionListenerAdapter
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.ActionMenuView
import androidx.core.view.children
import androidx.lifecycle.lifecycleScope
import com.lu.sfwall.R
import com.lu.sfwall.ladb.LadbManager
import com.lu.sfwall.services.AppMonitorService
import com.lu.sfwall.services.FloatingButtonService
import com.lu.sfwall.services.ForegroundDetectionService
import com.lu.sfwall.shell.RootShellExecutor
import com.lu.sfwall.shell.ShellExecutorProvider
import com.lu.sfwall.shizuku.ShizukuSetupActivity
import com.lu.sfwall.utils.BackupManager
import com.lu.sfwall.utils.ShizukuPackageResolver
import com.lu.sfwall.utils.UpdateChecker
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.platform.MaterialContainerTransform
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import com.lu.sfwall.firewall.FirewallCommands

class SettingsActivity : BaseActivity() {

    companion object {
        const val TRANSITION_NAME = "settings"
        private const val TOUR_DELAY_MS = 500L
    }

    private lateinit var sharedPreferences: SharedPreferences

    private fun containerTransform(ms: Long) = MaterialContainerTransform().apply {
        addTarget(android.R.id.content)
        duration = ms
        isElevationShadowEnabled = false
        addListener(object : TransitionListenerAdapter() {
            override fun onTransitionStart(transition: Transition) = layer(View.LAYER_TYPE_HARDWARE)
            override fun onTransitionEnd(transition: Transition) = layer(View.LAYER_TYPE_NONE)
            override fun onTransitionCancel(transition: Transition) = layer(View.LAYER_TYPE_NONE)
        })
    }

    private fun layer(type: Int) {
        findViewById<View>(R.id.settingsRoot)?.setLayerType(type, null)
    }

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) exportBackup(uri)
    }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) startImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setEnterSharedElementCallback(MaterialContainerTransformSharedElementCallback())
        window.enterTransition = null
        window.returnTransition = null
        window.sharedElementEnterTransition = containerTransform(450L)
        window.sharedElementReturnTransition = containerTransform(350L)
        super.onCreate(savedInstanceState)

        sharedPreferences = getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)

        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)
        findViewById<View>(android.R.id.content).transitionName = TRANSITION_NAME

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setupSettingsChrome(R.id.settingsRoot, toolbar)
        setupToolbarMenu(toolbar)
        toolbar.setNavigationOnClickListener {
            if (isTaskRoot) {
                startActivity(Intent(this, MainActivity::class.java))
            }
            finishAfterTransition()
        }

        findViewById<LinearLayout>(R.id.rowAppearance).setOnClickListener {
            startActivity(Intent(this, AppearanceSettingsActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.rowFirewall).setOnClickListener {
            startActivity(Intent(this, FirewallSettingsActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.rowHistory).setOnClickListener {
            startActivity(Intent(this, FirewallHistoryActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.rowBackend).setOnClickListener {
            startActivity(Intent(this, BackendSettingsActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.rowNotifications).setOnClickListener {
            startActivity(Intent(this, NotificationsSettingsActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.rowSecurity).setOnClickListener {
            startActivity(Intent(this, SecuritySettingsActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.btnDonate).setOnClickListener {
            val url = getString(R.string.buymeacoffee_url)
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        findViewById<LinearLayout>(R.id.btnGithub).setOnClickListener {
            val url = getString(R.string.github_url)
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        findViewById<LinearLayout>(R.id.btnWhatsNew).setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.changelog_title, com.lu.sfwall.BuildConfig.VERSION_NAME))
                .setMessage(getString(R.string.changelog_message))
                .setPositiveButton(R.string.ok, null)
                .show()
        }

        val switchAutoUpdate = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchAutoUpdate)
        switchAutoUpdate.isChecked = sharedPreferences.getBoolean(MainActivity.KEY_AUTO_CHECK_UPDATES, false)
        switchAutoUpdate.setOnCheckedChangeListener { _, checked ->
            sharedPreferences.edit().putBoolean(MainActivity.KEY_AUTO_CHECK_UPDATES, checked).apply()
        }
        findViewById<View>(R.id.rowAutoUpdate).setOnClickListener { switchAutoUpdate.toggle() }
        findViewById<View>(R.id.btnCheckUpdate).setOnClickListener { button ->
            button.isEnabled = false
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { UpdateChecker.fetchLatest() } }
                val release = result.getOrNull()
                when {
                    result.isFailure -> Toast.makeText(this@SettingsActivity, R.string.update_check_failed, Toast.LENGTH_SHORT).show()
                    release == null -> Toast.makeText(this@SettingsActivity, R.string.update_up_to_date, Toast.LENGTH_SHORT).show()
                    UpdateChecker.isNewer(release.tag, com.lu.sfwall.BuildConfig.VERSION_NAME) -> UpdateChecker.showDialog(this@SettingsActivity, release)
                    else -> Toast.makeText(this@SettingsActivity, R.string.update_up_to_date, Toast.LENGTH_SHORT).show()
                }
                button.isEnabled = true
            }
        }

        findViewById<LinearLayout>(R.id.btnContactDeveloper).setOnClickListener {
            val address = getString(R.string.contact_developer_email)
            val subject = getString(R.string.contact_developer_subject)
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:$address?subject=" + Uri.encode(subject))
                putExtra(Intent.EXTRA_EMAIL, arrayOf(address))
                putExtra(Intent.EXTRA_SUBJECT, subject)
            }
            try {
                startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                Snackbar.make(
                    findViewById(R.id.settingsRoot),
                    getString(R.string.contact_developer_no_app),
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }

        val tvVersion = findViewById<TextView>(R.id.tvVersion)
        tvVersion.text = getString(R.string.version_format, com.lu.sfwall.BuildConfig.VERSION_NAME)

        if (savedInstanceState == null && intent.getBooleanExtra(CoachMarks.EXTRA_TOUR, false)) {
            CoachMarks.show(this, listOf(
                CoachMarks.Step(
                    { toolbar.children.filterIsInstance<ActionMenuView>().firstOrNull()?.children?.lastOrNull() },
                    getString(R.string.shizuku_setup_guide),
                    getString(R.string.shizuku_guide_tour_message)
                ),
                CoachMarks.Step({ findViewById(R.id.rowBackend) }, getString(R.string.settings_category_backend_title), getString(R.string.settings_category_backend_desc), onTarget = {
                    startActivity(Intent(this, BackendSettingsActivity::class.java).putExtra(CoachMarks.EXTRA_TOUR, true))
                })
            ), TOUR_DELAY_MS)
        }
    }

    @SuppressLint("RestrictedApi")
    private fun setupToolbarMenu(toolbar: MaterialToolbar) {
        toolbar.inflateMenu(R.menu.menu_settings)
        (toolbar.menu as? MenuBuilder)?.setOptionalIconsVisible(true)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_shizuku_guide -> {
                    startActivity(Intent(this, ShizukuSetupActivity::class.java))
                    true
                }
                R.id.action_export_settings -> {
                    exportBackupLauncher.launch(BackupManager.defaultFileName())
                    true
                }
                R.id.action_import_settings -> {
                    importBackupLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                    true
                }
                R.id.action_reset_app -> {
                    showResetConfirmationDialog()
                    true
                }
                else -> false
            }
        }
    }

    private fun showResetConfirmationDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.reset_app_title))
            .setMessage(getString(R.string.reset_app_message))
            .setPositiveButton(getString(R.string.reset)) { _, _ -> resetApp() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun resetApp() {
        lifecycleScope.launch {
            try {
                stopResetSensitiveComponents()

                val cleanupPerformed = withContext(Dispatchers.IO) {
                    performBestEffortFirewallCleanup()
                }
                if (!cleanupPerformed) {
                    Toast.makeText(this@SettingsActivity, getString(R.string.firewall_cleanup_warning), Toast.LENGTH_LONG).show()
                }

                val clearRequested = requestSystemDataClear()
                if (clearRequested) {
                    Toast.makeText(this@SettingsActivity, getString(R.string.app_reset_complete), Toast.LENGTH_SHORT).show()
                    delay(1200)
                    finishAffinity()
                    android.os.Process.killProcess(android.os.Process.myPid())
                    System.exit(0)
                    return@launch
                }

                val fallbackSuccess = withContext(Dispatchers.IO) { performManualDataClearFallback() }
                if (!fallbackSuccess) {
                    throw IllegalStateException("Manual reset fallback failed")
                }

                Toast.makeText(this@SettingsActivity, getString(R.string.app_reset_complete), Toast.LENGTH_SHORT).show()
                finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(0)
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, getString(R.string.reset_app_failed, e.message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopResetSensitiveComponents() {
        try { stopService(Intent(this, AppMonitorService::class.java)) } catch (_: Exception) {}
        try { stopService(Intent(this, ForegroundDetectionService::class.java)) } catch (_: Exception) {}
        try { FloatingButtonService.stop(this) } catch (_: Exception) {}
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancelAll() } catch (_: Exception) {}
    }

    private suspend fun performBestEffortFirewallCleanup(): Boolean {
        return try {
            if (!sharedPreferences.getBoolean(MainActivity.KEY_FIREWALL_ENABLED, false)) return true

            val activePackages = sharedPreferences.getStringSet(MainActivity.KEY_ACTIVE_PACKAGES, emptySet()) ?: emptySet()
            val selectedApps = sharedPreferences.getStringSet(MainActivity.KEY_SELECTED_APPS, emptySet()) ?: emptySet()
            val allPackages = (activePackages + selectedApps)
                .filterNot { it == packageName || ShizukuPackageResolver.isShizukuPackage(this, it) }

            val mode = sharedPreferences.getString(MainActivity.KEY_WORKING_MODE, "SHIZUKU") ?: "SHIZUKU"
            val canPerformCleanup = when (mode) {
                "SHIZUKU" -> {
                    try {
                        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
                    } catch (_: Throwable) {
                        false
                    }
                }
                "ROOT" -> RootShellExecutor.hasRootAccess()
                else -> {
                    val dm = com.lu.sfwall.daemon.PersistentDaemonManager(this)
                    dm.isDaemonRunning() || LadbManager.getInstance(this).isConnected()
                }
            }

            if (!canPerformCleanup) return false

            val executor = ShellExecutorProvider.forContext(this)
            val chainResult = executor.exec(FirewallCommands.CHAIN3_DISABLE)

            executor.execBatch(
                FirewallCommands.unblockAll(allPackages)
            )

            if (mode == "LADB") {
                executor.exec("kill \\$(cat /data/local/tmp/daemon.pid 2>/dev/null) 2>/dev/null; pkill -f 'com.lu.sfwall.daemon.SystemDaemon' 2>/dev/null || true")
            }

            chainResult.success
        } catch (_: Exception) {
            false
        }
    }

    private fun requestSystemDataClear(): Boolean {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.clearApplicationUserData() == true
        } catch (_: Exception) {
            false
        }
    }

    private fun performManualDataClearFallback(): Boolean {
        var ok = true

        ok = clearAllSharedPrefs(this) && ok

        try {
            val dp = createDeviceProtectedStorageContext()
            ok = clearAllSharedPrefs(dp) && ok
        } catch (_: Exception) {
            ok = false
        }

        try {
            for (dbName in databaseList()) {
                if (!deleteDatabase(dbName)) ok = false
            }
        } catch (_: Exception) {
            ok = false
        }

        for (dir in listOf(filesDir, cacheDir, codeCacheDir, noBackupFilesDir, externalCacheDir, getExternalFilesDir(null))) {
            ok = (dir?.listFiles()?.all { it.deleteRecursively() } ?: true) && ok
        }

        return ok
    }

    private fun clearAllSharedPrefs(context: Context): Boolean {
        var ok = true
        val sharedPrefsDir = File(context.filesDir.parentFile, "shared_prefs")
        val prefNames = sharedPrefsDir.listFiles()
            ?.mapNotNull { file ->
                val name = file.name
                if (name.endsWith(".xml")) name.removeSuffix(".xml") else null
            }
            ?.toSet()
            ?: emptySet()

        for (name in prefNames) {
            try {
                if (!context.deleteSharedPreferences(name)) {
                    context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
                }
            } catch (_: Exception) {
                ok = false
            }
        }
        return ok
    }

    private fun exportBackup(uri: Uri) {
        lifecycleScope.launch {
            try {
                BackupManager.exportToUri(this@SettingsActivity, uri)
                Toast.makeText(this@SettingsActivity, R.string.export_successful, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, getString(R.string.export_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startImport(uri: Uri) {
        lifecycleScope.launch {
            val plan = try {
                val parsed = BackupManager.readFromUri(this@SettingsActivity, uri)
                withContext(Dispatchers.IO) { BackupManager.prepareImport(this@SettingsActivity, parsed) }
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, getString(R.string.import_failed, e.message), Toast.LENGTH_LONG).show()
                return@launch
            }
            MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle(getString(R.string.backup_import_confirm_title))
                .setMessage(getString(R.string.backup_import_confirm_message, plan.restoredApps, plan.restoredProfiles, plan.dropped))
                .setPositiveButton(getString(R.string.backup_import_confirm_apply)) { _, _ -> applyImport(plan) }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }

    private fun applyImport(plan: BackupManager.ImportPlan) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                BackupManager.apply(this@SettingsActivity, plan)
            }
            Toast.makeText(
                this@SettingsActivity,
                getString(R.string.backup_import_summary, result.restoredApps, result.restoredProfiles, result.dropped),
                Toast.LENGTH_LONG
            ).show()
            setResult(RESULT_OK)
            recreateWithAnimation()
        }
    }

}
