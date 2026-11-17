package com.lu.sfwall.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.lu.sfwall.BuildConfig
import com.lu.sfwall.FirewallMode
import com.lu.sfwall.firewall.FirewallTargets
import com.lu.sfwall.firewall.PerUidFirewall
import com.lu.sfwall.model.Profile
import com.lu.sfwall.profiles.ProfilesStore
import com.lu.sfwall.receivers.FirewallControlReceiver
import com.lu.sfwall.security.AppLock
import com.lu.sfwall.services.AppMonitorService
import com.lu.sfwall.services.FloatingButtonService
import com.lu.sfwall.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Curated SfWall backup, format "sfwall-backup", version 1.
 *
 * File layout:
 * ```
 * {
 *   "format": "sfwall-backup", "version": 1,
 *   "exportedAt": <epoch ms>, "appVersion": "4.7.2",
 *   "profiles": [<Profile JSON objects>],
 *   "activeProfileId": "<uuid>",               // absent when none active
 *   "selectedApps": {
 *     "apps": ["com.example.app", "150:com.example.app"],
 *     "appModes": {"com.example.app": 1},
 *     "firewallMode": "DEFAULT",
 *     "showSystemApps": false,
 *     "mobileOnlyApps": ["com.example.app"]
 *   },
 *   "settings": {"auto_check_updates": true, ...}
 * }
 * ```
 *
 * What is backed up: all profiles (with their app lists and modes), the
 * global per-app selection and modes, and the non-sensitive settings
 * switches listed below. What is never backed up: the firewall on/off
 * state, the automation token (SfWallSecrets), the AppLock PIN hash, the
 * LADB daemon token (daemon_prefs), device ids and runtime state.
 *
 * Validation only checks `format` and `version`. The package name is
 * never checked, so a file exports and imports on any build that
 * understands this format (debug or release, old or new package).
 *
 * Import is a full replace: profiles, selection and the listed settings
 * become exactly what the backup holds. Apps the backup references that
 * cannot be used on this device — not installed, not blockable, or without
 * the INTERNET permission — are dropped and counted, so the confirm dialog
 * promises exactly the apps that stay selected afterwards.
 */
object BackupManager {

    const val FORMAT = "sfwall-backup"
    const val VERSION = 1

    /**
     * Settings keys copied into a backup, grouped by the type the app reads
     * them with. Anything not listed here is never written to or read from
     * a backup.
     */
    private val BOOLEAN_SETTINGS = setOf(
        MainActivity.KEY_SKIP_ENABLE_CONFIRM,
        MainActivity.KEY_SKIP_ERROR_DIALOG,
        MainActivity.KEY_SKIP_ANDROID11_INFO,
        MainActivity.KEY_KEEP_ERROR_APPS_SELECTED,
        MainActivity.KEY_SHOW_OTHER_PROFILES,
        MainActivity.KEY_SHOW_APP_INFO_BUTTON,
        MainActivity.KEY_MOVE_SELECTED_TOP,
        MainActivity.KEY_HIDE_UNBLOCKABLE_APPS,
        MainActivity.KEY_HIDE_NO_INTERNET_APPS,
        MainActivity.KEY_USE_DYNAMIC_COLOR,
        MainActivity.KEY_USE_AMOLED_BLACK,
        MainActivity.KEY_AUTO_ENABLE_ON_SHIZUKU_START,
        MainActivity.KEY_APPLY_ROOT_RULES_AFTER_REBOOT,
        MainActivity.KEY_APP_MONITOR_ENABLED,
        MainActivity.KEY_AUTO_FIREWALL_NEW_APPS,
        MainActivity.KEY_SHOW_FIREWALL_STATUS_NOTIFICATION,
        MainActivity.KEY_AUTO_ENABLE_ON_PROFILE_ACTIVATE,
        MainActivity.KEY_TILE_COLLAPSE_SHADE,
        MainActivity.KEY_REMEMBER_DISABLED_APPS,
        MainActivity.KEY_AUTO_CHECK_UPDATES,
        FloatingButtonService.KEY_FLOATING_BUTTON_ENABLED
    )
    private val STRING_SETTINGS = setOf(
        MainActivity.KEY_SELECTED_FONT,
        MainActivity.KEY_WORKING_MODE,
        MainActivity.KEY_SORT_ORDER
    )
    private val INT_SETTINGS = setOf(
        MainActivity.KEY_NIGHT_MODE,
        MainActivity.KEY_SCREEN_LOCK_DELAY_SECONDS
    )
    private val STRING_SET_SETTINGS = setOf(
        MainActivity.KEY_FAVORITE_APPS
    )

    /**
     * Upstream export schema, written by the pre-rebrand builds
     * ("shizuwall.preferences", version <= 3). A legacy file is a dump of
     * whole SharedPreferences files:
     * ```
     * {"schema": "shizuwall.preferences", "version": 3, "exported_at": ...,
     *  "files": {"ShizuWallPrefs": {"entries": [
     *      {"key": "selected_apps", "type": "string_set", "value": [...]},
     *      {"key": "profiles_json", "type": "string",
     *       "value": "[{\"id\": ..., \"name\": ..., ...}]"}, ...]},
     *   "app_prefs": {...}, "ladb_prefs": {...}, "daemon_prefs": {...}}}
     * ```
     * Entry types are boolean, string, int, long, float and string_set.
     * Only the file holding the main selection is read, found by content
     * (it holds selected_apps), never by file name: the old prefs file is
     * named "ShizuWallPrefs" while the new one is "SfWallPrefs". The other
     * files (app_prefs, ladb_prefs, daemon_prefs) are ignored outright.
     * Profiles travel inside that same file as a JSON array string under
     * the profiles_json key, in the same shape Profile.fromJson reads.
     */
    private const val LEGACY_SCHEMA = "shizuwall.preferences"
    private const val LEGACY_MAX_VERSION = 3

    /** AppLock keys are never imported, even if a hand-made legacy file holds them. */
    private val LEGACY_APP_LOCK_KEYS = setOf(
        AppLock.KEY_ENABLED, AppLock.KEY_PIN, AppLock.KEY_BIOMETRIC, AppLock.KEY_PIN_LENGTH
    )

    /**
     * Keys that identify the main prefs file when a legacy export has no
     * selected_apps entry (an export with an empty selection writes none).
     * app_prefs only ever holds onboarding_complete, so it can never match.
     */
    private val LEGACY_MAIN_KEYS: Set<String> = setOf(
        MainActivity.KEY_PROFILES,
        MainActivity.KEY_APP_MODES,
        MainActivity.KEY_FIREWALL_MODE,
        MainActivity.KEY_FAVORITE_APPS,
        MainActivity.KEY_MOBILE_ONLY_APPS,
        MainActivity.KEY_SHOW_SYSTEM_APPS,
        MainActivity.KEY_ACTIVE_PROFILE_ID,
        MainActivity.KEY_SELECTED_COUNT
    ) + BOOLEAN_SETTINGS + STRING_SETTINGS + INT_SETTINGS + STRING_SET_SETTINGS

    data class ParsedBackup(
        val profiles: List<Profile>,
        val activeProfileId: String?,
        val selectedApps: Set<String>,
        val appModesJson: String,
        val firewallMode: String,
        val showSystemApps: Boolean,
        val mobileOnlyApps: Set<String>,
        val settings: Map<String, Any>
    )

    data class ImportPlan(
        val profiles: List<Profile>,
        val activeProfileId: String?,
        val selectedApps: Set<String>,
        val appModesJson: String,
        val firewallMode: String,
        val showSystemApps: Boolean,
        val mobileOnlyApps: Set<String>,
        val settings: Map<String, Any>,
        val droppedKeys: Set<String>
    ) {
        val restoredApps: Int get() = selectedApps.size
        val restoredProfiles: Int get() = profiles.size
        val dropped: Int get() = droppedKeys.size
    }

    data class ImportResult(
        val restoredApps: Int,
        val restoredProfiles: Int,
        val dropped: Int
    )

    fun defaultFileName(): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "sfwall-backup-$ts.json"
    }

    fun build(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
        val profiles = ProfilesStore.getProfiles(context)
        val selected = prefs.getStringSet(MainActivity.KEY_SELECTED_APPS, emptySet()) ?: emptySet()
        val mobileOnly = prefs.getStringSet(MainActivity.KEY_MOBILE_ONLY_APPS, emptySet()) ?: emptySet()
        val settings = JSONObject()
        for (key in BOOLEAN_SETTINGS) {
            if (prefs.contains(key)) settings.put(key, prefs.getBoolean(key, false))
        }
        for (key in STRING_SETTINGS) {
            prefs.getString(key, null)?.let { settings.put(key, it) }
        }
        for (key in INT_SETTINGS) {
            if (prefs.contains(key)) settings.put(key, prefs.getInt(key, 0))
        }
        for (key in STRING_SET_SETTINGS) {
            val set = prefs.getStringSet(key, emptySet()) ?: emptySet()
            settings.put(key, JSONArray(set.sorted()))
        }
        return JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("appVersion", BuildConfig.VERSION_NAME)
            put("profiles", JSONArray().apply { profiles.forEach { put(it.toJson()) } })
            prefs.getString(MainActivity.KEY_ACTIVE_PROFILE_ID, null)?.let { put("activeProfileId", it) }
            put("selectedApps", JSONObject().apply {
                put("apps", JSONArray(selected.sorted()))
                put("appModes", safeJsonObject(prefs.getString(MainActivity.KEY_APP_MODES, "{}")))
                put("firewallMode", prefs.getString(MainActivity.KEY_FIREWALL_MODE, FirewallMode.DEFAULT.name)
                    ?: FirewallMode.DEFAULT.name)
                put("showSystemApps", prefs.getBoolean(MainActivity.KEY_SHOW_SYSTEM_APPS, false))
                put("mobileOnlyApps", JSONArray(mobileOnly.sorted()))
            })
            put("settings", settings)
        }
    }

    private fun safeJsonObject(raw: String?): JSONObject {
        return try {
            JSONObject(raw ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
    }

    /**
     * Parses and validates a backup file. Throws IllegalArgumentException
     * when the file is not a SfWall backup or its version is unsupported.
     * Unknown extra fields are ignored; settings keys outside the allowlist
     * and values of the wrong type are skipped.
     */
    fun parse(text: String): ParsedBackup {
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            throw IllegalArgumentException("Not a SfWall backup file")
        }
        if (root.optString("schema") == LEGACY_SCHEMA) {
            return parseLegacy(root)
        }
        if (root.optString("format") != FORMAT) {
            throw IllegalArgumentException("Not a SfWall backup file")
        }
        val version = root.optInt("version", -1)
        if (version != VERSION) {
            throw IllegalArgumentException("Unsupported backup version: $version")
        }
        val profiles = ArrayList<Profile>()
        val profilesJson = root.optJSONArray("profiles")
        if (profilesJson != null) {
            for (i in 0 until profilesJson.length()) {
                val obj = profilesJson.optJSONObject(i) ?: continue
                val profile = Profile.fromJson(obj)
                if (profile.id.isNotBlank() && profile.name.isNotBlank()) profiles.add(profile)
            }
        }
        val sel = root.optJSONObject("selectedApps")
        val settings = LinkedHashMap<String, Any>()
        val settingsJson = root.optJSONObject("settings")
        if (settingsJson != null) {
            val keys = settingsJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = settingsJson.opt(key)
                when {
                    key in BOOLEAN_SETTINGS && value is Boolean -> settings[key] = value
                    key in STRING_SETTINGS && value is String -> settings[key] = value
                    key in INT_SETTINGS && value is Number -> settings[key] = value.toInt()
                    key in STRING_SET_SETTINGS && value is JSONArray -> settings[key] = jsonArrayToSet(value)
                }
            }
        }
        return ParsedBackup(
            profiles = profiles.distinctBy { it.id },
            activeProfileId = root.optString("activeProfileId", null),
            selectedApps = jsonArrayToSet(sel?.optJSONArray("apps")),
            appModesJson = sel?.optJSONObject("appModes")?.toString() ?: "{}",
            firewallMode = FirewallMode.fromName(sel?.optString("firewallMode")).name,
            showSystemApps = sel?.optBoolean("showSystemApps", false) ?: false,
            mobileOnlyApps = jsonArrayToSet(sel?.optJSONArray("mobileOnlyApps")),
            settings = settings
        )
    }

    /**
     * Reads an upstream ("shizuwall.preferences", version <= 3) export into
     * the same [ParsedBackup] the new format produces, so the existing
     * prepareImport -> confirm -> apply flow runs unchanged. Entries are
     * converted by their declared type and checked against the same
     * allowlists; an entry whose key is not allowlisted, whose type does
     * not match, or whose value has the wrong JSON type is skipped, never
     * fatal. A file that is not a legacy backup at all (bad version, no
     * files object, no main prefs file) throws, and the caller shows the
     * usual "import failed" path without applying anything.
     */
    private fun parseLegacy(root: JSONObject): ParsedBackup {
        val version = root.optInt("version", -1)
        if (version < 1 || version > LEGACY_MAX_VERSION) {
            throw IllegalArgumentException("Unsupported backup version: $version")
        }
        val files = root.optJSONObject("files")
            ?: throw IllegalArgumentException("Not a SfWall backup file")
        val entries = findLegacyMainEntries(files)
            ?: throw IllegalArgumentException("Not a SfWall backup file")
        val values = LinkedHashMap<String, Any>()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val key = entry.optString("key", "")
            if (key.isEmpty() || key in LEGACY_APP_LOCK_KEYS) continue
            when (entry.optString("type", "")) {
                "boolean" -> {
                    val v = entry.opt("value")
                    if (v is Boolean) values[key] = v
                }
                "string" -> {
                    val v = entry.opt("value")
                    if (v is String) values[key] = v
                }
                "int", "long" -> {
                    val v = entry.opt("value")
                    if (v is Int || v is Long) {
                        val l = (v as Number).toLong()
                        if (l in Int.MIN_VALUE..Int.MAX_VALUE) values[key] = l.toInt()
                    }
                }
                "string_set" -> {
                    val arr = entry.optJSONArray("value")
                    if (arr != null) values[key] = jsonArrayToSet(arr)
                }
                // "float" and unknown types: no allowlisted key uses them.
            }
        }
        val settings = LinkedHashMap<String, Any>()
        for ((key, value) in values) {
            when {
                key in BOOLEAN_SETTINGS && value is Boolean -> settings[key] = value
                key in STRING_SETTINGS && value is String -> settings[key] = value
                key in INT_SETTINGS && value is Int -> settings[key] = value
                key in STRING_SET_SETTINGS && value is Set<*> ->
                    settings[key] = value.filterIsInstance<String>().toSet()
            }
        }
        return ParsedBackup(
            profiles = parseLegacyProfiles(values[MainActivity.KEY_PROFILES] as? String),
            activeProfileId = values[MainActivity.KEY_ACTIVE_PROFILE_ID] as? String,
            selectedApps = stringSetOf(values[MainActivity.KEY_SELECTED_APPS]),
            appModesJson = values[MainActivity.KEY_APP_MODES] as? String ?: "{}",
            firewallMode = FirewallMode.fromName(values[MainActivity.KEY_FIREWALL_MODE] as? String).name,
            showSystemApps = values[MainActivity.KEY_SHOW_SYSTEM_APPS] as? Boolean ?: false,
            mobileOnlyApps = stringSetOf(values[MainActivity.KEY_MOBILE_ONLY_APPS]),
            settings = settings
        )
    }

    /**
     * Finds the main prefs entries inside a legacy "files" object by
     * content, not by file name. Preferred: the file holding selected_apps.
     * Fallback: the first file holding any other known main-prefs key, for
     * exports whose selection was empty (no selected_apps entry is written
     * then). The other files are never read.
     */
    private fun findLegacyMainEntries(files: JSONObject): JSONArray? {
        val names = files.keys()
        val candidates = ArrayList<JSONArray>()
        while (names.hasNext()) {
            val fileObj = files.optJSONObject(names.next()) ?: continue
            val entries = fileObj.optJSONArray("entries") ?: continue
            candidates.add(entries)
            if (hasLegacyKey(entries, MainActivity.KEY_SELECTED_APPS)) return entries
        }
        for (entries in candidates) {
            if (LEGACY_MAIN_KEYS.any { hasLegacyKey(entries, it) }) return entries
        }
        return null
    }

    private fun hasLegacyKey(entries: JSONArray, key: String): Boolean {
        for (i in 0 until entries.length()) {
            if (entries.optJSONObject(i)?.optString("key") == key) return true
        }
        return false
    }

    /**
     * Profiles in a legacy export are a JSON array string under the
     * profiles_json key, in the same shape Profile.fromJson reads. A
     * malformed string yields no profiles instead of failing the import;
     * bad single profiles are skipped like in the new-format path.
     */
    private fun parseLegacyProfiles(raw: String?): List<Profile> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Profile>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val profile = Profile.fromJson(obj)
                if (profile.id.isNotBlank() && profile.name.isNotBlank()) out.add(profile)
            }
            out.distinctBy { it.id }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun stringSetOf(value: Any?): Set<String> {
        if (value !is Set<*>) return emptySet()
        return value.filterIsInstance<String>().toSet()
    }

    /**
     * Keeps only keys the main list would keep selected. A key is kept when
     * its package is installed, the firewall can block its UID, and the app
     * declares the INTERNET permission — the same three conditions as
     * [AppInfo.isSelectable] (hasInternet && isBlockable). The INTERNET
     * check matters: MainActivity drops any selected app that lacks it when
     * the list loads (dropUnsupportedSelections), so importing one would
     * promise a restore the main list then silently undoes. Only the
     * declaration is checked, never the grant state. Every rejected key is
     * counted in [ImportPlan.droppedKeys], so the confirm dialog promises
     * exactly the apps that stay selected afterwards.
     */
    fun prepareImport(context: Context, parsed: ParsedBackup): ImportPlan {
        val dropped = LinkedHashSet<String>()
        val internetCache = HashMap<String, Boolean>()
        fun keep(key: String): Boolean {
            if (!isInstalled(context, key)) {
                dropped.add(key)
                return false
            }
            if (!PerUidFirewall.isBlockableKey(context, key)) {
                dropped.add(key)
                return false
            }
            val hasInternet = internetCache.getOrPut(AppKey.packageOf(key)) {
                hasInternetPermission(context, key)
            }
            if (!hasInternet) {
                dropped.add(key)
                return false
            }
            return true
        }
        val selected = parsed.selectedApps.filter(::keep).toSet()
        val appModes = FirewallTargets.parseAppModes(parsed.appModesJson)
            .filterKeys { it in selected }
        val appModesJson = JSONObject().apply {
            appModes.forEach { (key, mode) -> put(key, mode) }
        }.toString()
        val profiles = parsed.profiles.map { profile ->
            profile.copy(packages = profile.packages.filter(::keep).toSet())
        }
        val mobileOnly = parsed.mobileOnlyApps.filter(::keep).toSet()
        val activeId = parsed.activeProfileId?.takeIf { id -> profiles.any { it.id == id } }
        val settings = parsed.settings.toMutableMap()
        (settings[MainActivity.KEY_FAVORITE_APPS] as? Set<*>)?.let { favs ->
            settings[MainActivity.KEY_FAVORITE_APPS] =
                favs.filterIsInstance<String>().filter(::keep).toSet()
        }
        return ImportPlan(
            profiles = profiles,
            activeProfileId = activeId,
            selectedApps = selected,
            appModesJson = appModesJson,
            firewallMode = parsed.firewallMode,
            showSystemApps = parsed.showSystemApps,
            mobileOnlyApps = mobileOnly,
            settings = settings,
            droppedKeys = dropped
        )
    }

    fun isInstalled(context: Context, key: String): Boolean {
        val pkg = AppKey.packageOf(key)
        return try {
            context.packageManager.getApplicationInfo(
                pkg,
                PackageManager.MATCH_DISABLED_COMPONENTS or
                    PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Whether the app behind this key declares the INTERNET permission,
     * matching MainActivity's hasInternet (requestedPermissions contains
     * INTERNET). Only the declaration matters; the grant state does not.
     * A query failure is treated as no INTERNET: without proof the app
     * declares it, the main list would not keep it selected either.
     */
    private fun hasInternetPermission(context: Context, key: String): Boolean {
        return try {
            val info = context.packageManager.getPackageInfo(
                AppKey.packageOf(key),
                PackageManager.GET_PERMISSIONS
            )
            info.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Full replace: profiles, selection and the listed settings become
     * exactly what the backup holds. The firewall on/off state, the
     * automation token and the AppLock PIN are never touched. When the
     * firewall is on, its rules are re-applied from the new selection so
     * the restore takes effect immediately.
     */
    fun apply(context: Context, plan: ImportPlan): ImportResult {
        val prefs = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
        val wasEnabled = prefs.getBoolean(MainActivity.KEY_FIREWALL_ENABLED, false)
        prefs.edit().apply {
            putString(MainActivity.KEY_PROFILES, ProfilesStore.encode(plan.profiles))
            putStringSet(MainActivity.KEY_SELECTED_APPS, plan.selectedApps)
            putInt(MainActivity.KEY_SELECTED_COUNT, plan.selectedApps.size)
            putString(MainActivity.KEY_APP_MODES, plan.appModesJson)
            putString(MainActivity.KEY_FIREWALL_MODE, plan.firewallMode)
            putBoolean(MainActivity.KEY_SHOW_SYSTEM_APPS, plan.showSystemApps)
            putStringSet(MainActivity.KEY_MOBILE_ONLY_APPS, plan.mobileOnlyApps)
            if (plan.activeProfileId != null) {
                putString(MainActivity.KEY_ACTIVE_PROFILE_ID, plan.activeProfileId)
            } else {
                remove(MainActivity.KEY_ACTIVE_PROFILE_ID)
            }
            (BOOLEAN_SETTINGS + STRING_SETTINGS + INT_SETTINGS + STRING_SET_SETTINGS).forEach { remove(it) }
            plan.settings.forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            apply()
        }
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_complete", true)
            .apply()
        val monitorOn = prefs.getBoolean(MainActivity.KEY_APP_MONITOR_ENABLED, false) ||
            prefs.getBoolean(MainActivity.KEY_AUTO_FIREWALL_NEW_APPS, false) ||
            prefs.getBoolean(MainActivity.KEY_SHOW_FIREWALL_STATUS_NOTIFICATION, false)
        val monitorIntent = Intent(context, AppMonitorService::class.java)
        try {
            if (monitorOn) context.startForegroundService(monitorIntent)
            else context.stopService(monitorIntent)
        } catch (_: Exception) {
        }
        if (wasEnabled) {
            context.sendBroadcast(BroadcastAuth.attachToken(context, Intent(context, FirewallControlReceiver::class.java).apply {
                action = MainActivity.ACTION_FIREWALL_CONTROL
                putExtra(MainActivity.EXTRA_FIREWALL_ENABLED, true)
            }))
        }
        return ImportResult(plan.restoredApps, plan.restoredProfiles, plan.dropped)
    }

    suspend fun exportToUri(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val json = build(context)
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(json.toString(2).toByteArray(Charsets.UTF_8))
            out.flush()
        } ?: throw IllegalStateException("Unable to open output stream")
    }

    suspend fun readFromUri(context: Context, uri: Uri): ParsedBackup = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: throw IllegalStateException("Unable to open input stream")
        parse(text)
    }

    private fun jsonArrayToSet(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        val result = LinkedHashSet<String>()
        for (i in 0 until array.length()) {
            val value = array.optString(i, null)
            if (!value.isNullOrEmpty()) result.add(value)
        }
        return result
    }
}
