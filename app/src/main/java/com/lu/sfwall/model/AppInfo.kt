package com.lu.sfwall.model

import com.lu.sfwall.utils.AppIds
import com.lu.sfwall.utils.AppKey

data class AppInfo(
    val appName: String,
    val packageName: String,
    val isSelected: Boolean = false,
    val isSystem: Boolean = false,
    val isFavorite: Boolean = false,
    val installTime: Long = 0,
    val appFirewallMode: Int = 0,
    val userId: Int = 0,
    val uid: Int = -1,
    val hasInternet: Boolean = true
) {

    val isSelectable: Boolean get() = hasInternet && AppIds.isBlockable(uid)

    val key: String get() = AppKey.of(userId, packageName)
}
