package xyz.hlovet.portal.prototype

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeSystemStatusTest {
    @Test fun createdBackupImmediatelyIncludesMediaAndReplacesDuplicateWithoutDoubleCounting() {
        val old = NativeSystemBackup("old.sql.gz", 100, false, null, "verified", "2026-10-12T01:00:00Z")
        val created = NativeSystemBackup("new.sql.gz", 200, true, 300, "verified", "2026-10-13T01:00:00Z")
        val initial = NativeSystemBackups(true, 100, 1, old, listOf(old))
        val updated = initial.withCreatedBackup(created)
        assertEquals(2, updated.fileCount)
        assertEquals(600, updated.totalBytes)
        assertEquals(created, updated.latest)
        assertEquals(updated, updated.withCreatedBackup(created))
    }

    @Test fun nullableDependencyMetricsAreNotPretendedToBeZero() {
        val parsed = with(NativeSystemStatusApi) { JSONObject("""{"database":{"connected":false,"sizeBytes":null},"redis":{"connected":false,"latencyMs":null}}""").toSystemStatus() }
        assertNull(parsed.database.sizeBytes)
        assertNull(parsed.database.migrationCount)
        assertNull(parsed.redis.latencyMs)
        assertNull(parsed.redis.keyCount)
        assertNull(parsed.redis.maxMemoryBytes)
        assertNull(parsed.reliability.coveragePercentage)
    }

    @Test fun parsesSeparateEventsTrendsAndReliabilityCounters() {
        val parsed = with(NativeSystemStatusApi) { JSONObject("""{
          "database":{"connected":true,"version":"8.4","migrationCount":17,"latestMigration":{"name":"fixture-migration","finishedAt":"2026-10-13T01:00:00Z"}},
          "monitoring":{"slowRequests":[{"method":"GET","path":"/slow","statusCode":200,"durationMs":1500}],"recentErrors":[{"path":"/fail","statusCode":503,"message":"fixture error"}],"recentClientErrors":[{"source":"android","message":"fixture client","path":"/screen"}],"memoryTrend":[{"recordedAt":"2026-10-13T01:00:00Z","rssBytes":1000}],"diskTrend":[{"usedPercent":44}]},
          "reliability":{"backupCoverage":{"backedUpFiles":8,"totalFiles":10,"uncoveredFiles":2,"percentage":80},"lastSuccessfulBackupSource":"media","anomalies":{"backupFailures":1,"diskPressure":2,"missingFiles":3,"orphanFiles":4,"metadataMismatches":5,"recentApiErrors":6,"total":21}}
        }""").toSystemStatus() }
        assertTrue(parsed.database.latestMigration!!.startsWith("fixture-migration"))
        assertEquals("/slow", parsed.monitoring.slowRequests.single().path)
        assertEquals("fixture error", parsed.monitoring.recentErrors.single().message)
        assertEquals("fixture client", parsed.monitoring.recentClientErrors.single().message)
        assertEquals(44.0, parsed.monitoring.diskTrend.single().usedPercent, 0.0)
        assertEquals(8, parsed.reliability.backedUpFiles)
        assertEquals(21, parsed.reliability.anomalyTotal)
        assertEquals(5, parsed.reliability.metadataMismatches)
        assertEquals("media", parsed.reliability.lastSuccessfulBackupSource)
    }
}
