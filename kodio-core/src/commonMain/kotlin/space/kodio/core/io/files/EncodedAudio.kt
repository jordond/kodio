package space.kodio.core.io.files

import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * Encoded audio file bytes for platform-native playback without first
 * materializing a Kodio AudioRecording.
 */
class EncodedAudio private constructor(
    val fileFormat: AudioFileFormat,
    val fileName: String?,
    private val bytes: ByteArray,
) {
    val sizeInBytes: Int
        get() = bytes.size

    fun toByteArray(): ByteArray = bytes.copyOf()

    companion object {
        fun fromBytes(
            bytes: ByteArray,
            fileFormat: AudioFileFormat,
            fileName: String? = null,
        ): EncodedAudio = EncodedAudio(
            fileFormat = fileFormat,
            fileName = fileName,
            bytes = bytes.copyOf(),
        )

        fun fromBytes(bytes: ByteArray, fileName: String): EncodedAudio =
            fromBytes(bytes, AudioFileReader.detectFormatFromFileName(fileName), fileName)

        fun fromSource(
            source: Source,
            fileFormat: AudioFileFormat,
            fileName: String? = null,
        ): EncodedAudio = fromBytes(source.readByteArray(), fileFormat, fileName)

        fun fromFile(
            path: Path,
            fileSystem: FileSystem = SystemFileSystem,
        ): EncodedAudio {
            val fileName = path.toString()
            val fileFormat = AudioFileReader.detectFormat(path)
            return fileSystem.source(path).buffered().use { source ->
                fromSource(source, fileFormat, fileName)
            }
        }
    }
}
