package space.kodio.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import space.kodio.core.io.files.EncodedAudio
import javax.sound.sampled.SourceDataLine
import kotlin.time.Duration

/**
 * JVM implementation for [AudioPlaybackSession].
 *
 * @param device The output device to play to.
 */
class JvmAudioPlaybackSession(private val device: AudioDevice.Output) : BaseAudioPlaybackSession() {

    private val isPaused = MutableStateFlow(false)

    private var dataLine: SourceDataLine? = null
    private val mp3Backend = JvmMp3PlaybackBackend(device)

    override suspend fun preparePlayback(format: AudioFormat): AudioFormat {
        val mixer = getMixer(device)
        val playbackFormat = format
            .takeIf { mixer.isSupported<SourceDataLine>(it) }
            ?: device.formatSupport.defaultFormat
        val line = mixer.getLine<SourceDataLine>(playbackFormat)
        line.open(playbackFormat)
        line.start()
        this.dataLine = line
        return playbackFormat
    }

    override suspend fun playBlocking(audioFlow: AudioFlow) {
        val line = dataLine ?: return
        audioFlow.collect { buffer ->
            isPaused.first { !it } // blocks until false
            line.write(buffer, 0, buffer.size)
        }
        line.drain()
        line.stop()
        line.close()
    }

    override suspend fun loadEncodedAudio(encodedAudio: EncodedAudio): Duration? =
        mp3Backend.load(encodedAudio)

    override suspend fun playEncodedAudioBlocking(encodedAudio: EncodedAudio, startPosition: Duration) {
        mp3Backend.playBlocking(startPosition)
    }

    override fun seekLoadedEncodedAudio(position: Duration) = Unit

    override fun onPause() {
        mp3Backend.pause()
        dataLine?.stop()
        isPaused.value = true
    }

    override fun onResume() {
        isPaused.value = false
        mp3Backend.resume()
        dataLine?.start()
    }

    override fun onStop() {
        mp3Backend.stop()
        isPaused.value = false
        dataLine?.stop()
        dataLine?.flush()
        dataLine?.close()
        dataLine = null
    }

    override fun releaseLoadedEncodedAudio() {
        mp3Backend.release()
    }
}
