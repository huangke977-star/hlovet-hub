package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBackupValidationTest {
    private fun bundle(data: JSONObject) = JSONObject()
        .put("format", "hlovet-configuration")
        .put("version", 1)
        .put("generatedAt", "2026-10-08T00:00:00Z")
        .put("data", data)

    @Test
    fun previewOnlyListsPresentSettingsWithoutExposingCredentials() {
        val data = JSONObject()
            .put("siteSetting", JSONObject().put("siteName", "Test"))
            .put("securityConfiguration", JSONObject().put("smtpPasswordEncrypted", "sensitive-fixture"))
            .put("backupConfiguration", JSONObject())
            .put("aiCapabilities", JSONArray().put(JSONObject().put("capability", "embedding")))
            .put("articleTaxonomies", JSONArray().put(JSONObject().put("kind", "tag").put("name", "Test")))
        val input = bundle(data)
        val preview = previewNativeConfigurationImport(input)

        assertEquals(4, preview.sections.size)
        assertEquals("2026-10-08T00:00:00Z", preview.generatedAt)
        assertEquals(input.toString(), preview.bundle.toString())
        assertFalse(preview.sections.joinToString().contains("sensitive-fixture"))
        assertFalse(preview.sections.any { it.contains("备份策略") })
    }

    @Test
    fun previewIgnoresUnsupportedAndMalformedEntries() {
        val data = JSONObject()
            .put("users", JSONArray().put(JSONObject().put("username", "ignored")))
            .put("aiCapabilities", JSONArray().put("invalid").put(JSONObject()).put(JSONObject().put("capability", " ")).put(JSONObject().put("capability", JSONObject.NULL)).put(JSONObject().put("capability", 1)).put(JSONObject().put("capability", "ocr")))
            .put("articleTaxonomies", JSONArray().put(JSONObject().put("kind", "tag")).put(JSONObject().put("kind", JSONObject.NULL).put("name", "Test")).put(JSONObject().put("kind", "category").put("name", "Test")))

        val preview = previewNativeConfigurationImport(bundle(data))
        assertEquals(2, preview.sections.size)
        assertTrue(preview.sections.all { it.contains("1 项") })
    }

    @Test
    fun previewUsesEnglishLabelsWhenEnglishIsSelected() {
        val data = JSONObject()
            .put("aiCapabilities", JSONArray().put(JSONObject().put("capability", "embedding")))
            .put("articleTaxonomies", JSONArray().put(JSONObject().put("kind", "tag").put("name", "Test")))

        val preview = previewNativeConfigurationImport(bundle(data), "en-US")

        assertEquals(listOf("AI capability settings: 1", "Article categories and tags: 1 (merged by name)"), preview.sections)
        assertTrue(preview.sections.none { it.contains("配置") || it.contains("文章分类") })
    }

    @Test
    fun rejectsUnsupportedFormat() {
        assertThrows(IllegalArgumentException::class.java) {
            previewNativeConfigurationImport(bundle(JSONObject().put("siteSetting", JSONObject().put("siteName", "Test"))).put("format", "unknown"))
        }
    }

    @Test
    fun rejectsUnsupportedOrCoercedVersion() {
        listOf<Any>(2, "1", 1.5).forEach { version ->
            assertThrows(IllegalArgumentException::class.java) {
                previewNativeConfigurationImport(bundle(JSONObject().put("siteSetting", JSONObject().put("siteName", "Test"))).put("version", version))
            }
        }
    }

    @Test
    fun rejectsMissingOrEmptyConfiguration() {
        listOf(JSONObject().put("format", "hlovet-configuration").put("version", 1), bundle(JSONObject()), bundle(JSONObject().put("siteSetting", JSONArray()))).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { previewNativeConfigurationImport(invalid) }
        }
    }

    @Test
    fun remoteRetentionUsesServerLimitAndNeverSilentlyClamps() {
        assertEquals(3650, nativeBackupRetentionDays("3650", remote = true))
        assertEquals(365, nativeBackupRetentionDays("365", remote = false))
        assertEquals(1, nativeBackupRetentionDays("1", remote = false))
        assertThrows(IllegalArgumentException::class.java) { nativeBackupRetentionDays("366", remote = false) }
        assertThrows(IllegalArgumentException::class.java) { nativeBackupRetentionDays("3651", remote = true) }
        listOf("", "0", "-1", "1.5", "999999999999").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { nativeBackupRetentionDays(value, remote = true) }
        }
    }

    @Test
    fun retentionValidationUsesEnglishWhenEnglishIsSelected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            nativeBackupRetentionDays("366", remote = false, locale = "en-US")
        }

        assertEquals("Local retention must be an integer from 1 to 365.", error.message)
    }

    @Test
    fun parsesMediaBackupFileDirectoryAndNullableFields() {
        val page = parseNativeMediaBackupFilePage(
            JSONObject()
                .put("total", 2)
                .put("page", 1)
                .put("pageSize", 20)
                .put("pageCount", 1)
                .put("items", JSONArray()
                    .put(JSONObject()
                        .put("id", 7)
                        .put("category", "articles")
                        .put("storedName", "article-cover.webp")
                        .put("sourceType", "article")
                        .put("sourceId", "42")
                        .put("sourceLabel", "Test article")
                        .put("sizeBytes", 2048)
                        .put("contentHash", "abc123")
                        .put("fileUpdatedAt", JSONObject.NULL)
                        .put("lastSeenAt", "2026-10-11T00:00:00Z")
                        .put("lastBackedUpAt", "2026-10-11T00:01:00Z"))
                    .put(JSONObject().put("id", 8).put("storedName", "not-yet-backed-up.png"))),
        )

        assertEquals(2, page.total)
        assertEquals(1, page.pageCount)
        assertEquals("article-cover.webp", page.items.first().storedName)
        assertEquals("articles", page.items.first().category)
        assertEquals("42", page.items.first().sourceId)
        assertEquals(2048, page.items.first().sizeBytes)
        assertEquals(null, page.items.first().fileUpdatedAt)
        assertEquals("2026-10-11T00:01:00Z", page.items.first().lastBackedUpAt)
        assertEquals(8, page.items.last().id)
        assertEquals(null, page.items.last().lastBackedUpAt)
    }
}
