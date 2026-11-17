package com.lu.sfwall.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lu.sfwall.R
import com.lu.sfwall.utils.FirewallHistory

/**
 * One row of the firewall run history: the event, its time and source, plus
 * how long that session ran for close events.
 */
class FirewallHistoryAdapter : ListAdapter<FirewallHistoryAdapter.Row, FirewallHistoryAdapter.HistoryViewHolder>(DiffCallback()) {

    data class Row(val event: FirewallHistory.Event, val sessionDurationMs: Long?)

    class DiffCallback : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(oldItem: Row, newItem: Row) =
            oldItem.event.timestampMs == newItem.event.timestampMs &&
                oldItem.event.type == newItem.event.type

        override fun areContentsTheSame(oldItem: Row, newItem: Row) = oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_firewall_history, parent, false)
        return HistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class HistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.historyRowTitle)
        private val subtitle: TextView = itemView.findViewById(R.id.historyRowSubtitle)

        fun bind(row: Row) {
            val ctx = itemView.context
            val typeLabel = ctx.getString(
                when (row.event.type) {
                    FirewallHistory.Type.ENABLED -> R.string.history_event_enabled
                    FirewallHistory.Type.DISABLED -> R.string.history_event_disabled
                    FirewallHistory.Type.CLEARED_AFTER_REBOOT -> R.string.history_event_cleared
                }
            )
            title.text = if (row.sessionDurationMs != null) {
                "$typeLabel · ${ctx.getString(R.string.history_session_duration, FirewallHistoryActivity.formatDuration(ctx, row.sessionDurationMs))}"
            } else {
                typeLabel
            }
            val time = FirewallHistoryActivity.formatTime(row.event.timestampMs)
            val sourceLabel = ctx.getString(
                when (row.event.source) {
                    FirewallHistory.Source.MANUAL -> R.string.history_source_manual
                    FirewallHistory.Source.WIDGET -> R.string.history_source_widget
                    FirewallHistory.Source.AUTOMATION -> R.string.history_source_automation
                    FirewallHistory.Source.PROFILE -> R.string.history_source_profile
                    FirewallHistory.Source.TILE -> R.string.history_source_tile
                    FirewallHistory.Source.FLOATING_BUTTON -> R.string.history_source_floating_button
                    FirewallHistory.Source.SHIZUKU_AUTO -> R.string.history_source_shizuku_auto
                    FirewallHistory.Source.BOOT_AUTO -> R.string.history_source_boot_auto
                    FirewallHistory.Source.SYSTEM_REBOOT -> R.string.history_source_system_reboot
                    FirewallHistory.Source.SYSTEM -> R.string.history_source_system
                }
            )
            subtitle.text = "$time · $sourceLabel"
        }
    }
}
