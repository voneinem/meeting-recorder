package app.meetingrecorder.ui.theme

import kotlin.math.pow

/**
 * The colors of the app as plain ARGB values, in a light and a dark set. This is the only file that holds hex
 * values (the two window colors in the `colors.xml` files of `res/values` and `res/values-night` are copies
 * that a unit test keeps in step).
 * It uses no Compose or Android types, so a unit test can load it and check the contrast on the JVM.
 *
 * Tokens of the reference website (design values read on 2026-11-18 from its stylesheet): `paper`, `surface`,
 * `surfaceQuiet`, `ink`, `inkSoft`, `muted`, `line`, `green`, `greenStrong`, `mint`, `blue`, `blueSoft`, `amber`,
 * `amberSoft` and `rose`. Derived by this app because the website has no value for them or its value is too
 * faint for the purpose: `outlineControl` (the published `line` is only a divider and stays below 3:1),
 * `onGreen`, `onRose`, `roseSoft` and `onRoseSoft`.
 */
object Palette {

    /** One appearance. The names are roles, not colors, so the same code reads both sets. */
    class Set(
        val paper: Int,
        val surface: Int,
        val surfaceQuiet: Int,
        val ink: Int,
        val inkSoft: Int,
        val muted: Int,
        val line: Int,
        val outlineControl: Int,
        val green: Int,
        val onGreen: Int,
        val greenStrong: Int,
        val mint: Int,
        val blue: Int,
        val blueSoft: Int,
        val amber: Int,
        val amberSoft: Int,
        val rose: Int,
        val onRose: Int,
        val roseSoft: Int,
        val onRoseSoft: Int,
    ) {
        /** Every token by name, for tests and tools. */
        fun tokens(): Map<String, Int> = mapOf(
            "paper" to paper,
            "surface" to surface,
            "surfaceQuiet" to surfaceQuiet,
            "ink" to ink,
            "inkSoft" to inkSoft,
            "muted" to muted,
            "line" to line,
            "outlineControl" to outlineControl,
            "green" to green,
            "onGreen" to onGreen,
            "greenStrong" to greenStrong,
            "mint" to mint,
            "blue" to blue,
            "blueSoft" to blueSoft,
            "amber" to amber,
            "amberSoft" to amberSoft,
            "rose" to rose,
            "onRose" to onRose,
            "roseSoft" to roseSoft,
            "onRoseSoft" to onRoseSoft,
        )
    }

    val Light = Set(
        paper = 0xFFFBFCF8.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        surfaceQuiet = 0xFFF2F6F1.toInt(),
        ink = 0xFF102019.toInt(),
        inkSoft = 0xFF3F4D45.toInt(),
        muted = 0xFF5B685F.toInt(),
        line = 0xFFDCE5DC.toInt(),
        outlineControl = 0xFF7A8A7F.toInt(),
        green = 0xFF216E39.toInt(),
        onGreen = 0xFFFFFFFF.toInt(),
        greenStrong = 0xFF14552A.toInt(),
        mint = 0xFFDFF5E4.toInt(),
        blue = 0xFF245F92.toInt(),
        blueSoft = 0xFFE6F0F8.toInt(),
        amber = 0xFF8F4B08.toInt(),
        amberSoft = 0xFFFBECD8.toInt(),
        rose = 0xFFA94442.toInt(),
        onRose = 0xFFFFFFFF.toInt(),
        roseSoft = 0xFFF7E3E2.toInt(),
        onRoseSoft = 0xFF7A2E2C.toInt(),
    )

    val Dark = Set(
        paper = 0xFF0B130F.toInt(),
        surface = 0xFF121D17.toInt(),
        surfaceQuiet = 0xFF18251E.toInt(),
        ink = 0xFFEEF6EE.toInt(),
        inkSoft = 0xFFC8D6CB.toInt(),
        muted = 0xFF96AA9B.toInt(),
        line = 0xFF2B3C31.toInt(),
        outlineControl = 0xFF6B8172.toInt(),
        green = 0xFF78D58D.toInt(),
        onGreen = 0xFF0B130F.toInt(),
        greenStrong = 0xFF9DE8AD.toInt(),
        mint = 0xFF173D25.toInt(),
        blue = 0xFF8BBCE8.toInt(),
        blueSoft = 0xFF142B3C.toInt(),
        amber = 0xFFF0B05F.toInt(),
        amberSoft = 0xFF392716.toInt(),
        rose = 0xFFEF9A96.toInt(),
        onRose = 0xFF0B130F.toInt(),
        roseSoft = 0xFF3A1F1E.toInt(),
        onRoseSoft = 0xFFF7C6C4.toInt(),
    )

    /** WCAG 2.x contrast ratio of two opaque colors, from 1.0 (equal) to 21.0 (black on white). */
    fun contrastRatio(foreground: Int, background: Int): Double {
        val a = luminance(foreground)
        val b = luminance(background)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
