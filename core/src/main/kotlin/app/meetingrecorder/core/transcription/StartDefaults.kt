package app.meetingrecorder.core.transcription

import app.meetingrecorder.core.models.ModelLanguages

/** The preselection and the notices of the start dialog (data-model.md, "TranscriptionSettings"). */
public object StartDefaults {

    public const val DEFAULT_LANGUAGE: String = "auto"

    /**
     * The default model if installed, else the last used model if installed, else the only installed
     * model, else `null` (the dialog then leads to the model settings).
     */
    public fun preselect(installed: List<String>, defaultModelId: String?, lastModelId: String?): String? =
        listOfNotNull(defaultModelId, lastModelId).firstOrNull { it in installed } ?: installed.singleOrNull()

    /** True when an English-only model is combined with a language other than English or automatic. */
    public fun languageWarning(languages: ModelLanguages, requestedLanguage: String): Boolean =
        languages == ModelLanguages.ENGLISH_ONLY && requestedLanguage != "en" && requestedLanguage != DEFAULT_LANGUAGE

    /** True when the phone's RAM is below twice what the model needs: `totalMem < 2 x approxMemoryMb MiB`. */
    public fun memoryWarning(totalMemBytes: Long, approxMemoryMb: Int): Boolean =
        totalMemBytes < 2L * approxMemoryMb * MIB

    private const val MIB = 1024L * 1024
}
