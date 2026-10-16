package xyz.hlovet.portal.prototype

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeOfflineOwnerTest {
    @Test
    fun loggedInOwnerUsesImmutableUserId() {
        val session = session(userId = 42, username = "old-name")

        assertEquals("user:42", NativeOfflineOwner.forSession(session))
    }

    @Test
    fun renamedAccountKeepsTheSameOwnerKey() {
        assertEquals(
            NativeOfflineOwner.forSession(session(userId = 42, username = "old-name")),
            NativeOfflineOwner.forSession(session(userId = 42, username = "new-name")),
        )
    }

    @Test
    fun missingUserIdFallsBackToLegacyUsername() {
        val session = session(userId = 0, username = "legacy-name")

        assertEquals("legacy-name", NativeOfflineOwner.forSession(session))
    }

    @Test
    fun loggedOutOwnerIsPublic() {
        assertEquals("public", NativeOfflineOwner.forSession(null))
    }

    private fun session(userId: Int, username: String) = NativeSession(
        accessToken = "access",
        refreshToken = "refresh",
        userId = userId,
        username = username,
        nickname = username,
        appearance = PreviewAppearance(
            themeId = "cloud-blue",
            accent = "#0284c7",
            foreground = "#1f2937",
            muted = "#52616f",
            surface = "#ffffff",
            cardAlpha = 50,
            glassBlur = 18,
            glassTint = "#fff3f6",
            glassTintAlpha = 0,
        ),
    )
}
