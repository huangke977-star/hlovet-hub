package xyz.hlovet.portal.prototype

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证文章详情评论回包不会跨文章、账号或请求代际写回。 */
class NativeArticleDetailRequestTest {
    /** 当前文章和会话仍有效时允许评论结果更新。 */
    @Test
    fun currentArticleDetailRequestCanUpdate() {
        assertTrue(isCurrentArticleDetailRequest(4, 4, "article-a", "article-a", "token-a", "token-a"))
    }

    /** 切换文章或刷新后，旧评论请求必须被丢弃。 */
    @Test
    fun staleArticleDetailRequestCannotUpdate() {
        assertFalse(isCurrentArticleDetailRequest(3, 4, "article-a", "article-b", "token-a", "token-a"))
    }

    /** 账号变化后，旧账号的文章评论结果不能显示在新会话中。 */
    @Test
    fun changedArticleDetailAccountCannotUpdate() {
        assertFalse(isCurrentArticleDetailRequest(4, 4, "article-a", "article-a", "token-a", "token-b"))
    }
}
