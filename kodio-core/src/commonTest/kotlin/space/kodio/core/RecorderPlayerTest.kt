package space.kodio.core

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import space.kodio.core.io.files.AudioFileFormat
import space.kodio.core.io.files.EncodedAudio
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tests for [Recorder] and [Player] classes.
 */
class RecorderPlayerTest {

    // ==================== Recorder Tests ====================

    @Test
    fun `Recorder start changes isRecording to true`() = runTest {
        val session = FakeRecordingSession()
        val recorder = Recorder(session, AudioQuality.Standard)

        assertFalse(recorder.isRecording)
        recorder.start()
        assertTrue(recorder.isRecording)
    }

    @Test
    fun `Recorder stop changes isRecording to false`() = runTest {
        val session = FakeRecordingSession()
        val recorder = Recorder(session, AudioQuality.Standard)

        recorder.start()
        assertTrue(recorder.isRecording)
        
        recorder.stop()
        assertFalse(recorder.isRecording)
    }

    @Test
    fun `Recorder toggle starts when idle`() = runTest {
        val session = FakeRecordingSession()
        val recorder = Recorder(session, AudioQuality.Standard)

        val result = recorder.toggle()
        assertTrue(result)
        assertTrue(recorder.isRecording)
    }

    @Test
    fun `Recorder toggle stops when recording`() = runTest {
        val session = FakeRecordingSession()
        val recorder = Recorder(session, AudioQuality.Standard)

        recorder.start()
        val result = recorder.toggle()
        assertFalse(result)
        assertFalse(recorder.isRecording)
    }

    @Test
    fun `Recorder quality property reflects construction parameter`() {
        val session = FakeRecordingSession()
        val recorder = Recorder(session, AudioQuality.Lossless)

        assertEquals(AudioQuality.Lossless, recorder.quality)
    }

    @Test
    fun `Recorder getRecording returns recording after stop`() = runTest {
        val testData = byteArrayOf(1, 2, 3, 4)
        val session = FakeRecordingSession(testData = testData)
        val recorder = Recorder(session, AudioQuality.Standard)

        recorder.start()
        recorder.stop()
        
        val recording = recorder.getRecording()
        assertNotNull(recording)
        assertTrue(recording.sizeInBytes > 0)
    }

    @Test
    fun `Recorder getRecording returns null while recording`() = runTest {
        val session = FakeRecordingSession()
        val recorder = Recorder(session, AudioQuality.Standard)

        recorder.start()
        val recording = recorder.getRecording()
        assertNull(recording)
    }

    @Test
    fun `Recorder getRecording caches result`() = runTest {
        val testData = byteArrayOf(1, 2, 3, 4)
        val session = FakeRecordingSession(testData = testData)
        val recorder = Recorder(session, AudioQuality.Standard)

        recorder.start()
        recorder.stop()
        
        val recording1 = recorder.getRecording()
        val recording2 = recorder.getRecording()
        
        // Should be the same instance
        assertTrue(recording1 === recording2)
    }

    @Test
    fun `Recorder reset clears state`() = runTest {
        val session = FakeRecordingSession()
        val recorder = Recorder(session, AudioQuality.Standard)

        recorder.start()
        recorder.stop()
        recorder.reset()

        assertFalse(recorder.isRecording)
        assertEquals(AudioSessionState.Idle, recorder.sessionState)
    }

    @Test
    fun `Recorder start after stop without reset throws to prevent silent data loss`() = runTest {
        // Reproduces GitHub issue #24 — calling start() on a stopped recorder must
        // not silently drop the previous recording. Users must call reset() first
        // (or use AudioRecording.concat() to stitch multiple segments).
        val session = FakeRecordingSession()
        val recorder = Recorder(session, AudioQuality.Standard)

        recorder.start()
        recorder.stop()

        val ex = assertFailsWith<IllegalStateException> { recorder.start() }
        assertTrue(ex.message!!.contains("reset()"))
        assertTrue(ex.message!!.contains("AudioRecording.concat"))
    }

    @Test
    fun `Recorder start works again after explicit reset`() = runTest {
        val session = FakeRecordingSession()
        val recorder = Recorder(session, AudioQuality.Standard)

        recorder.start()
        recorder.stop()
        recorder.reset()
        recorder.start()

        assertTrue(recorder.isRecording)
    }

    @Test
    fun `Recorder pause transitions to Paused state and resume returns to Recording`() = runTest {
        val session = FakeRecordingSession()
        val recorder = Recorder(session, AudioQuality.Standard)

        recorder.start()
        assertTrue(recorder.isRecording)
        assertFalse(recorder.isPaused)

        recorder.pause()
        assertTrue(session.pauseCalled)
        assertFalse(recorder.isRecording)
        assertTrue(recorder.isPaused)

        recorder.resume()
        assertTrue(session.resumeCalled)
        assertTrue(recorder.isRecording)
        assertFalse(recorder.isPaused)
    }

    @Test
    fun `Recorder pause is a no-op when not recording`() = runTest {
        val session = FakeRecordingSession()
        val recorder = Recorder(session, AudioQuality.Standard)

        recorder.pause()
        assertFalse(session.pauseCalled)
        assertFalse(recorder.isPaused)
    }

    @Test
    fun `Recorder resume is a no-op when not paused`() = runTest {
        val session = FakeRecordingSession()
        val recorder = Recorder(session, AudioQuality.Standard)

        recorder.start()
        recorder.resume() // already recording, should do nothing
        assertFalse(session.resumeCalled)
        assertTrue(recorder.isRecording)
    }

    @Test
    fun `Recorder stop after pause finalises the recording`() = runTest {
        // Regression test: stop() while paused used to be a silent no-op,
        // forcing the user to resume() before stop() worked. See the chat
        // around 2026-04-25 — fixed in BaseAudioRecordingSession.stop().
        val session = FakeRecordingSession()
        val recorder = Recorder(session, AudioQuality.Standard)

        recorder.start()
        recorder.pause()
        assertTrue(recorder.isPaused)
        assertFalse(recorder.isRecording)

        recorder.stop()

        assertFalse(recorder.isRecording)
        assertFalse(recorder.isPaused)
        assertEquals(AudioSessionState.Complete, recorder.sessionState)
    }

    @Test
    fun `Recorder use extension calls release`() = runTest {
        val session = FakeRecordingSession()
        val recorder = Recorder(session, AudioQuality.Standard)

        recorder.use {
            it.start()
            it.stop()
        }

        // After use block, session should be reset
        assertTrue(session.resetCalled)
    }

    @Test
    fun `Recorder hasRecording is true after stop with data`() = runTest {
        val testData = byteArrayOf(1, 2, 3, 4)
        val session = FakeRecordingSession(testData = testData)
        val recorder = Recorder(session, AudioQuality.Standard)

        assertFalse(recorder.hasRecording)
        
        recorder.start()
        assertFalse(recorder.hasRecording)
        
        recorder.stop()
        assertTrue(recorder.hasRecording)
    }

    @Test
    fun `Recorder sessionState reflects session state`() = runTest {
        val session = FakeRecordingSession()
        val recorder = Recorder(session, AudioQuality.Standard)

        assertEquals(AudioSessionState.Idle, recorder.sessionState)
        
        recorder.start()
        assertEquals(AudioSessionState.Active, recorder.sessionState)
        
        recorder.stop()
        assertEquals(AudioSessionState.Complete, recorder.sessionState)
    }

    // ==================== Player Tests ====================

    @Test
    fun `Player isPlaying reflects state`() = runTest {
        val session = FakePlaybackSession()
        val player = Player(session)

        assertFalse(player.isPlaying)
        
        val recording = AudioRecording.fromBytes(
            AudioQuality.Standard.format,
            byteArrayOf(1, 2, 3, 4)
        )
        player.load(recording)
        player.start()
        
        assertTrue(player.isPlaying)
    }

    @Test
    fun `Player load sets audio flow and marks ready`() = runTest {
        val session = FakePlaybackSession()
        val player = Player(session)
        
        val recording = AudioRecording.fromBytes(
            AudioQuality.Standard.format,
            byteArrayOf(1, 2, 3, 4)
        )

        assertFalse(player.isReady)
        player.load(recording)
        assertTrue(player.isReady)
    }

    @Test
    fun `Player pause changes state to paused`() = runTest {
        val session = FakePlaybackSession()
        val player = Player(session)
        
        val recording = AudioRecording.fromBytes(
            AudioQuality.Standard.format,
            byteArrayOf(1, 2, 3, 4)
        )

        player.load(recording)
        player.start()
        player.pause()
        
        assertTrue(player.isPaused)
        assertFalse(player.isPlaying)
    }

    @Test
    fun `Player resume after pause`() = runTest {
        val session = FakePlaybackSession()
        val player = Player(session)
        
        val recording = AudioRecording.fromBytes(
            AudioQuality.Standard.format,
            byteArrayOf(1, 2, 3, 4)
        )

        player.load(recording)
        player.start()
        player.pause()
        player.resume()
        
        assertTrue(player.isPlaying)
        assertFalse(player.isPaused)
    }

    @Test
    fun `Player stop resets state`() = runTest {
        val session = FakePlaybackSession()
        val player = Player(session)
        
        val recording = AudioRecording.fromBytes(
            AudioQuality.Standard.format,
            byteArrayOf(1, 2, 3, 4)
        )

        player.load(recording)
        player.start()
        player.stop()
        
        assertFalse(player.isPlaying)
        assertFalse(player.isPaused)
    }

    @Test
    fun `Player toggle starts when ready`() = runTest {
        val session = FakePlaybackSession()
        val player = Player(session)
        
        val recording = AudioRecording.fromBytes(
            AudioQuality.Standard.format,
            byteArrayOf(1, 2, 3, 4)
        )

        player.load(recording)
        
        val result = player.toggle()
        assertEquals(true, result)
        assertTrue(player.isPlaying)
    }

    @Test
    fun `Player toggle pauses when playing`() = runTest {
        val session = FakePlaybackSession()
        val player = Player(session)
        
        val recording = AudioRecording.fromBytes(
            AudioQuality.Standard.format,
            byteArrayOf(1, 2, 3, 4)
        )

        player.load(recording)
        player.start()
        
        val result = player.toggle()
        assertEquals(false, result)
        assertTrue(player.isPaused)
    }

    @Test
    fun `Player toggle returns null when no audio loaded`() = runTest {
        val session = FakePlaybackSession()
        val player = Player(session)

        val result = player.toggle()
        assertNull(result)
    }

    @Test
    fun `Player use extension calls stop on cleanup`() = runTest {
        val session = FakePlaybackSession()
        val player = Player(session)

        player.use {
            it.load(AudioRecording.fromBytes(AudioQuality.Standard.format, byteArrayOf(1, 2)))
            it.start()
        }

        // Player.release() calls session.stop(), so state should be Idle
        assertTrue(session.stopCalled)
    }

    @Test
    fun `Player isReady includes Finished state`() = runTest {
        val session = FakePlaybackSession()
        val player = Player(session)
        
        val recording = AudioRecording.fromBytes(
            AudioQuality.Standard.format,
            byteArrayOf(1, 2, 3, 4)
        )

        player.load(recording)
        session.simulateFinished()
        
        assertTrue(player.isReady)
    }

    @Test
    fun `Player exposes seek metadata for loaded recordings`() = runTest {
        val session = FakePlaybackSession()
        val player = Player(session)
        val recording = AudioRecording.fromBytes(
            seekTestFormat,
            ByteArray(10)
        )

        player.load(recording)

        assertTrue(player.canSeek)
        assertEquals(10.milliseconds, player.duration)
        assertEquals(Duration.ZERO, player.position)
    }

    @Test
    fun `Player seekTo delegates to playback session`() = runTest {
        val session = FakePlaybackSession()
        val player = Player(session)
        val recording = AudioRecording.fromBytes(
            seekTestFormat,
            ByteArray(10)
        )

        player.load(recording)
        player.seekTo(4.milliseconds)

        assertEquals(4.milliseconds, session.seekPosition)
        assertEquals(4.milliseconds, player.position)
    }

    @Test
    fun `Player setPlaybackSpeed delegates to playback session`() = runTest {
        val session = FakePlaybackSession()
        val player = Player(session)

        player.setPlaybackSpeed(1.5f)

        assertEquals(1.5f, session.playbackSpeed.value)
        assertEquals(1.5f, player.playbackSpeed)
    }

    @Test
    fun `Base playback speed updates speed flow and backend`() = runTest {
        val session = CollectingPlaybackSession()

        session.setPlaybackSpeed(2.0f)

        assertEquals(2.0f, session.playbackSpeed.value)
        assertEquals(listOf(2.0f), session.playbackSpeeds)
    }

    @Test
    fun `Base playback speed rejects invalid values`() = runTest {
        val session = CollectingPlaybackSession()

        assertFailsWith<AudioError.InvalidPlaybackSpeed> {
            session.setPlaybackSpeed(0.0f)
        }
        assertFailsWith<AudioError.InvalidPlaybackSpeed> {
            session.setPlaybackSpeed(Float.NaN)
        }
    }

    @Test
    fun `Base playback starts from seeked recording position`() = runTest {
        val session = CollectingPlaybackSession()
        val recording = AudioRecording.fromBytes(
            seekTestFormat,
            byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        )

        session.load(recording)
        session.seekTo(4.milliseconds)
        session.play()
        session.state.first { it is AudioPlaybackSession.State.Finished }

        assertTrue(session.seekPositions.single() >= 4.milliseconds)
        assertContentEquals(byteArrayOf(4, 5, 6, 7, 8, 9), session.playedChunks.single())
    }

    @Test
    fun `Base playback restarts loaded recording from finished state`() = runTest {
        val session = CollectingPlaybackSession()
        val recording = AudioRecording.fromBytes(
            seekTestFormat,
            byteArrayOf(0, 1, 2, 3, 4)
        )

        session.load(recording)
        session.play()
        session.waitForPlayedChunkCount(1)
        session.state.first { it is AudioPlaybackSession.State.Finished }

        session.play()
        session.waitForPlayedChunkCount(2)
        session.state.first { it is AudioPlaybackSession.State.Finished }

        assertEquals(2, session.seekPositions.size)
        assertTrue(session.seekPositions.all { it < 1.milliseconds })
        assertEquals(2, session.playedChunks.size)
        session.playedChunks.forEach {
            assertContentEquals(byteArrayOf(0, 1, 2, 3, 4), it)
        }
    }

    @Test
    fun `Base seek clamps positions past recording duration`() = runTest {
        val session = CollectingPlaybackSession()
        val recording = AudioRecording.fromBytes(
            seekTestFormat,
            ByteArray(10)
        )

        session.load(recording)
        session.seekTo(50.milliseconds)

        assertEquals(10.milliseconds, session.position.value)
        assertEquals(AudioPlaybackSession.State.Finished, session.state.value)
    }

    @Test
    fun `Base seek rejects streaming audio flows`() = runTest {
        val session = CollectingPlaybackSession()

        session.load(AudioFlow(seekTestFormat, flowOf(ByteArray(10))))

        assertFailsWith<AudioError.SeekUnsupported> {
            session.seekTo(1.milliseconds)
        }
        assertFalse(session.canSeek.value)
    }

    @Test
    fun `Base encoded stop keeps source loaded for replay until replacement`() = runTest {
        val session = CollectingPlaybackSession()
        val encoded = EncodedAudio.fromBytes(byteArrayOf(1, 2, 3), AudioFileFormat.Mp3, "clip.mp3")

        session.load(encoded)
        session.play()
        session.waitForEncodedPlayCount(1)
        session.stop()

        assertTrue(session.encodedAudio.value === encoded)
        assertEquals(1, session.encodedStopCount)
        assertEquals(0, session.encodedReleaseCount)

        session.play()
        session.waitForEncodedPlayCount(2)
        session.stop()

        session.load(AudioFlow(seekTestFormat, flowOf(ByteArray(1))))

        assertEquals(2, session.encodedStopCount)
        assertEquals(1, session.encodedReleaseCount)
    }

    @Test
    fun `Base release frees loaded encoded backend`() = runTest {
        val session = CollectingPlaybackSession()
        val encoded = EncodedAudio.fromBytes(byteArrayOf(1, 2, 3), AudioFileFormat.Mp3, "clip.mp3")

        session.load(encoded)
        session.release()

        assertNull(session.encodedAudio.value)
        assertEquals(1, session.encodedReleaseCount)
        assertEquals(AudioPlaybackSession.State.Idle, session.state.value)
    }

    @Test
    fun `Base failed encoded load does not publish failed source`() = runTest {
        val session = CollectingPlaybackSession()
        val recording = AudioRecording.fromBytes(seekTestFormat, byteArrayOf(1, 2, 3, 4))
        val encoded = EncodedAudio.fromBytes(byteArrayOf(1, 2, 3), AudioFileFormat.Mp3, "clip.mp3")

        session.load(recording)
        session.failEncodedLoad = true

        assertFailsWith<IllegalStateException> {
            session.load(encoded)
        }

        assertNull(session.encodedAudio.value)
        assertNotNull(session.audioFlow.value)
        assertTrue(session.state.value is AudioPlaybackSession.State.Error)
    }

    // ==================== Fake Implementations ====================

    private val seekTestFormat = AudioFormat(
        sampleRate = 1000,
        channels = Channels.Mono,
        encoding = SampleEncoding.PcmInt(IntBitDepth.Eight)
    )

    private class FakeRecordingSession(
        private val testData: ByteArray = byteArrayOf(1, 2, 3, 4)
    ) : AudioRecordingSession {
        private val _state = MutableStateFlow<AudioRecordingSession.State>(AudioRecordingSession.State.Idle)
        override val state: StateFlow<AudioRecordingSession.State> = _state

        private val _audioFlow = MutableStateFlow<AudioFlow?>(null)
        override val audioFlow: StateFlow<AudioFlow?> = _audioFlow

        var resetCalled = false
        var pauseCalled = false
        var resumeCalled = false
        private val format = AudioQuality.Standard.format

        override suspend fun start() {
            _state.value = AudioRecordingSession.State.Recording
            _audioFlow.value = AudioFlow(format, flowOf(testData))
        }

        override suspend fun pause() {
            pauseCalled = true
            _state.value = AudioRecordingSession.State.Paused
        }

        override suspend fun resume() {
            resumeCalled = true
            _state.value = AudioRecordingSession.State.Recording
        }

        override fun stop() {
            _state.value = AudioRecordingSession.State.Stopped
        }

        override fun reset() {
            resetCalled = true
            _state.value = AudioRecordingSession.State.Idle
            _audioFlow.value = null
        }
    }

    private class FakePlaybackSession : AudioPlaybackSession {
        private val _state = MutableStateFlow<AudioPlaybackSession.State>(AudioPlaybackSession.State.Idle)
        override val state: StateFlow<AudioPlaybackSession.State> = _state
        
        private val _audioFlow = MutableStateFlow<AudioFlow?>(null)
        override val audioFlow: StateFlow<AudioFlow?> = _audioFlow

        private val _encodedAudio = MutableStateFlow<EncodedAudio?>(null)
        override val encodedAudio: StateFlow<EncodedAudio?> = _encodedAudio

        private val _position = MutableStateFlow(Duration.ZERO)
        override val position: StateFlow<Duration> = _position

        private val _duration = MutableStateFlow<Duration?>(null)
        override val duration: StateFlow<Duration?> = _duration

        private val _canSeek = MutableStateFlow(false)
        override val canSeek: StateFlow<Boolean> = _canSeek

        private val _playbackSpeed = MutableStateFlow(AudioPlaybackSession.DEFAULT_PLAYBACK_SPEED)
        override val playbackSpeed: StateFlow<Float> = _playbackSpeed

        var stopCalled = false
        var seekPosition: Duration? = null

        override suspend fun load(audioFlow: AudioFlow) {
            _audioFlow.value = audioFlow
            _encodedAudio.value = null
            _position.value = Duration.ZERO
            _duration.value = null
            _canSeek.value = false
            _state.value = AudioPlaybackSession.State.Ready
        }

        override suspend fun load(recording: AudioRecording) {
            _audioFlow.value = recording.asAudioFlow()
            _encodedAudio.value = null
            _position.value = Duration.ZERO
            _duration.value = recording.calculatedDuration
            _canSeek.value = true
            _state.value = AudioPlaybackSession.State.Ready
        }

        override suspend fun load(encodedAudio: EncodedAudio) {
            _audioFlow.value = null
            _encodedAudio.value = encodedAudio
            _position.value = Duration.ZERO
            _duration.value = null
            _canSeek.value = true
            _state.value = AudioPlaybackSession.State.Ready
        }

        override suspend fun play() {
            _state.value = AudioPlaybackSession.State.Playing
        }

        override suspend fun seekTo(position: Duration) {
            if (!_canSeek.value) throw AudioError.SeekUnsupported()
            seekPosition = position
            _position.value = position
        }

        override fun setPlaybackSpeed(speed: Float) {
            _playbackSpeed.value = speed
        }

        override fun pause() {
            _state.value = AudioPlaybackSession.State.Paused
        }

        override fun resume() {
            _state.value = AudioPlaybackSession.State.Playing
        }

        override fun stop() {
            stopCalled = true
            _state.value = AudioPlaybackSession.State.Idle
        }

        fun simulateFinished() {
            _state.value = AudioPlaybackSession.State.Finished
        }
    }

    private class CollectingPlaybackSession : BaseAudioPlaybackSession() {
        val playedChunks = mutableListOf<ByteArray>()
        val seekPositions = mutableListOf<Duration>()
        val encodedStartPositions = mutableListOf<Duration>()
        val playbackSpeeds = mutableListOf<Float>()
        var encodedStopCount = 0
        var encodedReleaseCount = 0
        var failEncodedLoad = false

        override suspend fun preparePlayback(format: AudioFormat): AudioFormat = format

        override suspend fun playBlocking(audioFlow: AudioFlow) {
            seekPositions += position.value
            audioFlow.collect { playedChunks += it }
        }

        override suspend fun loadEncodedAudio(encodedAudio: EncodedAudio): Duration? {
            check(!failEncodedLoad) { "encoded load failed" }
            return 10.milliseconds
        }

        override suspend fun playEncodedAudioBlocking(encodedAudio: EncodedAudio, startPosition: Duration) {
            encodedStartPositions += startPosition
            awaitCancellation()
        }

        override fun onPlaybackSpeedChanged(speed: Float) {
            playbackSpeeds += speed
        }

        override fun onPause() = Unit

        override fun onResume() = Unit

        override fun onStop() {
            encodedStopCount++
        }

        override fun releaseLoadedEncodedAudio() {
            encodedReleaseCount++
        }

        suspend fun waitForEncodedPlayCount(count: Int) {
            repeat(100) {
                if (encodedStartPositions.size >= count) return
                delay(10.milliseconds)
            }
            error("Timed out waiting for $count encoded playback starts")
        }

        suspend fun waitForPlayedChunkCount(count: Int) {
            repeat(100) {
                if (playedChunks.size >= count) return
                delay(10.milliseconds)
            }
            error("Timed out waiting for $count played chunks")
        }
    }
}
