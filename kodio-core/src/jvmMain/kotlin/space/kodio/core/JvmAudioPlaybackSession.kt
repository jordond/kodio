package space.kodio.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import space.kodio.core.io.files.EncodedAudio
import javax.sound.sampled.SourceDataLine
import kotlin.math.roundToInt
import kotlin.time.Duration

/**
 * JVM implementation for [AudioPlaybackSession].
 *
 * @param device The output device to play to.
 */
class JvmAudioPlaybackSession(private val device: AudioDevice.Output) : BaseAudioPlaybackSession() {

    private val isPaused = MutableStateFlow(false)

    private var dataLine: SourceDataLine? = null
    private lateinit var preparedPlaybackFormat: AudioFormat
    private var appliedLineSpeed: Float? = null
    private val mp3Backend = JvmMp3PlaybackBackend(device)

    override suspend fun preparePlayback(format: AudioFormat): AudioFormat {
        val mixer = getMixer(device)
        val playbackFormat = format
            .takeIf { mixer.isSupported<SourceDataLine>(it) }
            ?: device.formatSupport.defaultFormat
        preparedPlaybackFormat = playbackFormat
        openLineForSpeed(playbackFormat, playbackSpeed.value).close()
        appliedLineSpeed = null
        return playbackFormat
    }

    private fun openLineForSpeed(format: AudioFormat, speed: Float): SourceDataLine {
        val mixer = getMixer(device)
        val requestedFormat = format.copy(
            sampleRate = (format.sampleRate * speed).roundToInt().coerceAtLeast(1)
        )
        val playbackFormat = requestedFormat
            .takeIf { mixer.isSupported<SourceDataLine>(it) }
            ?: format
        val line = mixer.getLine<SourceDataLine>(playbackFormat)
        line.open(playbackFormat)
        line.start()
        return line
    }

    override suspend fun playBlocking(audioFlow: AudioFlow) {
        audioFlow.collect { buffer ->
            isPaused.first { !it } // blocks until false
            val speed = playbackSpeed.value
            val line = if (dataLine == null || appliedLineSpeed != speed) {
                dataLine?.drain()
                dataLine?.stop()
                dataLine?.close()
                openLineForSpeed(preparedPlaybackFormat, speed).also {
                    dataLine = it
                    appliedLineSpeed = speed
                }
            } else {
                dataLine ?: return@collect
            }
            line.write(buffer, 0, buffer.size)
        }
        dataLine?.drain()
        dataLine?.stop()
        dataLine?.close()
        dataLine = null
        appliedLineSpeed = null
    }

    override suspend fun loadEncodedAudio(encodedAudio: EncodedAudio): Duration? =
        mp3Backend.load(encodedAudio)

    override suspend fun playEncodedAudioBlocking(encodedAudio: EncodedAudio, startPosition: Duration) {
        mp3Backend.playBlocking(startPosition)
    }

    override fun seekLoadedEncodedAudio(position: Duration) = Unit

    override fun onPlaybackSpeedChanged(speed: Float) {
        mp3Backend.setPlaybackSpeed(speed)
    }

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
        appliedLineSpeed = null
    }

    override fun releaseLoadedEncodedAudio() {
        mp3Backend.release()
    }
}
