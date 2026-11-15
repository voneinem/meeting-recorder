package app.meetingrecorder.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.meetingrecorder.AppPreferences
import app.meetingrecorder.storage.StorageGuard
import app.meetingrecorder.storage.StorageStatus
import app.meetingrecorder.ui.list.MeetingListScreen
import app.meetingrecorder.ui.record.RecordScreen
import app.meetingrecorder.ui.settings.SettingsScreen
import app.meetingrecorder.ui.setup.ConsentScreen
import app.meetingrecorder.ui.setup.SetupScreen

object Routes {
    const val SETUP = "setup?lost={lost}&change={change}"
    const val CONSENT = "consent"
    const val RECORD = "record"
    const val LIST = "list"
    const val SETTINGS = "settings"

    fun setup(lost: Boolean = false, change: Boolean = false) = "setup?lost=$lost&change=$change"
}

/**
 * The navigation graph: first-launch flow (storage folder, consent), then recording and the meeting
 * list. The storage folder is checked on every launch (FR-002); if it became unusable the user is
 * sent back to the setup.
 */
@Composable
fun AppNavigation(preferences: AppPreferences) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val start = remember {
        when {
            preferences.storageTreeUri == null -> Routes.setup()
            !preferences.consentAcknowledged -> Routes.CONSENT
            else -> Routes.RECORD
        }
    }

    LaunchedEffect(Unit) {
        if (preferences.storageTreeUri != null) {
            val status = StorageGuard(context, preferences).check()
            if (status !is StorageStatus.Ok) navController.navigate(Routes.setup(lost = true))
        }
    }

    NavHost(navController = navController, startDestination = start) {
        composable(
            route = Routes.SETUP,
            arguments = listOf(
                navArgument("lost") { type = NavType.BoolType; defaultValue = false },
                navArgument("change") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val change = entry.arguments?.getBoolean("change") ?: false
            SetupScreen(
                folderWasLost = entry.arguments?.getBoolean("lost") ?: false,
                onDone = {
                    if (change || preferences.consentAcknowledged) {
                        navController.goTo(Routes.RECORD)
                    } else {
                        navController.goTo(Routes.CONSENT)
                    }
                },
            )
        }
        composable(Routes.CONSENT) {
            ConsentScreen(onAcknowledged = {
                preferences.consentAcknowledged = true
                navController.goTo(Routes.RECORD)
            })
        }
        composable(Routes.RECORD) {
            RecordScreen(
                onOpenList = { navController.navigate(Routes.LIST) },
                onStorageProblem = { navController.navigate(Routes.setup(lost = true)) },
                onFinished = { navController.navigate(Routes.LIST) },
            )
        }
        composable(Routes.LIST) {
            MeetingListScreen(
                onNewRecording = { navController.goTo(Routes.RECORD) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                preferences = preferences,
                onChangeFolder = { navController.navigate(Routes.setup(change = true)) },
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/** Navigates to [route] and clears everything above it, so Back leaves the app instead of looping. */
private fun NavHostController.goTo(route: String) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
