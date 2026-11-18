package app.meetingrecorder

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.meetingrecorder.transcription.TranscriptionNotification
import app.meetingrecorder.ui.AppNavigation
import app.meetingrecorder.ui.theme.MeetingRecorderTheme

class MainActivity : ComponentActivity() {
    /** The meeting a notification asked to open; cleared once the navigation has used it. */
    private var openFolder by mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFolder = intent.getStringExtra(TranscriptionNotification.EXTRA_FOLDER)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openFolder = intent?.getStringExtra(TranscriptionNotification.EXTRA_FOLDER)
        enableEdgeToEdge()
        val preferences = AppPreferences(applicationContext)
        setContent {
            MeetingRecorderTheme {
                // The surface supplies the background and the matching text colour for every screen.
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavigation(preferences, openFolder = openFolder, onFolderOpened = { openFolder = null })
                }
            }
        }
    }
}
