package app.meetingrecorder.ui

import android.net.Uri
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
import app.meetingrecorder.ui.playback.PlaybackScreen
import app.meetingrecorder.ui.record.RecordScreen
import app.meetingrecorder.ui.models.ModelSettingsScreen
import app.meetingrecorder.ui.settings.SettingsScreen
import app.meetingrecorder.ui.setup.ConsentScreen
import app.meetingrecorder.ui.setup.SetupScreen

object Routes {
    const val SETUP = "setup?lost={lost}&change={change}"
    const val CONSENT = "consent"
    const val RECORD = "record"
    const val LIST = "list"
    const val SETTINGS = "settings"
    const val MODELS = "models"
    const val PLAYBACK = "playback/{folder}"

    fun playback(folder: String) = "playback/${Uri.encode(folder)}"

    fun setup(lost: Boolean = false, change: Boolean = false) = "setup?lost=$lost&change=$change"
}

/**
 * The navigation graph: first-launch flow (storage folder, consent), then recording and the meeting
 * list. The storage folder is checked on every launch (FR-002); if it became unusable the user is
 * sent back to the setup.
 */
@Composable
fun AppNavigation(preferences: AppPreferences, openFolder: String? = null, onFolderOpened: () -> Unit = {}) {
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

    // A tap on a transcription notification opens that meeting (once setup and consent are done).
    LaunchedEffect(openFolder) {
        if (openFolder != null && preferences.storageTreeUri != null && preferences.consentAcknowledged) {
            navController.navigate(Routes.playback(openFolder))
            onFolderOpened()
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
                onOpenMeeting = { navController.navigate(Routes.playback(it)) },
                onNewRecording = { navController.goTo(Routes.RECORD) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenModels = { navController.navigate(Routes.MODELS) },
            )
        }
        composable(
            route = Routes.PLAYBACK,
            arguments = listOf(navArgument("folder") { type = NavType.StringType }),
        ) {
            PlaybackScreen(onBack = { navController.popBackStack() }, onOpenModels = { navController.navigate(Routes.MODELS) })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                preferences = preferences,
                onChangeFolder = { navController.navigate(Routes.setup(change = true)) },
                onOpenModels = { navController.navigate(Routes.MODELS) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.MODELS) {
            ModelSettingsScreen(onBack = { navController.popBackStack() })
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
