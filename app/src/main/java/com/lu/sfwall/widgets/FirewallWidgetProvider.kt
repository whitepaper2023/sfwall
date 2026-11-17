package com.lu.sfwall.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast
import com.lu.sfwall.FirewallMode
import com.lu.sfwall.R
import com.lu.sfwall.receivers.FirewallControlReceiver
import com.lu.sfwall.ui.MainActivity
import com.lu.sfwall.utils.BroadcastAuth
import com.lu.sfwall.utils.FirewallUtils

class FirewallWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        WidgetTheme.clear(context, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_CLICK) {
            if (!BroadcastAuth.isAuthorized(context, intent)) {
                android.util.Log.w(TAG, "Rejected widget click without a valid token")
                return
            }
            val sharedPreferences = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            val newState = !FirewallUtils.loadFirewallEnabled(sharedPreferences)

            if (!FirewallUtils.checkBackendReady(context)) {
                return
            }

            if (newState) {
                val selectedApps = FirewallUtils.loadSelectedApps(context, sharedPreferences)
                val firewallMode = FirewallMode.fromName(sharedPreferences.getString(MainActivity.KEY_FIREWALL_MODE, FirewallMode.DEFAULT.name))

                if (selectedApps.isEmpty() && !firewallMode.allowsDynamicSelection()) {
                    Toast.makeText(context, context.getString(R.string.no_apps_selected), Toast.LENGTH_SHORT).show()
                    return
                }
            }

            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = android.content.ComponentName(context, FirewallWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            for (appWidgetId in appWidgetIds) {
                updateAppWidgetOptimistic(context, appWidgetManager, appWidgetId, newState)
            }

            val toggleIntent = Intent(context, FirewallControlReceiver::class.java).apply {
                action = MainActivity.ACTION_FIREWALL_CONTROL
                putExtra(MainActivity.EXTRA_FIREWALL_ENABLED, newState)
                putExtra(FirewallControlReceiver.EXTRA_HISTORY_SOURCE, com.lu.sfwall.utils.FirewallHistory.Source.WIDGET.key)
            }
            context.sendBroadcast(BroadcastAuth.attachToken(context, toggleIntent))
        } else if (intent.action == MainActivity.ACTION_FIREWALL_STATE_CHANGED) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = android.content.ComponentName(context, FirewallWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            onUpdate(context, appWidgetManager, appWidgetIds)
        }
    }

    companion object {
        private const val TAG = "FirewallWidgetProvider"
        const val ACTION_WIDGET_CLICK = "com.lu.sfwall.ACTION_WIDGET_CLICK"

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val sharedPreferences = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            updateAppWidgetOptimistic(context, appWidgetManager, appWidgetId, FirewallUtils.loadFirewallEnabled(sharedPreferences))
        }

        private fun updateAppWidgetOptimistic(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, isEnabled: Boolean) {
            val views = RemoteViews(context.packageName, R.layout.widget_firewall)
            val theme = WidgetTheme.of(context, appWidgetId)
            views.setInt(R.id.widget_layout, "setBackgroundResource", theme.backgroundRes)
            views.setImageViewResource(
                R.id.widget_icon,
                if (isEnabled) R.drawable.ic_widget_network_blocked else R.drawable.ic_widget_network_allowed
            )
            theme.applyContent(context, views, R.id.widget_icon)

            val intent = Intent(context, FirewallWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_CLICK
                putExtra(BroadcastAuth.EXTRA_TOKEN, BroadcastAuth.token(context))
            }
            val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_layout, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}