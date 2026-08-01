import com.aistudio.mediatool.core.SlideshowTiming
import com.aistudio.mediatool.core.SlideshowInterval
import com.aistudio.mediatool.core.SlideshowSlot
import com.aistudio.mediatool.core.media.AudioMath
import com.aistudio.mediatool.core.media.MediaEffectPolicy
import com.aistudio.mediatool.core.media.WavHeader
import com.aistudio.mediatool.core.media.TimelineSegments
import com.aistudio.mediatool.core.diagnostics.DiagnosticRedactor
import com.aistudio.mediatool.core.ml.ContentRange
import com.aistudio.mediatool.core.ml.OnnxThreadingConfig
import com.aistudio.mediatool.core.ml.OnnxThreadingPolicy
import com.aistudio.mediatool.core.ml.OverlapWindow
import com.aistudio.mediatool.core.ml.StemPreflight
import com.aistudio.mediatool.core.ml.StemModelRegistry
import com.aistudio.mediatool.core.subtitle.SubtitleParser
import com.aistudio.mediatool.core.subtitle.UtteranceQueueTracker

fun main() {
    val wav = WavHeader.create(176_400, 44_100, 2, 16)
    check(String(wav.copyOfRange(0, 4)) == "RIFF")
    check(String(wav.copyOfRange(8, 12)) == "WAVE")

    val cues = SubtitleParser.parse("""
        WEBVTT

        00:00:01.000 --> 00:00:02.500
        <b>Xin &amp; chào</b>
    """.trimIndent())
    check(cues.single().text == "Xin & chào")
    check(cues.single().startMs == 1_000L)

    val estimate = StemPreflight.estimate(60_000L, 2)
    check(estimate.temporaryBytes > 0L)
    check(estimate.recommendedFreeBytes > estimate.temporaryBytes)

    check(SlideshowTiming.distributeDurations(10L, 3).sum() == 10L)
    check(
        SlideshowTiming.buildSchedule(
            10_000L,
            listOf(SlideshowInterval(null, null), SlideshowInterval(4_000L, 6_000L), SlideshowInterval(null, null)),
        ) == listOf(SlideshowSlot(0L, 4_000L), SlideshowSlot(4_000L, 6_000L), SlideshowSlot(6_000L, 10_000L)),
    )
    check(ContentRange.parse("bytes 10-19/100")?.start == 10L)
    check(ContentRange.parse("bytes 20-10/100") == null)

    check(AudioMath.stereoPan(50) == AudioMath.StereoGain(1f, 1f))
    check(AudioMath.clampedFadeDuration(5.0, 4.0) == 2.0)
    check(AudioMath.progressPercent(500L, 1_000L) == 50)
    check(kotlin.math.abs(AudioMath.truePeakDbFromPercent(50f) + 6.0206) < 0.001)
    check(!AudioMath.canApplyGlobalFade(3.0, listOf(1_000L, null)))
    check(!MediaEffectPolicy.supportsAudioFilters(isVideoMode = false, modeIndex = 1))

    check(TimelineSegments.parse("0,5000", "2000,7000").isValid)
    check(!TimelineSegments.parse("bad", "1000").isValid)

    val overlap = OverlapWindow.weights(23, 64)
    check(kotlin.math.abs(overlap.previous + overlap.current - 1f) < 0.000001f)
    val melModel = StemModelRegistry.melBandRoFormerTwoStem
    check(melModel.chunking.frames == 352_800)
    check(melModel.sources.vocals.sourceIndices == listOf(0))
    check(melModel.sources.music.sourceIndices == listOf(1))
    check(OverlapWindow.weights(0, melModel.chunking).previous == 1f)
    check(OverlapWindow.weights(melModel.chunking.overlapFrames - 1, melModel.chunking).current == 1f)
    val melOverlap = OverlapWindow.weights(35_280, melModel.chunking)
    check(kotlin.math.abs(melOverlap.previous + melOverlap.current - 1f) < 0.000001f)
    check(OnnxThreadingPolicy.resolve(2, 4) == OnnxThreadingConfig(1, 4))

    val queue = UtteranceQueueTracker()
    val first = queue.enqueue(1)
    val second = queue.enqueue(2)
    check(!queue.complete(first))
    check(queue.complete(second))

    val privateLog = "content://media/42 /storage/emulated/0/Music/private.mp3 Authorization: Bearer secret"
    val redacted = DiagnosticRedactor.sanitize(privateLog).orEmpty()
    check("content://" !in redacted)
    check("/storage/" !in redacted)
    check("secret" !in redacted)
    val ffmpegLog = DiagnosticRedactor.sanitizeFfmpegLogs(
        "Input #0 from 'private-song.mp3':\n  title : Private title\nError at /storage/private-song.mp3",
    ).orEmpty()
    check("private-song" !in ffmpegLog)
    check("Private title" !in ffmpegLog)
    check("<media-metadata omitted>" in ffmpegLog)
    check(DiagnosticRedactor.stableId("one") == DiagnosticRedactor.stableId("one"))
    check(DiagnosticRedactor.stableId("one") != DiagnosticRedactor.stableId("two"))
    println("CORE SMOKE OK")
}
