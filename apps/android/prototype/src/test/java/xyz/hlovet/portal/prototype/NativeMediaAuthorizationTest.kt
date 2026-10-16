package xyz.hlovet.portal.prototype

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMediaAuthorizationTest {
    @Test
    fun onlyTheApiOriginMayReceiveAccountCredentials() {
        val api = "https://portal.example/api"
        assertTrue(nativeMediaMayReceiveToken("https://portal.example/api/articles/attachments/1/download", api))
        assertTrue(nativeMediaMayReceiveToken("https://PORTAL.example:443/images/photo.jpg", api))
        assertFalse(nativeMediaMayReceiveToken("https://images.example/photo.jpg", api))
        assertFalse(nativeMediaMayReceiveToken("https://portal.example.evil.test/photo.jpg", api))
        assertFalse(nativeMediaMayReceiveToken("https://portal.example:8443/photo.jpg", api))
        assertFalse(nativeMediaMayReceiveToken("http://portal.example/photo.jpg", api))
        assertFalse(nativeMediaMayReceiveToken("https://portal.example@evil.test/photo.jpg", api))
        assertFalse(nativeMediaMayReceiveToken("not a URL", api))
    }

    @Test
    fun localPreviewUsesItsExactOriginAndPort() {
        val api = "http://10.0.2.2:3001"
        assertTrue(nativeMediaMayReceiveToken("http://10.0.2.2:3001/articles/attachments/1/download", api))
        assertFalse(nativeMediaMayReceiveToken("http://10.0.2.2:3000/photo.jpg", api))
        assertFalse(nativeMediaMayReceiveToken("https://10.0.2.2:3001/photo.jpg", api))
    }
}
