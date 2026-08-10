package com.foreverjukebox.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one rule every `thresh` read goes through. The same table passes in the web engine's
 * `parsePinnedThreshold` and in the cast receiver, so a saved tuning string means the same thing
 * whichever app opens it.
 */
class ThresholdReadRuleTest {

    @Test
    fun aValueInRangeIsTheChosenThreshold() {
        assertEquals(MIN_THRESHOLD, parsePinnedThreshold("2"))
        assertEquals(29, parsePinnedThreshold("29"))
        assertEquals(45, parsePinnedThreshold("45"))
        assertEquals(MAX_THRESHOLD, parsePinnedThreshold("80"))
    }

    @Test
    fun anythingBelowTheControlRangeIsAuto() {
        assertNull(parsePinnedThreshold("1"))
        assertNull(parsePinnedThreshold("0"))
        assertNull(parsePinnedThreshold("-5"))
        assertNull(parsePinnedThreshold("-0"))
    }

    @Test
    fun anythingAboveTheMaximumClampsSoTheReportedValueIsTheActingValue() {
        assertEquals(MAX_THRESHOLD, parsePinnedThreshold("81"))
        assertEquals(MAX_THRESHOLD, parsePinnedThreshold("500"))
        assertEquals(MAX_THRESHOLD, parsePinnedThreshold("999999999999999999999999"))
    }

    @Test
    fun unreadableInputIsAutoRatherThanAnError() {
        assertNull(parsePinnedThreshold(null as String?))
        assertNull(parsePinnedThreshold(""))
        assertNull(parsePinnedThreshold("   "))
        assertNull(parsePinnedThreshold("abc"))
        assertNull(parsePinnedThreshold("."))
        assertNull(parsePinnedThreshold("-"))
        assertNull(parsePinnedThreshold("+"))
    }

    @Test
    fun aLeadingIntegerIsTakenFromWhateverElseTheStringHolds() {
        // Matches the web app's Number.parseInt, so a hand-edited or legacy string reads the same
        // in every app rather than being a choice in one and auto in another.
        assertEquals(29, parsePinnedThreshold("29.7"))
        assertEquals(45, parsePinnedThreshold("45abc"))
        assertEquals(45, parsePinnedThreshold("  45  "))
        assertEquals(45, parsePinnedThreshold("+45"))
        // "0x10" stops at the x, giving 0, which is below the range and so reads as auto.
        assertNull(parsePinnedThreshold("0x10"))
    }

    @Test
    fun aNumericFieldReadsUnderTheSameRule() {
        assertEquals(45, parsePinnedThreshold(45))
        assertEquals(MAX_THRESHOLD, parsePinnedThreshold(500))
        assertNull(parsePinnedThreshold(1))
        assertNull(parsePinnedThreshold(0))
        assertNull(parsePinnedThreshold(null as Int?))
    }

    @Test
    fun theControlFloorIsNotTheComputedFloor() {
        // A computed threshold can only be 10, 15, ... 80; the 2..9 range exists only for a
        // threshold someone chose. Nothing should collapse these two minimums together.
        assertEquals(MIN_THRESHOLD, 2)
        assertEquals(MAX_THRESHOLD, 80)
        assertEquals(MAX_THRESHOLD, JukeboxConfig().maxBranchThreshold)
    }
}
