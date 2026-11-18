package app.meetingrecorder.core.models

/**
 * The catalog is compiled in; nothing is ever fetched, so there is nothing to spoof. The entries are the
 * candidates of the spike that ran at most 1.0 times real time on the reference phone (`spike-results.md`,
 * decided at task T017). Sizes and hashes are the Hugging Face API values of the pinned revision
 * (contracts/model-catalog.md); `approxMemoryMb` is the measured peak plus a margin.
 */
public object ModelCatalog {
    private const val REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"

    private fun entry(
        id: String,
        key: String,
        sizeBytes: Long,
        sha256: String,
        approxMemoryMb: Int,
        recommended: Boolean = false,
    ) = ModelCatalogEntry(
        id = id,
        kind = ModelKind.SPEECH,
        displayNameKey = "model_${key}_name",
        descriptionKey = "model_${key}_description",
        languages = ModelLanguages.MULTILINGUAL,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/$REVISION/ggml-$id.bin",
        sourceName = "Hugging Face (huggingface.co)",
        license = "MIT",
        approxMemoryMb = approxMemoryMb,
        recommended = recommended,
    )

    public val entries: List<ModelCatalogEntry> = listOf(
        entry(
            id = "tiny-q5_1", key = "tiny", sizeBytes = 32152673L,
            sha256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",
            approxMemoryMb = 300,
        ),
        entry(
            id = "base-q5_1", key = "base_compact", sizeBytes = 59707625L,
            sha256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
            approxMemoryMb = 350,
        ),
        entry(
            id = "base-q8_0", key = "base_fast", sizeBytes = 81768585L,
            sha256 = "c577b9a86e7e048a0b7eada054f4dd79a56bbfa911fbdacf900ac5b567cbb7d9",
            approxMemoryMb = 400,
        ),
        entry(
            id = "small-q8_0", key = "small", sizeBytes = 264464607L,
            sha256 = "49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f",
            approxMemoryMb = 750, recommended = true,
        ),
    )

    /** The entries offered to the user: speech models only. */
    public fun offered(): List<ModelCatalogEntry> = entries.filter { it.kind == ModelKind.SPEECH }

    public fun find(id: String): ModelCatalogEntry? = entries.firstOrNull { it.id == id }

    /** The catalog entry whose file has this hash, if any. */
    public fun findBySha256(sha256: String): ModelCatalogEntry? = entries.firstOrNull { it.sha256 == sha256 }
}
