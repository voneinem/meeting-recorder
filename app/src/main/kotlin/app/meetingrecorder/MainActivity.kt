package app.meetingrecorder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.meetingrecorder.ui.AppNavigation
import app.meetingrecorder.ui.theme.MeetingRecorderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val preferences = AppPreferences(applicationContext)
        setContent {
            MeetingRecorderTheme {
                AppNavigation(preferences)
            }
        }
    }
}
