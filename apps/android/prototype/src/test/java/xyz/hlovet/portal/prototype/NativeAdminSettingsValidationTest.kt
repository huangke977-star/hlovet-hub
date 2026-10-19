package xyz.hlovet.portal.prototype

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 核对站点设置的文件边界和配置路径，不依赖 Android 设备或真实服务。 */
class NativeAdminSettingsValidationTest {
    /** 五项数值分别接受上下边界，防止把合法零值或范围端点当成空值。 */
    @Test
    fun numericDraftAcceptsEveryInclusiveBoundary() {
        // 每次只修改一个字段，其它值保持合法，确保每项约束均被单独覆盖。
        val valid = NativeSiteNumericDraft("50", "18", "46", "3", "8")
        listOf(
            valid.copy(cardAlpha = "38"), valid.copy(cardAlpha = "76"),
            valid.copy(glassBlur = "0"), valid.copy(glassBlur = "36"),
            valid.copy(glassTintAlpha = "0"), valid.copy(glassTintAlpha = "100"),
            valid.copy(apkRetentionCount = "1"), valid.copy(apkRetentionCount = "20"),
            valid.copy(articleImageMaxSizeMb = "1"), valid.copy(articleImageMaxSizeMb = "30"),
        ).forEach { assertEquals(null, it.validationError()) }
    }

    /** 空文本、越界、小数、负数和溢出均保留为非法草稿，不能转成另一个可保存值。 */
    @Test
    fun numericDraftRejectsRawInvalidValuesForEveryField() {
        // 各字段独立覆盖空值和上下界外值，再覆盖原先数字过滤可能吞掉的字符。
        val valid = NativeSiteNumericDraft("50", "18", "46", "3", "8")
        val invalid = listOf(
            valid.copy(cardAlpha = ""), valid.copy(cardAlpha = "37"), valid.copy(cardAlpha = "77"),
            valid.copy(glassBlur = ""), valid.copy(glassBlur = "-1"), valid.copy(glassBlur = "37"),
            valid.copy(glassTintAlpha = ""), valid.copy(glassTintAlpha = "-1"), valid.copy(glassTintAlpha = "101"),
            valid.copy(apkRetentionCount = ""), valid.copy(apkRetentionCount = "0"), valid.copy(apkRetentionCount = "21"),
            valid.copy(articleImageMaxSizeMb = ""), valid.copy(articleImageMaxSizeMb = "0"), valid.copy(articleImageMaxSizeMb = "31"),
            valid.copy(cardAlpha = "50.5"), valid.copy(apkRetentionCount = "1x"),
            valid.copy(glassBlur = "9999999999999999999999999"),
        )
        invalid.forEach { assertTrue("Unexpected valid draft: $it", it.validationError() != null) }
    }

    /** 已带 API 前缀的资源不重复添加，普通相对路径采用与 PC 相同的站点配置路径。 */
    @Test
    fun configuredSiteAssetPathsPreserveExistingApiPrefix() {
        // 同一资源的三种接口相对形式必须保存为同一个配置值。
        assertEquals("/api/site-settings/assets/7/file", nativeSettingsAssetPath("/api/site-settings/assets/7/file"))
        assertEquals("/api/site-settings/assets/7/file", nativeSettingsAssetPath("/site-settings/assets/7/file"))
        assertEquals("/api/site-settings/assets/7/file", nativeSettingsAssetPath("site-settings/assets/7/file"))
    }

    @Test
    fun backgroundLimitMatchesServerLimit() {
        assertTrue(nativeBackgroundUploadAllowed(30L * 1024L * 1024L))
        assertFalse(nativeBackgroundUploadAllowed(30L * 1024L * 1024L + 1L))
    }

    @Test
    fun emptyBackgroundFilesAreRejected() {
        assertFalse(nativeBackgroundUploadAllowed(0L))
        assertFalse(nativeBackgroundUploadAllowed(-1L))
    }

    @Test
    fun pickerUsesServerSizeLimitForEverySelectedFile() {
        val selected = (1..NATIVE_BACKGROUND_MAX_FILES).toList()
        val readIds = mutableListOf<Int>()
        val files = readNativeBackgroundUploads(selected) { id, maxBytes ->
            assertEquals(30L * 1024L * 1024L, maxBytes)
            readIds.add(id)
            NativeUploadFile("$id.png", "image/png", byteArrayOf(id.toByte()))
        }
        assertEquals(selected, readIds)
        assertEquals(selected.size, files.size)
    }

    @Test
    fun invalidBatchIsRejectedBeforeReadingAnyFileInsteadOfTruncating() {
        listOf(emptyList(), (1..NATIVE_BACKGROUND_MAX_FILES + 1).toList()).forEach { selected ->
            assertThrows(IllegalArgumentException::class.java) {
                readNativeBackgroundUploads(selected) { _, _ -> error("Must not read an invalid batch") }
            }
        }
    }
}
