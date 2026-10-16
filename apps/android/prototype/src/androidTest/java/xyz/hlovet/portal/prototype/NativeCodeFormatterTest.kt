package xyz.hlovet.portal.prototype

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeCodeFormatterTest {
    private suspend fun <T> withActivity(block: suspend (MainActivity) -> T): T {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var activity: MainActivity? = null
            scenario.onActivity { activity = it }
            return block(requireNotNull(activity))
        }
    }

    @Test
    fun bundledFormatterFormatsRepresentativeLanguagesInAndroidWebView() = runBlocking {
        withActivity { activity ->
            val cases = listOf(
                "json" to "{\"enabled\":true,\"items\":[1,2]}",
                "html" to "<main><p>Hello</p></main>",
                "sql" to "select * from users where id=1;",
            )

            cases.forEach { (language, source) ->
                val result = NativeCodeFormatter.format(activity, source, language)
                assertTrue("$language should format successfully: $result", result is NativeCodeFormatResult.Formatted)
                assertTrue((result as NativeCodeFormatResult.Formatted).code.isNotBlank())
            }
        }
    }

    @Test
    fun bundledFormatterReportsTheSameFailuresAsTheOfflineContract() = runBlocking {
        withActivity { activity ->
            assertEquals(
                NativeCodeFormatResult.Failure.InvalidSyntax,
                NativeCodeFormatter.format(activity, "{invalid", "json"),
            )
            assertEquals(
                NativeCodeFormatResult.Failure.UnsupportedLanguage,
                NativeCodeFormatter.format(activity, "print('hello')", "python"),
            )
            assertEquals(
                NativeCodeFormatResult.Failure.TooLarge,
                NativeCodeFormatter.format(activity, "x".repeat(NativeCodeFormatter.maxSourceLength + 1), "json"),
            )
        }
    }
}
