package com.lu.sfwall.shell

import android.content.Context
import com.lu.sfwall.WorkingMode
import com.lu.sfwall.ui.MainActivity
import com.lu.sfwall.shizuku.ShizukuShellExecutor
import com.lu.sfwall.daemon.DaemonShellExecutor

object ShellExecutorProvider {
    fun forContext(context: Context): ShellExecutor {
        val prefs = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
        val modeName = prefs.getString(MainActivity.KEY_WORKING_MODE, WorkingMode.SHIZUKU.name)
        val executor = when (WorkingMode.fromName(modeName)) {
            WorkingMode.LADB -> DaemonShellExecutor(context)
            WorkingMode.ROOT -> RootShellExecutor()
            else -> ShizukuShellExecutor()
        }
        return PerUidRoutingExecutor(context, executor)
    }
}
