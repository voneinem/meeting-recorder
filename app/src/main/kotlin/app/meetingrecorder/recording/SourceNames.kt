package app.meetingrecorder.recording

import android.content.Context
import app.meetingrecorder.R

/**
 * The name of a source as shown to the user. `meeting.json` always stores the English name; the
 * built-in microphone is translated for the screen and the notification, external devices keep the
 * product name they report.
 */
fun localizedSourceName(context: Context, name: String): String =
    if (name == SourceMapper.BUILT_IN_NAME) context.getString(R.string.source_builtin) else name
