package space.kodio.core.io.files

/**
 * Represents a supported audio container format for reading and writing.
 */
sealed class AudioFileFormat(
    val extension: String,
    val mimeType: String,
    val canWrite: Boolean = true,
) {
    data object Wav : AudioFileFormat("wav", "audio/wav")
    data object Aiff : AudioFileFormat("aiff", "audio/aiff")
    data object Au : AudioFileFormat("au", "audio/basic")
    data object Mp3 : AudioFileFormat("mp3", "audio/mpeg", canWrite = false)

    companion object {
        val entries: List<AudioFileFormat>
            get() = listOf(Wav, Aiff, Au, Mp3)
        val writableEntries: List<AudioFileFormat>
            get() = listOf(Wav, Aiff, Au)
    }
}
