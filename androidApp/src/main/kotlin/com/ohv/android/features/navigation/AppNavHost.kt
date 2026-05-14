package com.ohv.android.features.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ohv.android.features.album.AlbumDetailScreen
import com.ohv.android.features.creator.AllCreatorsScreen
import com.ohv.android.features.creator.CreatorScreen
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
    object AllCreators : Screen("all_creators")
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
                onAllCreatorsClick = {
                    navController.navigate(Screen.AllCreators.route)
                },
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
                onPlayerClick = { navController.navigate(Screen.Player.route) }
            )
        }

        composable(
            route = Screen.Album.route,
            arguments = listOf(navArgument("albumId") { type = NavType.StringType }),
            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, animationSpec = tween(300)) },
            exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, animationSpec = tween(300)) }
        ) { backStackEntry ->
            val albumId = backStackEntry.arguments?.getString("albumId") ?: return@composable
            AlbumDetailScreen(
                albumId = albumId,
                onBack = { navController.popBackStack() },
                onPlayerClick = { navController.navigate(Screen.Player.route) },
                onShowPlaylist = {
                    navController.popBackStack(Screen.Home.route, inclusive = false)
                    AudioPlayerManager.shared.requestScrollToPlaylist()
                }
            )
        }

        composable(
            route = Screen.Creator.route,
            arguments = listOf(navArgument("creatorId") { type = NavType.StringType }),
            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, animationSpec = tween(300)) },
            exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, animationSpec = tween(300)) }
        ) { backStackEntry ->
            val creatorId = backStackEntry.arguments?.getString("creatorId") ?: return@composable
            CreatorScreen(
                creatorId = creatorId,
                onBack = { navController.popBackStack() },
                onAlbumClick = { albumId ->
                    navController.navigate(Screen.Album.createRoute(albumId))
                }
            )
        }

        composable(
            Screen.AllCreators.route,
            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, animationSpec = tween(300)) },
            exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, animationSpec = tween(300)) }
        ) {
            AllCreatorsScreen(
                onBack = { navController.popBackStack() },
                onCreatorClick = { creatorId ->
                    navController.navigate(Screen.Creator.createRoute(creatorId))
                }
            )
        }

        composable(
            Screen.Settings.route,
            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, animationSpec = tween(300)) },
            exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, animationSpec = tween(300)) }
        ) {
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

        composable(
            Screen.Player.route,
            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Up, animationSpec = tween(350)) },
            exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Down, animationSpec = tween(300)) }
        ) {
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
