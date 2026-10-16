package xyz.hlovet.portal.prototype

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeAuthApiTest {
    @Test
    fun parsesDeviceVerificationResendResponse() {
        val result = parseNativeDeviceVerificationResend(
            JSONObject("""
                {
                  "challengeToken": "rotated-challenge",
                  "emailHint": "a***@example.com",
                  "retryAfterSeconds": 45
                }
            """),
            fallbackChallengeToken = "original-challenge",
        )

        assertEquals("rotated-challenge", result.challengeToken)
        assertEquals("a***@example.com", result.emailHint)
        assertEquals(45, result.retryAfterSeconds)
    }

    @Test
    fun keepsChallengeAndUsesSafeDefaultsForIncompleteResponse() {
        val result = parseNativeDeviceVerificationResend(
            JSONObject("""{"retryAfterSeconds":0}"""),
            fallbackChallengeToken = "original-challenge",
        )

        assertEquals("original-challenge", result.challengeToken)
        assertEquals("", result.emailHint)
        assertEquals(1, result.retryAfterSeconds)
    }
}
