package app.pulso.music

import org.junit.Assert.assertEquals
import org.junit.Test
import app.pulso.music.PlaybackRecovery.Action.*

class PlaybackRecoveryTest {
    @Test fun expiredStreamRetriesOnceThenAdvances() {
        val recovery = PlaybackRecovery()
        assertEquals(RETRY, recovery.decide("a", true, true, "b"))
        assertEquals(NEXT, recovery.decide("a", true, true, "b"))
    }
    @Test fun offlineDoesNotConsumeRetryOrSkipQueue() {
        val recovery = PlaybackRecovery()
        repeat(5) { assertEquals(OFFLINE, recovery.decide("a", true, false, "b")) }
        assertEquals(RETRY, recovery.decide("a", true, true, "b"))
    }
    @Test fun missingLocalAudioSkipsWithoutNetwork() {
        assertEquals(NEXT, PlaybackRecovery().decide("local-a", false, false, "b"))
    }
    @Test fun repeatAllNeverCyclesThroughFailedSongs() {
        val recovery = PlaybackRecovery()
        assertEquals(NEXT, recovery.decide("a", false, true, "b"))
        assertEquals(STOP, recovery.decide("b", false, true, "a"))
    }
    @Test fun stopsAfterThreeUnavailableTracks() {
        val recovery = PlaybackRecovery()
        assertEquals(NEXT, recovery.decide("a", false, true, "b"))
        assertEquals(NEXT, recovery.decide("b", false, true, "c"))
        assertEquals(STOP, recovery.decide("c", false, true, "d"))
    }
    @Test fun stopsAtEndOrSameSongAndCanRetryAfterReset() {
        val recovery = PlaybackRecovery()
        assertEquals(STOP, recovery.decide("a", false, true, null))
        assertEquals(STOP, recovery.decide("a", false, true, "a"))
        recovery.decide("b", true, true, null)
        recovery.reset()
        assertEquals(RETRY, recovery.decide("b", true, true, null))
    }
}
