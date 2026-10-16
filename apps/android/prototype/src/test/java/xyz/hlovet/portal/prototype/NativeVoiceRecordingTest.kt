package xyz.hlovet.portal.prototype

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeVoiceRecordingTest {
    @Test
    fun recordingStopsAtPcLimit() {
        assertFalse(nativeVoiceRecordingLimitReached(NATIVE_VOICE_RECORDING_LIMIT_SECONDS - 1))
        assertTrue(nativeVoiceRecordingLimitReached(NATIVE_VOICE_RECORDING_LIMIT_SECONDS))
        assertTrue(nativeVoiceRecordingLimitReached(NATIVE_VOICE_RECORDING_LIMIT_SECONDS + 1))
    }
}
