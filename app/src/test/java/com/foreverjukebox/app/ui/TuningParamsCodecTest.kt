package com.foreverjukebox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TuningParamsCodecTest {

    @Test
    fun parseExtractsKnownFields() {
        val parsed = TuningParamsCodec.parse("jb=1&lg=0&sq=0&thresh=27&bp=12,34,56&d=3,8&ab=22&ah=1")

        assertNotNull(parsed)
        assertEquals(27, parsed?.threshold)
        assertEquals(12, parsed?.minProbPercent)
        assertEquals(34, parsed?.maxProbPercent)
        assertEquals(56, parsed?.rampPercent)
        assertTrue(parsed?.highlightAnchorBranch == true)
        assertTrue(parsed?.justBackwards == true)
        assertEquals(0, parsed?.minJumpDistancePercent)
        assertTrue(parsed?.removeSequentialBranches == true)
        assertEquals(listOf(3, 8), parsed?.deletedEdgeIds)
        assertEquals(22, parsed?.anchorBeat)
    }

    @Test
    fun parseReturnsNullWhenNoKnownFields() {
        val parsed = TuningParamsCodec.parse("foo=1&bar=2")
        assertNull(parsed)
    }

    @Test
    fun parseHonorsMinThreshold() {
        val parsed = TuningParamsCodec.parse("thresh=0&jb=1", minThreshold = 2)
        assertNotNull(parsed)
        assertNull(parsed?.threshold)
        assertTrue(parsed?.justBackwards == true)
    }

    @Test
    fun parseRecognizesBooleanAliasesForSequentialRule() {
        val parsedTrue = TuningParamsCodec.parse("sq=true")
        val parsedFalse = TuningParamsCodec.parse("sq=false")

        assertTrue(parsedTrue?.removeSequentialBranches == true)
        assertFalse(parsedFalse?.removeSequentialBranches == true)
    }

    @Test
    fun parseTreatsBooleanFieldsCaseInsensitively() {
        val parsed = TuningParamsCodec.parse("jb=TRUE&lg=False")

        assertTrue(parsed?.justBackwards == true)
        assertEquals(0, parsed?.minJumpDistancePercent)
    }

    @Test
    fun parseExtractsHighlightAndIgnoresUnknownParams() {
        assertTrue(TuningParamsCodec.parse("ah=1")?.highlightAnchorBranch == true)
        assertNull(TuningParamsCodec.parse("foo=bar"))

        val parsed = TuningParamsCodec.parse("jb=1&foo=bar&ah=1")
        assertNotNull(parsed)
        assertTrue(parsed?.justBackwards == true)
        assertTrue(parsed?.highlightAnchorBranch == true)
    }

    @Test
    fun parseExtractsAudioMode() {
        val parsed = TuningParamsCodec.parse("am=nightcore")

        assertNotNull(parsed)
        assertEquals(JukeboxAudioMode.Nightcore, parsed?.audioMode)
    }

    @Test
    fun parseExtractsLatestAudioModes() {
        assertEquals(JukeboxAudioMode.EightBit, TuningParamsCodec.parse("am=eight_bit")?.audioMode)
        assertEquals(JukeboxAudioMode.Underwater, TuningParamsCodec.parse("am=underwater")?.audioMode)
        assertEquals(JukeboxAudioMode.Cathedral, TuningParamsCodec.parse("am=cathedral")?.audioMode)
        assertEquals(
            JukeboxAudioMode.Cowbell,
            TuningParamsCodec.parse("am=cowbell")?.audioMode
        )
    }

    @Test
    fun audioModesUseExplicitNativeCodes() {
        assertEquals(0, JukeboxAudioMode.Off.nativeModeCode)
        assertEquals(1, JukeboxAudioMode.Nightcore.nativeModeCode)
        assertEquals(2, JukeboxAudioMode.Daycore.nativeModeCode)
        assertEquals(3, JukeboxAudioMode.Vaporwave.nativeModeCode)
        assertEquals(4, JukeboxAudioMode.EightD.nativeModeCode)
        assertEquals(5, JukeboxAudioMode.Lofi.nativeModeCode)
        assertEquals(6, JukeboxAudioMode.EightBit.nativeModeCode)
        assertEquals(7, JukeboxAudioMode.Underwater.nativeModeCode)
        assertEquals(8, JukeboxAudioMode.Cathedral.nativeModeCode)
        assertEquals(9, JukeboxAudioMode.Cowbell.nativeModeCode)
    }

    @Test
    fun parseIgnoresInvalidAudioMode() {
        assertNull(TuningParamsCodec.parse("am=chipmunk"))

        val parsed = TuningParamsCodec.parse("jb=1&am=chipmunk")
        assertNotNull(parsed)
        assertNull(parsed?.audioMode)
        assertTrue(parsed?.justBackwards == true)
    }

    @Test
    fun parseClampsBpPercentFields() {
        val parsed = TuningParamsCodec.parse("bp=-5,120,400")

        assertEquals(0, parsed?.minProbPercent)
        assertEquals(100, parsed?.maxProbPercent)
        assertEquals(100, parsed?.rampPercent)
    }

    @Test
    fun parseIgnoresMalformedBpTriplet() {
        val parsed = TuningParamsCodec.parse("bp=10,20")

        assertNotNull(parsed)
        assertNull(parsed?.minProbPercent)
        assertNull(parsed?.maxProbPercent)
        assertNull(parsed?.rampPercent)
    }

    @Test
    fun parseFiltersInvalidDeletedEdgeIds() {
        val parsed = TuningParamsCodec.parse("d=1,-1,foo,3")

        assertEquals(listOf(1, 3), parsed?.deletedEdgeIds)
    }

    @Test
    fun parseDecodesPercentEncodedFields() {
        val parsed = TuningParamsCodec.parse("bp=15%2C45%2C20&d=5%2C8")

        assertEquals(15, parsed?.minProbPercent)
        assertEquals(45, parsed?.maxProbPercent)
        assertEquals(20, parsed?.rampPercent)
        assertEquals(listOf(5, 8), parsed?.deletedEdgeIds)
    }

    @Test
    fun parseAcceptsCanonicalBranchLengths() {
        for (percent in listOf(5, 10, 20, 30)) {
            assertEquals(
                percent,
                TuningParamsCodec.parse("bl=$percent")?.minJumpDistancePercent
            )
        }
    }

    @Test
    fun parseSupportsLegacyLongBranchValues() {
        assertEquals(20, TuningParamsCodec.parse("lg=1")?.minJumpDistancePercent)
        assertEquals(0, TuningParamsCodec.parse("lg=0")?.minJumpDistancePercent)
    }

    @Test
    fun parseAcceptsExplicitZeroBranchLength() {
        assertEquals(0, TuningParamsCodec.parse("bl=0")?.minJumpDistancePercent)
        assertEquals(0, TuningParamsCodec.parse("bl=0&lg=1")?.minJumpDistancePercent)
    }

    @Test
    fun parseCanonicalBranchLengthOverridesLegacyValue() {
        assertEquals(
            5,
            TuningParamsCodec.parse("bl=5&lg=1")?.minJumpDistancePercent
        )
        assertEquals(
            30,
            TuningParamsCodec.parse("bl=30&lg=0")?.minJumpDistancePercent
        )
    }

    @Test
    fun parseInvalidBranchLengthFallsBackToLegacyValue() {
        assertEquals(
            20,
            TuningParamsCodec.parse("bl=15&lg=1")?.minJumpDistancePercent
        )
        assertEquals(
            0,
            TuningParamsCodec.parse("bl=15&lg=0")?.minJumpDistancePercent
        )
        assertNull(TuningParamsCodec.parse("bl=15")?.minJumpDistancePercent)
    }

    @Test
    fun buildCastLoadPayloadReturnsSparsePrefsOnlyWhenRawIsMissing() {
        val highlighted = TuningParamsCodec.buildCastLoadPayload(
            raw = null,
            highlightAnchorBranch = true
        )
        val empty = TuningParamsCodec.buildCastLoadPayload(
            raw = null,
            highlightAnchorBranch = false
        )

        assertEquals("ah=1", highlighted)
        assertNull(empty)
    }

    @Test
    fun buildCastLoadPayloadKeepsSparseRawTuning() {
        val payload = TuningParamsCodec.buildCastLoadPayload(
            raw = "thresh=35&jb=1&ah=0&bp=1,2,3&d=4,9&ab=22&am=lofi",
            highlightAnchorBranch = true
        )

        assertEquals("thresh=35&jb=1&ah=1&bp=1,2,3&d=4,9&ab=22&am=lofi", payload)
    }

    @Test
    fun buildCastLoadPayloadCanonicalizesBranchLength() {
        assertEquals(
            "bl=30&jb=1",
            TuningParamsCodec.buildCastLoadPayload(
                raw = "lg=1&jb=1&bl=30",
                highlightAnchorBranch = false
            )
        )
        assertEquals(
            "bl=20",
            TuningParamsCodec.buildCastLoadPayload(
                raw = "lg=1",
                highlightAnchorBranch = false
            )
        )
        assertEquals(
            "bl=0",
            TuningParamsCodec.buildCastLoadPayload(
                raw = "lg=0",
                highlightAnchorBranch = false
            )
        )
        assertEquals(
            "bl=0",
            TuningParamsCodec.buildCastLoadPayload(
                raw = "bl=0",
                highlightAnchorBranch = false
            )
        )
    }

    @Test
    fun buildCastLoadPayloadKeepsLatestAudioModes() {
        assertEquals(
            "am=eight_bit",
            TuningParamsCodec.buildCastLoadPayload(
                raw = "am=eight_bit",
                highlightAnchorBranch = false
            )
        )
        assertEquals(
            "am=underwater",
            TuningParamsCodec.buildCastLoadPayload(
                raw = "am=underwater",
                highlightAnchorBranch = false
            )
        )
        assertEquals(
            "am=cathedral",
            TuningParamsCodec.buildCastLoadPayload(
                raw = "am=cathedral",
                highlightAnchorBranch = false
            )
        )
    }

    @Test
    fun buildCastLoadPayloadDropsInvalidThresholdAndAudioMode() {
        val payload = TuningParamsCodec.buildCastLoadPayload(
            raw = "thresh=0&jb=1&am=chipmunk",
            highlightAnchorBranch = true
        )

        assertEquals("jb=1&ah=1", payload)
    }

    @Test
    fun buildCastLoadPayloadDropsUnknownKeys() {
        val payload = TuningParamsCodec.buildCastLoadPayload(
            raw = "jb=1&foo=bar&d=2&am=nightcore",
            highlightAnchorBranch = false
        )

        assertEquals("jb=1&d=2&am=nightcore", payload)
    }

    @Test
    fun buildCastLoadPayloadKeepsExplicitOffAudioMode() {
        val payload = TuningParamsCodec.buildCastLoadPayload(
            raw = "am=off",
            highlightAnchorBranch = false
        )

        assertEquals("am=off", payload)
    }

    @Test
    fun buildSavedTuningParamsReturnsNullForDefaultTuning() {
        val raw = TuningParamsCodec.buildSavedTuningParams(TuningState())

        assertNull(raw)
    }

    @Test
    fun buildSavedTuningParamsEmitsOnlyNonDefaultValues() {
        val raw = TuningParamsCodec.buildSavedTuningParams(
            TuningState(
                threshold = 31,
                computedThreshold = 29,
                minProb = 12,
                maxProb = 45,
                ramp = 20,
                justBackwards = true,
                minJumpDistancePercent = 30,
                removeSequential = true,
                deletedEdgeIds = listOf(4, 9),
                anchorBranchId = 128
            ),
            audioModeWireValue = "daycore"
        )

        assertEquals("jb=1&bl=30&sq=0&thresh=31&bp=12,45,20&d=4,9&ab=128&am=daycore", raw)
    }

    @Test
    fun buildSavedTuningParamsOmitsThresholdMatchingComputed() {
        val autoThreshold = TuningParamsCodec.buildSavedTuningParams(
            TuningState(threshold = 29, computedThreshold = 29, justBackwards = true)
        )
        val unknownComputed = TuningParamsCodec.buildSavedTuningParams(
            TuningState(threshold = 29, computedThreshold = null, justBackwards = true)
        )

        assertEquals("jb=1", autoThreshold)
        assertEquals("jb=1", unknownComputed)
    }

    @Test
    fun buildSavedTuningParamsOmitsOffAndBlankAudioMode() {
        val offMode = TuningParamsCodec.buildSavedTuningParams(
            TuningState(justBackwards = true),
            audioModeWireValue = JukeboxAudioMode.Off.wireValue
        )
        val blankMode = TuningParamsCodec.buildSavedTuningParams(
            TuningState(justBackwards = true),
            audioModeWireValue = "   "
        )

        assertEquals("jb=1", offMode)
        assertEquals("jb=1", blankMode)
    }

    @Test
    fun buildSavedTuningParamsNeverEmitsHighlightAnchor() {
        val raw = TuningParamsCodec.buildSavedTuningParams(
            TuningState(justBackwards = true, highlightAnchorBranch = true)
        )

        assertEquals("jb=1", raw)
    }

    @Test
    fun buildSavedTuningParamsRoundTripsThroughParse() {
        val raw = TuningParamsCodec.buildSavedTuningParams(
            TuningState(
                threshold = 31,
                computedThreshold = 29,
                minProb = 12,
                maxProb = 45,
                ramp = 20,
                minJumpDistancePercent = 20,
                deletedEdgeIds = listOf(4, 9),
                anchorBranchId = 128
            ),
            audioModeWireValue = "daycore"
        )
        val parsed = TuningParamsCodec.parse(raw, minThreshold = 2)

        assertEquals(31, parsed?.threshold)
        assertEquals(12, parsed?.minProbPercent)
        assertEquals(45, parsed?.maxProbPercent)
        assertEquals(20, parsed?.rampPercent)
        assertEquals(20, parsed?.minJumpDistancePercent)
        assertEquals(listOf(4, 9), parsed?.deletedEdgeIds)
        assertEquals(128, parsed?.anchorBeat)
        assertEquals(JukeboxAudioMode.Daycore, parsed?.audioMode)
    }

    @Test
    fun buildAudioModeParamSupportsOff() {
        assertEquals("am=off", TuningParamsCodec.buildAudioModeParam(JukeboxAudioMode.Off))
        assertEquals("am=lofi", TuningParamsCodec.buildAudioModeParam(JukeboxAudioMode.Lofi))
        assertEquals(
            "am=eight_bit",
            TuningParamsCodec.buildAudioModeParam(JukeboxAudioMode.EightBit)
        )
        assertEquals(
            "am=underwater",
            TuningParamsCodec.buildAudioModeParam(JukeboxAudioMode.Underwater)
        )
        assertEquals(
            "am=cathedral",
            TuningParamsCodec.buildAudioModeParam(JukeboxAudioMode.Cathedral)
        )
        assertEquals(
            "am=cowbell",
            TuningParamsCodec.buildAudioModeParam(JukeboxAudioMode.Cowbell)
        )
    }

    @Test
    fun buildAudioModeParamSupportsReceiverWireValues() {
        assertEquals("am=future_mode", TuningParamsCodec.buildAudioModeParam(" future_mode "))
        assertNull(TuningParamsCodec.buildAudioModeParam(" "))
    }

    @Test
    fun stripHighlightAnchorParamDropsHighlightOnlyPayload() {
        val stripped = TuningParamsCodec.stripHighlightAnchorParam("ah=1")
        assertNull(stripped)
    }

    @Test
    fun stripHighlightAnchorParamKeepsTrackSpecificTuning() {
        val stripped = TuningParamsCodec.stripHighlightAnchorParam("jb=1&ah=1&d=3,9&ab=22&am=lofi")
        assertEquals("jb=1&d=3%2C9&ab=22&am=lofi", stripped)
    }

    @Test
    fun mergeIntoStateOnlyOverridesPresentValues() {
        val base = TuningState(
            threshold = 33,
            minProb = 12,
            maxProb = 49,
            ramp = 15,
            highlightAnchorBranch = true,
            justBackwards = true,
            minJumpDistancePercent = 30,
            removeSequential = true
        )
        val parsed = TuningParamsCodec.parse("jb=0")
        val merged = TuningParamsCodec.mergeIntoState(base, parsed)

        assertEquals(33, merged.threshold)
        assertEquals(12, merged.minProb)
        assertEquals(49, merged.maxProb)
        assertEquals(15, merged.ramp)
        assertEquals(true, merged.highlightAnchorBranch)
        assertEquals(false, merged.justBackwards)
        assertEquals(30, merged.minJumpDistancePercent)
        assertEquals(true, merged.removeSequential)
    }

    @Test
    fun parseAndMergePreserveExplicitAnyBranchLength() {
        // An explicit bl=0 on the wire (e.g. cast reset params) must override a
        // non-zero base rather than being treated as absent.
        val parsed = TuningParamsCodec.parse("jb=0&bl=0&sq=1&thresh=31&bp=18,50,10", minThreshold = 2)
        val merged = TuningParamsCodec.mergeIntoState(
            TuningState(minJumpDistancePercent = 30),
            parsed
        )

        assertEquals(0, merged.minJumpDistancePercent)
    }
}
