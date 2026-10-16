package xyz.hlovet.portal.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeOperationsValidationTest {
    @Test fun acceptsIntegerBoundaries() {
        assertEquals(1, nativeOperationsNumber("1", "Concurrency", 1, 50))
        assertEquals(50, nativeOperationsNumber("50", "Concurrency", 1, 50))
        assertEquals(3650, nativeOperationsNumber("3650", "Retention", 7, 3650))
    }

    @Test fun rejectsInvalidValuesWithoutSubstitutingDefaults() {
        listOf("", "0", "51", " 2", "2.0", "-1", "999999999999999").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { nativeOperationsNumber(value, "Concurrency", 1, 50) }
        }
    }

    @Test fun onlyAllowsCurrentServerTargets() {
        val targets = listOf(NativeLoadTestTarget("/health", "Health", ""))
        assertEquals(listOf("/health"), nativeOperationsPaths(setOf("/health"), targets))
        listOf(emptySet(), setOf("/admin/users"), setOf("/health", "/old-target")).forEach { selected ->
            assertThrows(IllegalArgumentException::class.java) { nativeOperationsPaths(selected, targets) }
        }
    }

    @Test fun policyFormExcludesCleanupMetadata() {
        val policy = NativeAuditPolicy(true, 180, 365, 90, null, 0)
        assertEquals(policy.toForm(), policy.copy(lastCleanupAt = "2026-10-13", lastCleanupCount = 12).toForm())
    }
}
