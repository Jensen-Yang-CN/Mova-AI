package com.mova.sceneai.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mova.sceneai.MovaApp
import com.mova.sceneai.R
import com.mova.sceneai.core.ThemeMode
import com.mova.sceneai.ui.about.AboutScreen
import com.mova.sceneai.ui.chat.ChatScreen
import com.mova.sceneai.ui.components.LocalSnackbar
import com.mova.sceneai.ui.food.FoodScreen
import com.mova.sceneai.ui.history.HistoryScreen
import com.mova.sceneai.ui.history.ResultDetailScreen
import com.mova.sceneai.ui.home.HomeScreen
import com.mova.sceneai.ui.nav.MainTab
import com.mova.sceneai.ui.nav.Routes
import com.mova.sceneai.ui.onboarding.OnboardingScreen
import com.mova.sceneai.ui.reading.ReadingScreen
import com.mova.sceneai.ui.settings.SettingsScreen
import com.mova.sceneai.ui.tech.TechPanelScreen
import com.mova.sceneai.ui.theme.MovaTheme

/**
 * 应用根组件：主题 → 全局 Snackbar → 底部导航 → NavHost。
 *
 * 关于 inset：外层 Scaffold 关闭自身的 contentWindowInsets，
 * 由每个页面的 TopAppBar 自行处理状态栏，底部导航自行处理导航栏，
 * 这样不会出现"状态栏高度被计算两次"的经典问题。
 */
@Composable
fun MovaRoot() {
    val container = MovaApp.instance.container

    val settingsOrNull by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val settings = settingsOrNull

    LaunchedEffect(Unit) { container.repository.loadLocalData() }

    if (settings == null) {
        MovaTheme { SplashScreen() }
        return
    }

    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    MovaTheme(darkTheme = dark) {
        val navController = rememberNavController()
        val snackbarHostState = remember { SnackbarHostState() }
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentTab = MainTab.of(backStackEntry?.destination?.route)

        CompositionLocalProvider(LocalSnackbar provides snackbarHostState) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    if (currentTab != null) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                            MainTab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = tab == currentTab,
                                    onClick = {
                                        if (tab != currentTab) {
                                            navController.navigate(tab.route) {
                                                popUpTo(Routes.HOME) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                MovaNavHost(
                    navController = navController,
                    modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
                    startDestination = if (settings.onboardingDone) Routes.HOME else Routes.ONBOARDING,
                )
            }
        }
    }
}

@Composable
private fun MovaNavHost(
    navController: NavHostController,
    modifier: Modifier,
    startDestination: String,
) {
    NavHost(navController = navController, startDestination = startDestination, modifier = modifier) {

        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onFinished = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                onOpenScene = { scene -> navController.navigate(scene) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenTech = { navController.navigate(Routes.TECH) },
            )
        }

        composable(Routes.FOOD) {
            FoodScreen(
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.READING) {
            ReadingScreen(
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.CHAT) {
            ChatScreen(
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.HISTORY) {
            HistoryScreen(
                onOpenEntry = { id -> navController.navigate(Routes.resultDetail(id)) },
                onOpenFood = { navController.navigate(Routes.FOOD) },
            )
        }

        composable(
            route = Routes.RESULT_DETAIL,
            arguments = listOf(navArgument("entryId") { type = NavType.StringType }),
        ) { entry ->
            ResultDetailScreen(
                entryId = entry.arguments?.getString("entryId").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.TECH) {
            TechPanelScreen(onOpenSettings = { navController.navigate(Routes.SETTINGS) })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onOpenAbout = { navController.navigate("about") },
                onRerunOnboarding = {
                    navController.navigate(Routes.ONBOARDING) {
                        popUpTo(Routes.HOME) { inclusive = false }
                    }
                },
            )
        }

        composable("about") {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}

/** 设置加载期间的轻量占位，避免首帧闪过错误的主题或页面。 */
@Composable
private fun SplashScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("✨", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(12.dp))
            Text("Mova-AI", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.app_name_desc),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.app_slogan),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(modifier = Modifier.height(20.dp))
        }
    }
}
