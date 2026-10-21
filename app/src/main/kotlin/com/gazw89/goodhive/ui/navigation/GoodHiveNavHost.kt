package com.gazw89.goodhive.ui.navigation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import android.net.Uri
import com.gazw89.goodhive.ui.reports.*
import com.gazw89.goodhive.ui.theme.LocalReducedMotion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gazw89.goodhive.auth.AuthRepository
import com.gazw89.goodhive.ui.dashboard.DashboardScreen
import com.gazw89.goodhive.ui.detail.DeviceDetailScreen
import com.gazw89.goodhive.ui.login.LoginScreen
import com.gazw89.goodhive.ui.schedule.ScheduleScreen
import com.gazw89.goodhive.ui.settings.AboutScreen
import com.gazw89.goodhive.ui.settings.SettingsScreen
import com.gazw89.goodhive.ui.theme.GoodHiveMotion
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

private object Routes {
    const val LOGIN = "login"
    const val DASHBOARD = "dashboard"
    const val REPORTS = "reports"
    const val ROOM_REPORT = "report/{zoneId}/{kind}/{offset}"
    fun roomReport(zoneId: String, kind: ReportPeriodKind, offset: Int) = "report/${Uri.encode(zoneId)}/${kind.name}/$offset"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val DEVICE_DETAIL = "device/{zoneId}"
    const val SCHEDULE = "schedule/{zoneId}"
    fun deviceDetail(zoneId: String) = "device/${Uri.encode(zoneId)}"
    fun schedule(zoneId: String) = "schedule/${Uri.encode(zoneId)}"
}

/**
 * Material "shared axis X": both screens translate in the same direction (the direction of
 * travel), with the outgoing screen fading out fast and the incoming screen's fade delayed so
 * the two never cross-dissolve — a full-bleed screen (this app has no shared toolbar/nav rail)
 * reads much better as "one screen replacing another" than as a symmetric crossfade.
 */
private val forwardEnter = fadeIn(
    tween(GoodHiveMotion.NAV_ENTER_FADE_MS, delayMillis = GoodHiveMotion.NAV_ENTER_FADE_DELAY_MS, easing = LinearEasing),
) + slideInHorizontally(tween(GoodHiveMotion.NAV_TOTAL_MS, easing = GoodHiveMotion.EmphasizedDecelerate)) { it / 4 }
private val forwardExit = fadeOut(
    tween(GoodHiveMotion.NAV_EXIT_FADE_MS, easing = LinearEasing),
) + slideOutHorizontally(tween(GoodHiveMotion.NAV_TOTAL_MS, easing = GoodHiveMotion.EmphasizedAccelerate)) { -it / 10 }
private val backEnter = fadeIn(
    tween(GoodHiveMotion.NAV_ENTER_FADE_MS, delayMillis = GoodHiveMotion.NAV_ENTER_FADE_DELAY_MS, easing = LinearEasing),
) + slideInHorizontally(tween(GoodHiveMotion.NAV_TOTAL_MS, easing = GoodHiveMotion.EmphasizedDecelerate)) { -it / 4 }
private val backExit = fadeOut(
    tween(GoodHiveMotion.NAV_EXIT_FADE_MS, easing = LinearEasing),
) + slideOutHorizontally(tween(GoodHiveMotion.NAV_TOTAL_MS, easing = GoodHiveMotion.EmphasizedAccelerate)) { it / 10 }

@HiltViewModel
class NavStartViewModel @Inject constructor(
    authRepository: AuthRepository,
) : ViewModel() {
    /** Refresh token present → resume on dashboard; otherwise start at login. */
    val startDestination: String =
        if (authRepository.hasRefreshToken()) Routes.DASHBOARD else Routes.LOGIN
}

@Composable
fun GoodHiveNavHost(
    navController: NavHostController = rememberNavController(),
    navStartViewModel: NavStartViewModel = hiltViewModel(),
) {
    val startDestination = remember(navStartViewModel) { navStartViewModel.startDestination }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val reducedMotion = LocalReducedMotion.current
    // Keep the dashboard on the stack so its single existing refresh loop still covers Reports.
    val atRoot = route == Routes.DASHBOARD || route == Routes.REPORTS
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), bottomBar = {
        if (atRoot) NavigationBar {
            NavigationBarItem(selected = route == Routes.DASHBOARD,
                onClick = { if (route != Routes.DASHBOARD) navController.popBackStack(Routes.DASHBOARD, false) },
                icon = { Icon(Icons.Filled.Home, contentDescription = null) }, label = { Text("Rooms") })
            NavigationBarItem(selected = route == Routes.REPORTS,
                onClick = { if (route != Routes.REPORTS) navController.navigate(Routes.REPORTS) { launchSingleTop = true } },
                icon = { Icon(Icons.Filled.Assessment, contentDescription = null) }, label = { Text("Reports") })
        }
    }) { rootPadding ->
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier.padding(rootPadding).consumeWindowInsets(rootPadding),
        enterTransition = { if (reducedMotion) EnterTransition.None else forwardEnter },
        exitTransition = { if (reducedMotion) ExitTransition.None else forwardExit },
        popEnterTransition = { if (reducedMotion) EnterTransition.None else backEnter },
        popExitTransition = { if (reducedMotion) ExitTransition.None else backExit },
    ) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoggedIn = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onZoneClick = { zoneId -> navController.navigate(Routes.deviceDetail(zoneId)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onSessionExpired = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.DASHBOARD) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.REPORTS) {
            ReportsScreen(onOpenRoom = { id, kind, offset -> navController.navigate(Routes.roomReport(id, kind, offset)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) })
        }
        composable(Routes.ROOM_REPORT, arguments = listOf(
            navArgument(RoomReportViewModel.ARG_ZONE_ID) { type = NavType.StringType },
            navArgument(RoomReportViewModel.ARG_KIND) { type = NavType.StringType },
            navArgument(RoomReportViewModel.ARG_OFFSET) { type = NavType.IntType },
        )) { entry ->
            val id = entry.arguments?.getString(RoomReportViewModel.ARG_ZONE_ID).orEmpty()
            RoomReportScreen(onBack = { navController.popBackStack() },
                onOpenRoomControls = { navController.navigate(Routes.deviceDetail(id)) },
                onOpenSchedule = { navController.navigate(Routes.schedule(id)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
                onSignedOut = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable(
            Routes.DEVICE_DETAIL,
            arguments = listOf(navArgument("zoneId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val zoneId = backStackEntry.arguments?.getString("zoneId").orEmpty()
            DeviceDetailScreen(
                onBack = { navController.popBackStack() },
                onEditSchedule = { navController.navigate(Routes.schedule(zoneId)) },
                onOpenZone = { otherZoneId -> navController.navigate(Routes.deviceDetail(otherZoneId)) },
            )
        }
        composable(
            Routes.SCHEDULE,
            arguments = listOf(navArgument("zoneId") { type = NavType.StringType }),
        ) {
            ScheduleScreen(onBack = { navController.popBackStack() })
        }
    }
}
}
