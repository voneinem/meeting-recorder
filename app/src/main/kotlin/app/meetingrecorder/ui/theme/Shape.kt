package app.meetingrecorder.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Modest rounding (FR-007). `extraSmall` is 8 dp as well because Material's outlined text field reads that slot.
 * Material's buttons and chips do not read this scale: the shared button wrappers and the status chip set their
 * shapes explicitly (research R5).
 */
internal val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp),
)
