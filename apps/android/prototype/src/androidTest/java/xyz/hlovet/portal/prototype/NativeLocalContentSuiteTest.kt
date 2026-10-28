package xyz.hlovet.portal.prototype

import org.junit.runner.RunWith
import org.junit.runners.Suite

/** 按本地会话依赖顺序运行文章内容专项，确保后续测试复用主会话创建的读者夹具。 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    NativeLocalMainSessionTest::class,
    NativeLocalCollectionsTest::class,
    NativeLocalRichEditorTest::class,
)
class NativeLocalContentSuiteTest
