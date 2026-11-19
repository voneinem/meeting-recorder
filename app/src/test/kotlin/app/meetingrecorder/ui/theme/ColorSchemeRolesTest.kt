package app.meetingrecorder.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every color role of the Material schemes must be a palette token, so no role keeps Material's baseline purple
 * tones (analysis finding C2). The roles are read by reflection, so a role that a later Material version adds is
 * caught as well.
 */
class ColorSchemeRolesTest {

    /** The raw value of every `Color`-typed property of the scheme, by role name. */
    private fun roles(scheme: ColorScheme): Map<String, Long> =
        ColorScheme::class.java.methods
            .filter { it.parameterCount == 0 && it.name.startsWith("get") && it.returnType == java.lang.Long.TYPE && it.name.contains('-') }
            .associate { it.name.removePrefix("get").substringBefore('-') to it.invoke(scheme) as Long }

    private fun tokens(vararg sets: Palette.Set): Set<Long> =
        sets.flatMap { it.tokens().values }.map { Color(it).value.toLong() }.toSet()

    private fun check(name: String, scheme: ColorScheme) {
        val roles = roles(scheme)
        assertTrue(roles.size >= 48, "expected the 48 roles of Material 3 1.4, found ${roles.size}: ${roles.keys}")
        val allowed = tokens(Palette.Light, Palette.Dark)
        val strays = roles.filterValues { it !in allowed }.keys
        assertEquals(emptySet(), strays, "$name roles that are not palette tokens")
    }

    @Test
    fun `light scheme uses palette tokens only`() = check("light", LightColorScheme)

    @Test
    fun `dark scheme uses palette tokens only`() = check("dark", DarkColorScheme)

    @Test
    fun `each appearance uses its own page and text colors`() {
        assertEquals(Color(Palette.Light.paper), LightColorScheme.background)
        assertEquals(Color(Palette.Light.ink), LightColorScheme.onBackground)
        assertEquals(Color(Palette.Dark.paper), DarkColorScheme.background)
        assertEquals(Color(Palette.Dark.ink), DarkColorScheme.onBackground)
        assertEquals(Color(Palette.Light.green), LightColorScheme.primary)
        assertEquals(Color(Palette.Dark.green), DarkColorScheme.primary)
        assertEquals(Color(Palette.Light.outlineControl), LightColorScheme.outline)
        assertEquals(Color(Palette.Dark.outlineControl), DarkColorScheme.outline)
    }
}
