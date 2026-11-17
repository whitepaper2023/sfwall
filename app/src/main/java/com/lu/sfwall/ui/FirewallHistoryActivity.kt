package com.lu.sfwall.ui

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lu.sfwall.R
import com.lu.sfwall.adapters.FirewallHistoryAdapter
import com.lu.sfwall.utils.FirewallHistory
import com.lu.sfwall.utils.FirewallUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Firewall run history: current state, how long the current session has
 * been running, and the list of start/stop events with their source.
 */
class FirewallHistoryActivity : BaseActivity() {

    companion object {
        fun formatTime(timestampMs: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestampMs))

        fun formatDuration(context: Context, durationMs: Long): String {
            val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
            val days = totalSeconds / 86400
            val hours = (totalSeconds % 86400) / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            val d = context.getString(R.string.history_unit_day)
            val h = context.getString(R.string.history_unit_hour)
            val m = context.getString(R.string.history_unit_minute)
            val s = context.getString(R.string.history_unit_second)
            return when {
                days > 0 -> "$days$d$hours$h$minutes$m"
                hours > 0 -> "$hours$h$minutes$m"
                minutes > 0 -> "$minutes$m$seconds$s"
                else -> "$seconds$s"
            }
        }
    }

    private lateinit var statusText: TextView
    private lateinit var runningForText: TextView
    private lateinit var emptyText: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var clearButton: MaterialButton
    private val adapter = FirewallHistoryAdapter()

    private var lastEnabledAtMs: Long? = null
    private val ticker = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            updateRunningFor()
            ticker.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_firewall_history)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setupSettingsChrome(R.id.historyRoot, toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        statusText = findViewById(R.id.historyStatusText)
        runningForText = findViewById(R.id.historyRunningForText)
        emptyText = findViewById(R.id.historyEmptyText)
        recyclerView = findViewById(R.id.historyRecyclerView)
        clearButton = findViewById(R.id.btnClearHistory)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        clearButton.setOnClickListener { showClearConfirmation() }
    }

    override fun onResume() {
        super.onResume()
        reload()
        ticker.post(tickRunnable)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tickRunnable)
    }

    private fun reload() {
        lifecycleScope.launch {
            val events = withContext(Dispatchers.IO) { FirewallHistory.load(this@FirewallHistoryActivity) }
            val prefs = getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
            val running = FirewallUtils.loadFirewallEnabled(prefs)
            // Pair each close event with the most recent open one to get the
            // session length; the header uses the last open event, if any.
            var openSince: Long? = null
            val rows = ArrayList<FirewallHistoryAdapter.Row>(events.size)
            for (event in events) {
                when (event.type) {
                    FirewallHistory.Type.ENABLED -> {
                        rows += FirewallHistoryAdapter.Row(event, null)
                        openSince = event.timestampMs
                    }
                    FirewallHistory.Type.DISABLED, FirewallHistory.Type.CLEARED_AFTER_REBOOT -> {
                        val duration = openSince?.let { event.timestampMs - it }
                        rows += FirewallHistoryAdapter.Row(event, duration)
                        openSince = null
                    }
                }
            }
            // If the firewall is on but the history has no open event (for
            // example it was enabled before this version was installed), fall
            // back to the persisted enable time.
            val savedElapsed = prefs.getLong(MainActivity.KEY_FIREWALL_SAVED_ELAPSED, -1L)
            lastEnabledAtMs = if (running) {
                openSince ?: if (savedElapsed > 0L) {
                    System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - savedElapsed)
                } else {
                    null
                }
            } else {
                null
            }
            statusText.text = getString(
                if (running) R.string.history_status_running else R.string.history_status_stopped
            )
            updateRunningFor()
            val displayRows = rows.asReversed()
            adapter.submitList(displayRows)
            emptyText.visibility = if (displayRows.isEmpty()) View.VISIBLE else View.GONE
            recyclerView.visibility = if (displayRows.isEmpty()) View.GONE else View.VISIBLE
            clearButton.isEnabled = displayRows.isNotEmpty()
        }
    }

    private fun updateRunningFor() {
        val since = lastEnabledAtMs
        if (since == null) {
            runningForText.visibility = View.GONE
            return
        }
        runningForText.visibility = View.VISIBLE
        runningForText.text = getString(
            R.string.history_running_for,
            formatDuration(this, System.currentTimeMillis() - since)
        )
    }

    private fun showClearConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.history_clear_title))
            .setMessage(getString(R.string.history_clear_message))
            .setPositiveButton(getString(R.string.history_clear_confirm)) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { FirewallHistory.clear(this@FirewallHistoryActivity) }
                    reload()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
}
