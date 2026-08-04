package com.aistudio.mediatool.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MossFormer2WorkspaceTest {
    @Test
    fun synthesisBuffersAreClearedAndReused() {
        val workspace = MossFormer2Workspace()
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
    fun featureBuffersMatchModelContract() {
        val workspace = MossFormer2Workspace()

        assertEquals(MossFormer2Dsp.FRAMES * MossFormer2Dsp.MEL_BINS, workspace.featureBase.size)
        assertEquals(workspace.featureBase.size, workspace.featureDelta.size)
        assertEquals(workspace.featureBase.size, workspace.featureDeltaDelta.size)
        assertEquals(MossFormer2Dsp.FRAMES * MossFormer2Dsp.FEATURES, workspace.features.size)
    }
}
