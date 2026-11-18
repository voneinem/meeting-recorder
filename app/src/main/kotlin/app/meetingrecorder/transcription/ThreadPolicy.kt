package app.meetingrecorder.transcription

import java.io.File

/**
 * How many threads the decoder uses (research R-9, spike S-1): the number of cores whose maximum frequency is within 95%
 * of the highest one (the "big" cores), clamped to 2..6. More threads than big cores slow the decode down on
 * phones with big.LITTLE CPUs: on the reference phone (two cores at 2.4 GHz, six at 2.0 GHz) two threads decode at
 * 0.56 times real time and six at 0.74. An 80% rule counted the 2.0 GHz cores as big (83%), so it is 95%. The threads keep the default priority: never `THREAD_PRIORITY_BACKGROUND`,
 * which would starve the decode of CPU time.
 */
object ThreadPolicy {
    /** Only set if a measurement finds a better fixed count than the computed one; the spike found none (the 95% rule gives its 2). */
    val SPIKE_THREAD_OVERRIDE: Int? = null

    private const val MIN_THREADS = 2
    private const val MAX_THREADS = 6
    private const val BIG_CORE_NUMERATOR = 95
    private const val BIG_CORE_DENOMINATOR = 100
    private const val CPU_DIRECTORY = "/sys/devices/system/cpu"
    private val cpuName = Regex("cpu\\d+")

    /** [frequencies] holds one entry per core; `null` marks a core whose frequency could not be read and is ignored. */
    fun threadCount(frequencies: List<Long?>, override: Int? = SPIKE_THREAD_OVERRIDE): Int {
        if (override != null) return override.coerceIn(MIN_THREADS, MAX_THREADS)
        val known = frequencies.filterNotNull()
        val big = if (known.isEmpty()) {
            1 // nothing readable: treat the phone as one core and use the minimum
        } else {
            val highest = known.max()
            known.count { it * BIG_CORE_DENOMINATOR >= highest * BIG_CORE_NUMERATOR }
        }
        return big.coerceIn(MIN_THREADS, MAX_THREADS)
    }

    /** Reads `cpuinfo_max_freq` of every `cpuN` entry through [read] (a path relative to the cpu directory, or null). */
    fun readFrequencies(cpuNames: List<String>, read: (String) -> String?): List<Long?> =
        cpuNames.filter { cpuName.matches(it) }.sortedBy { it.removePrefix("cpu").toInt() }.map { name ->
            read(name)?.trim()?.toLongOrNull()
        }

    /** The thread count for this phone. */
    fun current(): Int {
        val names = File(CPU_DIRECTORY).list()?.toList().orEmpty()
        val frequencies = readFrequencies(names) { name ->
            runCatching { File("$CPU_DIRECTORY/$name/cpufreq/cpuinfo_max_freq").readText() }.getOrNull()
        }
        return threadCount(frequencies)
    }
}
