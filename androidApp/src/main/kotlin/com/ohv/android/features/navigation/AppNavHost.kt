package com.ohv.android.features.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ohv.android.features.album.AlbumDetailScreen
import com.ohv.android.features.home.HomeScreen
import com.ohv.android.features.onboarding.CreatorSelectScreen
import com.ohv.android.features.onboarding.LoginWebViewScreen
import com.ohv.android.features.onboarding.SyncProgressScreen
import com.ohv.android.features.onboarding.WelcomeScreen
import com.ohv.android.features.player.PlayerScreen
import com.ohv.android.features.settings.SettingsScreen
import com.ohv.android.platform.AudioPlayerManager

sealed class Screen(val route: String) {
    // Onboarding
    object Welcome : Screen("welcome")
    object Login : Screen("login")
    object CreatorSelect : Screen("creator_select")

    /** SyncProgress 接收逗号分隔的 creatorIds 参数 */
    object SyncProgress : Screen("sync_progress/{creatorIds}") {
        fun createRoute(creatorIds: List<String>) =
            "sync_progress/${creatorIds.joinToString(",")}"
    }

    // Main
    object Home : Screen("home")
    object Album : Screen("album/{albumId}") {
        fun createRoute(albumId: String) = "album/$albumId"
    }
    object Creator : Screen("creator/{creatorId}") {
        fun createRoute(creatorId: String) = "creator/$creatorId"
    }
    object Settings : Screen("settings")
    object Player : Screen("player")
}

@Composable
fun AppNavHost(
    isLoggedIn: Boolean,
    onLoginComplete: () -> Unit,
    onLogout: () -> Unit
) {
    val navController = rememberNavController()
    val startDestination = if (isLoggedIn) Screen.Home.route else Screen.Welcome.route

    NavHost(navController = navController, startDestination = startDestination) {

        // ── Onboarding ────────────────────────────────────────────────────────

        composable(Screen.Welcome.route) {
            WelcomeScreen(
                onStartLogin = { navController.navigate(Screen.Login.route) }
            )
        }

        composable(Screen.Login.route) {
            LoginWebViewScreen(
                onLoginSuccess = {
                    // 登录成功 → 进入创作者选择
                    navController.navigate(Screen.CreatorSelect.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.CreatorSelect.route) {
            CreatorSelectScreen(
                onConfirm = { selectedIds ->
                    navController.navigate(Screen.SyncProgress.createRoute(selectedIds)) {
                        popUpTo(Screen.CreatorSelect.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.SyncProgress.route,
            arguments = listOf(navArgument("creatorIds") { type = NavType.StringType })
        ) { backStackEntry ->
            val raw = backStackEntry.arguments?.getString("creatorIds") ?: ""
            val creatorIds = if (raw.isBlank()) emptyList() else raw.split(",")

            SyncProgressScreen(
                selectedCreatorIds = creatorIds,
                onComplete = {
                    // 同步完成 → 进入首页，清空 Onboarding 栈
                    onLoginComplete()
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Welcome.route) { inclusive = true }
                    }
                }
            )
        }

        // ── Main ──────────────────────────────────────────────────────────────

        composable(Screen.Home.route) {
            HomeScreen(
                onAlbumClick = { albumId ->
                    navController.navigate(Screen.Album.createRoute(albumId))
                },
                onCreatorClick = { creatorId ->
                    navController.navigate(Screen.Creator.createRoute(creatorId))
                },
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
                onPlayerClick = { navController.navigate(Screen.Player.route) }
            )
        }

        composable(
            route = Screen.Album.route,
            arguments = listOf(navArgument("albumId") { type = NavType.StringType })
        ) { backStackEntry ->
            val albumId = backStackEntry.arguments?.getString("albumId") ?: return@composable
            AlbumDetailScreen(
                albumId = albumId,
                onBack = { navController.popBackStack() },
                onPlayerClick = { navController.navigate(Screen.Player.route) }
            )
        }

        composable(
            route = Screen.Creator.route,
            arguments = listOf(navArgument("creatorId") { type = NavType.StringType })
        ) {
            // TODO: CreatorScreen（创作者详情页，当前用 AlbumDetail 代替）
            // 暂时 pop 回去，避免空白页
            navController.popBackStack()
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onLogout = {
                    onLogout()
                    navController.navigate(Screen.Welcome.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onResync = {
                    // 重新同步：走 CreatorSelect → SyncProgress 流程
                    navController.navigate(Screen.CreatorSelect.route)
                },
                onRelogin = {
                    navController.navigate(Screen.Login.route)
                }
            )
        }

        composable(Screen.Player.route) {
            PlayerScreen(
                onDismiss = { navController.popBackStack() },
                onShowPlaylist = {
                    navController.popBackStack(Screen.Home.route, inclusive = false)
                    AudioPlayerManager.shared.requestScrollToPlaylist()
                }
            )
        }
    }
}
