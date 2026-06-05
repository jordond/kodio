package space.kodio.core

import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import space.kodio.core.io.files.AudioFileFormat
import space.kodio.core.io.files.AudioFileReadError
import space.kodio.core.io.files.EncodedAudio
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.roundToLong
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import javax.sound.sampled.AudioFormat as JavaSoundAudioFormat
import javax.sound.sampled.AudioSystem as JavaSoundAudioSystem

internal class JvmMp3PlaybackBackend(
    private val device: AudioDevice.Output?,
) {
    private val paused = MutableStateFlow(false)
    private val stopRequested = AtomicBoolean(false)

    private var encodedAudio: EncodedAudio? = null
    private var line: SourceDataLine? = null
    private var playbackSpeed: Float = AudioPlaybackSession.DEFAULT_PLAYBACK_SPEED

    fun load(encodedAudio: EncodedAudio): kotlin.time.Duration? {
        if (encodedAudio.fileFormat !is AudioFileFormat.Mp3) {
            throw AudioFileReadError.UnsupportedFormat(
                "Direct playback is only implemented for MP3 on JVM targets."
            )
        }
        val bytes = encodedAudio.toByteArray()
        val duration = calculateDurationMillis(bytes).roundToLong().milliseconds
        this.encodedAudio = encodedAudio
        return duration
    }

    suspend fun playBlocking(startPosition: kotlin.time.Duration) {
        val audio = encodedAudio ?: return
        stopRequested.set(false)
        paused.value = false

        val startMillis = startPosition.inWholeMilliseconds.toDouble()
        var elapsedMillis = 0.0
        val bitstream = Bitstream(ByteArrayInputStream(audio.toByteArray()))
        val decoder = Decoder()

        try {
            while (!stopRequested.get()) {
                val header = bitstream.readFrame() ?: break
                try {
                    val output = decoder.decodeFrame(header, bitstream) as SampleBuffer
                    val frameMillis = header.ms_per_frame().toDouble()

                    if (elapsedMillis >= startMillis) {
                        val playbackLine = line ?: openLine(output).also { line = it }
                        paused.first { !it || stopRequested.get() }
                        if (stopRequested.get()) break
                        playbackLine.write(output.toLittleEndianPcm16Bytes(), 0, output.bufferLength * 2)
                    }

                    elapsedMillis += frameMillis
                } finally {
                    bitstream.closeFrame()
                }
            }

            if (!stopRequested.get()) {
                line?.drain()
            }
        } finally {
            bitstream.close()
            closeLine()
        }
    }

    fun pause() {
        paused.value = true
        line?.stop()
    }

    fun resume() {
        paused.value = false
        line?.start()
    }

    fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed
    }

    fun stop() {
        stopRequested.set(true)
        paused.value = false
        line?.stop()
        line?.flush()
        closeLine()
    }

    fun release() {
        stop()
        encodedAudio = null
    }

    private fun openLine(output: SampleBuffer): SourceDataLine {
        val format = JavaSoundAudioFormat(
            (output.sampleFrequency * playbackSpeed).roundToInt().coerceAtLeast(1).toFloat(),
            16,
            output.channelCount,
            true,
            false,
        )
        val dataLine = if (device != null) {
            getMixer(device).getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
        } else {
            JavaSoundAudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
        }
        dataLine.open(format)
        dataLine.start()
        return dataLine
    }

    private fun closeLine() {
        line?.close()
        line = null
    }
}

private fun calculateDurationMillis(bytes: ByteArray): Double {
    var totalMillis = 0.0
    val bitstream = Bitstream(ByteArrayInputStream(bytes))
    try {
        while (true) {
            val header = bitstream.readFrame() ?: break
            try {
                totalMillis += header.ms_per_frame().toDouble()
            } finally {
                bitstream.closeFrame()
            }
        }
    } finally {
        bitstream.close()
    }
    return totalMillis
}

private fun SampleBuffer.toLittleEndianPcm16Bytes(): ByteArray {
    val samples = buffer
    val bytes = ByteArray(bufferLength * 2)
    var byteIndex = 0
    for (i in 0 until bufferLength) {
        val sample = samples[i].toInt()
        bytes[byteIndex++] = sample.toByte()
        bytes[byteIndex++] = (sample ushr 8).toByte()
    }
    return bytes
}
