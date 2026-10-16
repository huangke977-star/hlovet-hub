package xyz.hlovet.portal.prototype

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeEditorSnapshotTest {
    @Test
    fun readsLiveContentForEveryActionIncludingEmptyBody() = runBlocking {
        val snapshot = NativeEditorSnapshot()
        var body = "<p>first</p>"
        snapshot.attach(Any()) { body }
        assertEquals(body, snapshot.capture())
        body = "<p>last typed character</p>"
        assertEquals(body, snapshot.capture())
        body = ""
        assertEquals("", snapshot.capture())
    }

    @Test
    fun unavailableEditorFailsInsteadOfUsingAnOldSnapshot() = runBlocking {
        assertTrue(runCatching { NativeEditorSnapshot().capture() }.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun releasingOldViewDoesNotDetachItsReplacement() = runBlocking {
        val snapshot = NativeEditorSnapshot()
        val old = Any()
        val replacement = Any()
        snapshot.attach(old) { "old" }
        snapshot.attach(replacement) { "new" }
        snapshot.detach(old)
        assertEquals("new", snapshot.capture())
        snapshot.detach(replacement)
        assertTrue(runCatching { snapshot.capture() }.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun resultFromAClosedEditorCannotBeUsedForAnotherEditor() = runBlocking {
        val snapshot = NativeEditorSnapshot()
        val owner = Any()
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<String>()
        snapshot.attach(owner) { started.complete(Unit); result.await() }
        val pending = async { runCatching { snapshot.capture() } }
        started.await()
        snapshot.detach(owner)
        snapshot.attach(Any()) { "replacement" }
        result.complete("old content")
        assertTrue(pending.await().exceptionOrNull() is IllegalStateException)
        assertEquals("replacement", snapshot.capture())
    }

    @Test
    fun stalledReaderFailsWithinTheBoundedWait() = runBlocking {
        val snapshot = NativeEditorSnapshot(timeoutMillis = 10)
        snapshot.attach(Any()) { awaitCancellation() }
        assertTrue(runCatching { snapshot.capture() }.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun cancellationDoesNotCompleteAnEditorAction() = runBlocking {
        val snapshot = NativeEditorSnapshot()
        val started = CompletableDeferred<Unit>()
        var actionRan = false
        snapshot.attach(Any()) { started.complete(Unit); awaitCancellation() }
        val pending = async { snapshot.capture(); actionRan = true }
        started.await()
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertEquals(false, actionRan)
    }
}
