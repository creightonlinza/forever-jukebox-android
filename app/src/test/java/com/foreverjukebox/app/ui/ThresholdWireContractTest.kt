package com.foreverjukebox.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What `thresh` means on the wire, stated only in terms of persisted strings. Favorites, playlist
 * entries, share links, and cast payloads all travel as these strings between this app, the web
 * app, and the receiver, so the meanings pinned here are the ones the three have to share.
 *
 * Deliberately says nothing about how a threshold is held in memory: a string is the only thing
 * every side sees.
 */
class ThresholdWireContractTest {

    private fun equivalent(left: String?, right: String?): Boolean =
        TuningParamsCodec.savedTuningParamsEquivalent(left, right)

    @Test
    fun aChosenThresholdIsTheSameTuningOnlyAsItself() {
        assertTrue(equivalent("thresh=45", "thresh=45"))
        assertFalse(equivalent("thresh=45", "thresh=40"))
    }

    @Test
    fun aChosenThresholdIsNotTheSameTuningAsNoThreshold() {
        // No `thresh` is how "let the track decide" is spelled, and a number is a decision, so the
        // two can never fold together — including at the lowest number a control can produce.
        assertFalse(equivalent("thresh=45", null))
        assertFalse(equivalent("thresh=2", null))
        assertFalse(equivalent("jb=1&thresh=45", "jb=1"))
    }

    @Test
    fun thresholdsBelowTheControlRangeAllMeanTheSameThing() {
        // No control can emit these, so they all carry the one meaning available: not a choice.
        assertTrue(equivalent("thresh=0", "thresh=1"))
        assertTrue(equivalent("jb=1&thresh=0", "jb=1&thresh=1"))
    }

    @Test
    fun unreadableThresholdsAreNotTreatedAsAChoice() {
        assertTrue(equivalent("thresh=abc", null))
        assertTrue(equivalent("thresh=", null))
        assertTrue(equivalent("thresh=-5", null))
    }

    @Test
    fun aThresholdTravelsIndependentlyOfTheControlsAroundIt() {
        assertTrue(equivalent("bl=10&thresh=45&jb=1", "jb=1&thresh=45&bl=10"))
        assertTrue(equivalent("thresh=45", "thresh=45&ah=1"))
        assertTrue(equivalent("thresh=45", "thresh=45&d=4,9&ab=12"))
        assertFalse(equivalent("thresh=45&jb=1", "thresh=45"))
        assertFalse(equivalent("thresh=45&am=daycore", "thresh=45"))
    }

    @Test
    fun aThresholdSurvivesEveryOtherLegacySpellingAroundIt() {
        // Favorites written by the web app spell the branch-length and boolean params their own way;
        // that must not change how the threshold beside them reads.
        assertTrue(equivalent("lg=0&thresh=45", "thresh=45"))
        assertTrue(equivalent("jb=true&thresh=45", "jb=1&thresh=45"))
        assertTrue(equivalent("sq=false&thresh=45", "thresh=45"))
    }
}
