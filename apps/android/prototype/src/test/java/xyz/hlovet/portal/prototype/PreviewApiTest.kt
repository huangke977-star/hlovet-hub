package xyz.hlovet.portal.prototype

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PreviewApiTest {
    @Test
    fun configuredBackgroundUsesTheSiteOrigin() {
        assertEquals(
            BuildConfig.PORTAL_BASE_URL.trimEnd('/') + "/images/hlovet-city-lights.jpg",
            PreviewApi.siteAbsoluteUrl("/images/hlovet-city-lights.jpg"),
        )
    }

    @Test
    fun uploadedBackgroundUsesTheApiOrigin() {
        assertEquals(
            BuildConfig.API_BASE_URL.trimEnd('/') + "/backgrounds/files/background.webp",
            PreviewApi.apiAbsoluteUrl("/backgrounds/files/background.webp"),
        )
    }

    @Test
    fun nonCustomThemeKeepsItsPaletteButReadsGlassSettings() {
        val appearance = PreviewApi.appearanceFromJson(
            JSONObject()
                .put("themeId", "cloud-blue")
                .put("customAccent", "#ff0000")
                .put("cardAlpha", 64)
                .put("glassBlur", 27)
                .put("glassTint", "#ddeeff")
                .put("glassTintAlpha", 35),
        )

        assertEquals("#0284c7", appearance.accent)
        assertEquals(64, appearance.cardAlpha)
        assertEquals(27, appearance.glassBlur)
        assertEquals("#ddeeff", appearance.glassTint)
        assertEquals(35, appearance.glassTintAlpha)
    }

    @Test
    fun customThemeReadsItsConfiguredPaletteAndGlassSettings() {
        val appearance = PreviewApi.appearanceFromJson(
            JSONObject()
                .put("themeId", "custom")
                .put("customAccent", "#102030")
                .put("customForeground", "#203040")
                .put("customMuted", "#304050")
                .put("customSurface", "#f0f1f2")
                .put("cardAlpha", 61)
                .put("glassBlur", 23)
                .put("glassTint", "#f9e0e8")
                .put("glassTintAlpha", 42),
        )

        assertEquals("#102030", appearance.accent)
        assertEquals("#203040", appearance.foreground)
        assertEquals("#304050", appearance.muted)
        assertEquals("#f0f1f2", appearance.surface)
        assertEquals(61, appearance.cardAlpha)
        assertEquals(23, appearance.glassBlur)
        assertEquals("#f9e0e8", appearance.glassTint)
        assertEquals(42, appearance.glassTintAlpha)
        assertNotEquals("#0284c7", appearance.accent)
    }
}
