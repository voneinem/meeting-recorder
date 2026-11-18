package app.meetingrecorder.core.transcription

import app.meetingrecorder.core.models.ModelLanguages
import kotlin.math.pow

/** The language chosen once for a meeting (research R-6). [probes] are the probe positions in frames. */
public data class LanguageChoice(val code: String, val confidence: Double?, val probes: List<Long>)

/** Where to probe, and how to turn the probabilities of the probes into one language. */
public object LanguageProbe {

    public const val PROBE_SECONDS: Int = 30
    public const val SILENCE_DBFS: Double = -60.0
    public const val MAX_TRIES: Int = 9

    private val nominalFractions = listOf(0.10, 0.40, 0.70)
    private const val MAX_OFFSET_STEPS = 4

    /** One probe window `[fromFrame, toFrame)` of the audio. */
    public data class Probe(val fromFrame: Long, val toFrame: Long)

    /** The probes' answers, in the order of [Probe]s. */
    public data class ProbeResult(val probe: Probe, val probabilities: List<LanguageProbability>)

    /** The three nominal probe windows (10%, 40%, 70%), each 30 s wide and clamped inside the audio. */
    public fun positions(durationFrames: Long, sampleRate: Int): List<Probe> =
        nominalFractions.map { window(durationFrames, sampleRate, (durationFrames * it).toLong()) }

    /**
     * Picks the probe windows to decode. A nominal window whose RMS is below -60 dBFS is replaced by
     * the nearest window (alternating later and earlier in steps of 30 s) that has signal; windows
     * that would repeat an already chosen one are skipped. At most [MAX_TRIES] windows are measured.
     * Returns an empty list when no window has signal.
     */
    public fun select(durationFrames: Long, sampleRate: Int, rms: (fromFrame: Long, toFrame: Long) -> Double): List<Probe> {
        val width = PROBE_SECONDS.toLong() * sampleRate
        val chosen = mutableListOf<Probe>()
        val measured = mutableSetOf<Probe>()
        var tries = 0
        for (nominal in positions(durationFrames, sampleRate)) {
            val offsets = sequence {
                yield(0L)
                for (step in 1..MAX_OFFSET_STEPS) {
                    yield(step * width)
                    yield(-step * width)
                }
            }
            for (offset in offsets) {
                if (tries >= MAX_TRIES) return chosen
                val candidate = window(durationFrames, sampleRate, nominal.fromFrame + offset)
                if (chosen.any { overlaps(it, candidate) } || candidate in measured) continue
                measured += candidate
                tries++
                if (!isSilent(rms(candidate.fromFrame, candidate.toFrame))) {
                    chosen += candidate
                    break
                }
            }
        }
        return chosen
    }

    /** True when [rms] (linear, full scale 1.0) is below -60 dBFS. */
    public fun isSilent(rms: Double): Boolean = rms < 10.0.pow(SILENCE_DBFS / 20.0)

    /**
     * Sums the probabilities of all probes and takes the highest language; the confidence is its mean
     * probability over the probes. An English-only model is always `en`. With no probe (no signal) the
     * [deviceLanguage] is used when the model is multilingual, otherwise `en`.
     */
    public fun choose(results: List<ProbeResult>, modelLanguages: ModelLanguages, deviceLanguage: String): LanguageChoice {
        if (modelLanguages == ModelLanguages.ENGLISH_ONLY) return LanguageChoice("en", null, emptyList())
        val positions = results.map { it.probe.fromFrame }
        if (results.isEmpty()) return LanguageChoice(deviceLanguage.ifBlank { "en" }, null, positions)
        val sums = sortedMapOf<String, Double>()
        for (result in results) {
            for (p in result.probabilities) sums.merge(p.code, p.probability, Double::plus)
        }
        val best = sums.entries.maxWithOrNull(compareBy<Map.Entry<String, Double>> { it.value }.thenByDescending { it.key })
            ?: return LanguageChoice(deviceLanguage.ifBlank { "en" }, null, positions)
        return LanguageChoice(best.key, best.value / results.size, positions)
    }

    private fun window(durationFrames: Long, sampleRate: Int, from: Long): Probe {
        val width = PROBE_SECONDS.toLong() * sampleRate
        if (durationFrames <= width) return Probe(0, durationFrames)
        val start = from.coerceIn(0, durationFrames - width)
        return Probe(start, start + width)
    }

    private fun overlaps(a: Probe, b: Probe): Boolean = a.fromFrame < b.toFrame && b.fromFrame < a.toFrame
}
