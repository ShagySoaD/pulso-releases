package app.pulso.music

import org.junit.Assert.*
import org.junit.Test

class EngineStartupTest {
    @Test fun bundledEngineStartsWithoutNetwork() {
        assertEquals("2026.08.19", EngineStartup.start({ "2026.08.19" }).version)
    }
    @Test fun failedUpdateDoesNotDisableWorkingEngine() {
        var checks = 0
        val ready = EngineStartup.start({ checks++; "bundled" }, { throw java.io.IOException("Offline") })
        assertEquals("bundled", ready.version)
        assertEquals("Offline", ready.updateWarning)
        assertEquals(2, checks)
    }
    @Test fun successfulUpdateIsVerified() {
        var version = "old"
        assertEquals("new", EngineStartup.start({ version }, { version = "new" }).version)
    }
    @Test(expected = IllegalStateException::class) fun missingRuntimeIsNotReportedReady() {
        EngineStartup.start({ throw IllegalStateException("Missing Python") })
    }
    @Test(expected = IllegalStateException::class) fun damagedUpdateIsNotReportedReady() {
        var works = true
        EngineStartup.start({ check(works); "old" }, { works = false })
    }
}
