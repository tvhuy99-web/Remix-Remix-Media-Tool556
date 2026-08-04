package com.aistudio.mediatool.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MossFormer2WorkspaceTest {
    @Test
    fun synthesisBuffersAreClearedAndReused() {
        val mode = VoiceCleanupWindowMode.BALANCED_10S
        val workspace = MossFormer2Workspace(mode.segmentSamples, mode.frames)
        val output = workspace.output
        val envelope = workspace.envelope
        output.fill(1f)
        envelope.fill(2f)

        workspace.clearSynthesis()

        assertSame(output, workspace.output)
        assertSame(envelope, workspace.envelope)
        assertTrue(output.all { it == 0f })
        assertTrue(envelope.all { it == 0f })
    }

    @Test
    fun featureBuffersMatchEveryWindowContract() {
        for (mode in VoiceCleanupWindowMode.entries) {
            val workspace = MossFormer2Workspace(mode.segmentSamples, mode.frames)

            assertEquals(mode.segmentSamples, workspace.ditheredInput.size)
            assertEquals(mode.frames * MossFormer2Dsp.MEL_BINS, workspace.featureBase.size)
            assertEquals(workspace.featureBase.size, workspace.featureDelta.size)
            assertEquals(workspace.featureBase.size, workspace.featureDeltaDelta.size)
            assertEquals(mode.frames * MossFormer2Dsp.FEATURES, workspace.features.size)
            assertEquals(mode.segmentSamples, workspace.output.size)
            assertEquals(mode.segmentSamples, workspace.envelope.size)
        }
    }
}
