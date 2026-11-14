package com.arslan.shizuwall.utils

import android.content.Context
import android.content.Intent
import java.security.MessageDigest
import java.util.UUID

/**
 * Shared-secret authentication for ShizuWall's automation broadcasts
 * ([com.arslan.shizuwall.receivers.FirewallControlReceiver],
 * [com.arslan.shizuwall.receivers.ProfileControlReceiver] and
 * [com.arslan.shizuwall.receivers.AppInstalledReceiver]).
 *
 * The receivers are exported so automation apps (Tasker, MacroDroid, adb)
 * can drive the firewall, but an exported receiver with no sender check
 * lets *any* app on the device toggle the firewall. Every broadcast must
 * therefore carry the per-device token in the [EXTRA_TOKEN] extra; the
 * receivers silently drop broadcasts with a missing or wrong token.
 *
 * The token is a random UUID stored in its own private preferences file
 * (ShizuWallSecrets), which is excluded from Auto Backup (see
 * res/xml/backup_rules.xml and res/xml/data_extraction_rules.xml).
 * It is shown in the ADB Broadcast Usage dialog (Settings > Firewall),
 * where it can be copied and regenerated. The app's own internal
 * broadcasts attach it via [attachToken].
 */
object BroadcastAuth {

    /** Name of the broadcast extra carrying the automation token. */
    const val EXTRA_TOKEN = "token"

    /**
     * Private preferences file holding the automation token only, so it can
     * be excluded from Auto Backup without losing the user's firewall
     * settings (which live in MainActivity.PREF_NAME).
     */
    private const val PREFS_NAME = "ShizuWallSecrets"

    private const val KEY_AUTOMATION_TOKEN = "automation_token"

    /** Returns the per-device automation token, creating one on first use. */
    fun token(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var token = prefs.getString(KEY_AUTOMATION_TOKEN, null)
        if (token.isNullOrBlank()) {
            token = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_AUTOMATION_TOKEN, token).apply()
        }
        return token
    }

    /** Replaces the automation token with a fresh random value. */
    fun regenerateToken(context: Context): String {
        val token = UUID.randomUUID().toString()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_AUTOMATION_TOKEN, token).apply()
        return token
    }

    /** Adds the automation token to an intent, for the app's own broadcasts. */
    fun attachToken(context: Context, intent: Intent): Intent =
        intent.putExtra(EXTRA_TOKEN, token(context))

    /** True only when the intent carries the current automation token. */
    fun isAuthorized(context: Context, intent: Intent): Boolean {
        val supplied = intent.getStringExtra(EXTRA_TOKEN) ?: return false
        val expected = token(context).toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(supplied.toByteArray(Charsets.UTF_8), expected)
    }
}
