package com.mova.sceneai.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

object Routes {
    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val FOOD = "food"
    const val READING = "reading"
    const val CHAT = "chat"
    const val HISTORY = "history"
    const val TECH = "tech"
    const val SETTINGS = "settings"

    /** 记录详情：用参数定位到具体一条历史 */
    const val RESULT_DETAIL = "result/{entryId}"
    fun resultDetail(entryId: String) = "result/$entryId"
}

/**
 * 底部导航的四个主页。
 * 场景页（做饭/阅读/聊天）刻意**不**进底部导航 —— 它们是从首页进入的任务流，
 * 底部导航只放"长期停留"的页面，避免把导航变成功能列表。
 */
enum class MainTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    HOME(Routes.HOME, "首页", Icons.Filled.Home),
    HISTORY(Routes.HISTORY, "记录", Icons.Filled.History),
    TECH(Routes.TECH, "技术", Icons.Filled.Insights),
    SETTINGS(Routes.SETTINGS, "设置", Icons.Filled.Settings);

    companion object {
        val routes: Set<String> = entries.map { it.route }.toSet()
        fun of(route: String?): MainTab? = entries.firstOrNull { it.route == route }
    }
}
