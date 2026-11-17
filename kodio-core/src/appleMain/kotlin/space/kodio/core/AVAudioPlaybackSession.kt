package space.kodio.core

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.write
import platform.AVFAudio.AVAudioConverter
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioEngineConfigurationChangeNotification
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioMixerNode
import platform.AVFAudio.AVAudioPCMFormatFloat32
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioUnitVarispeed
import platform.Foundation.NSError
import platform.Foundation.NSException as RaisedNSException
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSURL
import platform.darwin.NSObjectProtocol
import space.kodio.core.objc.KodioEngineAttachNode
import space.kodio.core.objc.KodioEngineConnect
import space.kodio.core.objc.KodioEngineDisconnectNodeOutput
import space.kodio.core.objc.KodioEngineMainMixerNode
import space.kodio.core.objc.KodioEnginePrepare
import space.kodio.core.objc.KodioEngineStart
import space.kodio.core.objc.KodioEngineStop
import space.kodio.core.objc.KodioNodeOutputFormat
import space.kodio.core.objc.KodioPlayerNodePause
import space.kodio.core.objc.KodioPlayerNodePlay
import space.kodio.core.objc.KodioPlayerNodeScheduleBuffer
import space.kodio.core.objc.KodioPlayerNodeStop
import space.kodio.core.io.files.AudioFileFormat
import space.kodio.core.io.files.AudioFileReadError
import space.kodio.core.io.files.EncodedAudio
import space.kodio.core.io.convertSimple
import space.kodio.core.io.toIosAudioBuffer
import space.kodio.core.util.namedLogger
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference
import kotlin.native.ref.createCleaner
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val log = namedLogger("AVAudioPlayback")

/**
 * Playback through an `AVAudioEngine`.
 *
 * AVFAudio raises an `NSException` when a call finds the engine in a state it does not
 * accept: the player node raises "player did not see an IO cycle." when the engine's IO
 * unit is not rendering, and "player started when engine not running" after a stop, an
 * interruption or a configuration change stopped the engine. Kotlin cannot catch an
 * `NSException`, so every raising call goes through the ObjC shim (`KodioAVFAudio.def`)
 * and comes back as an [AVAudioRaisedException]. A sound that cannot start ends in
 * [AudioPlaybackSession.State.Error], and the engine is left stopped so that the next
 * play builds it afresh.
 *
 * The caller's thread (stop, pause, resume, seek), the playback job and the notifications
 * all reach the engine and the player node, so every use of them holds [engineLock].
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
abstract class AVAudioPlaybackSession(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : BaseAudioPlaybackSession(dispatcher) {

    internal val engine = AVAudioEngine()
    internal val player = AVAudioPlayerNode()
    private val varispeed = AVAudioUnitVarispeed()
    private lateinit var standardAVFormat: AVAudioFormat
    private lateinit var interleavedAVFormat: AVAudioFormat
    private var deinterleaveConverter: AVAudioConverter? = null
    private var encodedPlayer: AVAudioPlayer? = null
    private var encodedTempPath: Path? = null

    /** Held around every use of the engine and the player node. Never held across a suspension. */
    private val engineLock = NSRecursiveLock()

    /**
     * Identifies the [playBlocking] call that owns the player node. Bumped by each
     * [playBlocking] and by [onStop]: a buffer of an older call is neither scheduled nor played.
     */
    private var nodeGeneration = 0L

    /** Set by [onPause], cleared by [onResume] and [onStop]: the node is not played while set. */
    private var paused = false

    /** The current [playBlocking] call has scheduled a buffer, so the node has something to play. */
    private var scheduled = false

    /** Fails the play that is in flight, when the engine could not be brought back under it. */
    private var playbackInFlight: CompletableDeferred<Nothing>? = null

    private val observers = NotificationObservers()

    @OptIn(ExperimentalNativeApi::class)
    @Suppress("unused") // Held so the observers are removed when the session is collected.
    private val observersCleaner = createCleaner(observers, NotificationObservers::removeAll)

    init {
        KodioEngineAttachNode(engine, player).throwIfRaised()
        KodioEngineAttachNode(engine, varispeed).throwIfRaised()
        log.info { "Attached player node to engine" }
        // The engine stops itself when the hardware's sample rate or channel count changes.
        observeNotification(AVAudioEngineConfigurationChangeNotification, engine) {
            recoverEngine("the engine's configuration changed")
        }
    }

    /**
     * Calls [onNotified] on this session for each [name] notification that [sender] posts.
     * The observer holds the session weakly and is removed when the session is collected,
     * so [onNotified] must reach the session through its receiver and capture nothing of it.
     */
    @OptIn(ExperimentalNativeApi::class)
    internal fun observeNotification(
        name: String?,
        sender: Any?,
        onNotified: AVAudioPlaybackSession.(NSNotification) -> Unit,
    ) {
        val session = WeakReference(this)
        observers.add(name, sender) { notification -> session.get()?.onNotified(notification) }
    }

    /**
     * Brings the engine back after the system stopped it under a playing sound (a
     * configuration change). The node is stopped, which ends what was scheduled on it, and
     * played again on the restarted engine; the play fails if they cannot be started. A
     * paused sound is left for [onResume], and with no play in flight the next play finds
     * the engine stopped and builds it afresh.
     */
    internal fun recoverEngine(cause: String) {
        // Off the notification's thread, which AVFAudio posts from inside the engine.
        scope.launch {
            engineLock.withLock {
                // The play whose engine was lost: a failure here fails it, not one started since.
                val inFlight = playbackInFlight
                if (inFlight == null || paused || !scheduled) return@withLock
                log.info { "Restarting the engine under the playing sound: $cause" }
                runCatching { startNode(engineWasStopped = true) }.onFailure { failure ->
                    log.error(failure) { "The engine did not come back: ${failure.message}" }
                    inFlight.completeExceptionally(failure)
                }
            }
        }
    }

    /** The session's pause, resume and stop take [engineLock], as the notifications do. */
    override fun <T> serialized(block: () -> T): T = engineLock.withLock(block)

    /** A seam for tests, called under the lock between the engine check and the node's play. */
    internal open fun onBeforeNodeStart() = Unit

    /** A seam for tests, called under the lock before each buffer is scheduled. */
    internal open fun onBeforeSchedule() = Unit

    /** A seam for tests, called under the lock before the loaded MP3 plays. */
    internal open fun onBeforeEncodedPlay() = Unit

    /**
     * Starts the player node, on a running engine. A node that does not start is answered
     * once by restarting the engine: the node raises on an engine that says it is running
     * while its IO unit is not ("player did not see an IO cycle."), and takes a play
     * without starting on an engine that stopped after the check.
     *
     * @param engineWasStopped the system stopped the engine since the node last played.
     * @throws AVAudioRaisedException if the node raised both times.
     * @throws AVAudioEngineException if the engine or the node cannot be started.
     */
    private fun startNode(engineWasStopped: Boolean = false) = engineLock.withLock {
        var nodeIsStale = engineWasStopped
        if (!engine.isRunning()) {
            log.info { "Engine is not running before play, starting it" }
            startEngine()
            nodeIsStale = true
        }
        onBeforeNodeStart()
        try {
            playNode(nodeIsStale)
        } catch (failure: Exception) {
            log.warn(failure) { "Player node did not start, restarting the engine once: ${failure.message}" }
            KodioEngineStop(engine).throwIfRaised()
            startEngine()
            onBeforeNodeStart()
            playNode(nodeIsStale = true)
        }
    }

    /**
     * @param nodeIsStale the engine stopped under the node. Such a node may still say it is
     * playing, and a play alone does not start it: it has to be stopped first. The stop ends
     * what was scheduled (each buffer's completion runs), so no wait for a buffer is left
     * without an end. What was scheduled is dropped, not played: after an interruption or a
     * configuration change the sound goes on only with the buffers scheduled after this, and
     * a sound that was scheduled whole ends [AudioPlaybackSession.State.Finished].
     */
    private fun playNode(nodeIsStale: Boolean) {
        if (nodeIsStale) KodioPlayerNodeStop(player).throwIfRaised()
        KodioPlayerNodePlay(player).throwIfRaised()
        if (!player.isPlaying() || !engine.isRunning()) throw AVAudioEngineException.PlayerNodeNotStarted()
    }

    private fun startEngine() {
        var failed = false
        var error: NSError? = null
        KodioEngineStart(engine) {
            failed = true
            error = it
        }.throwIfRaised()
        if (failed) {
            val description = error?.localizedDescription ?: "Unknown error"
            log.error { "Engine failed to start: $description" }
            throw AVAudioEngineException.FailedToStart(description)
        }
    }

    /**
     * Starts an engine the system stopped, leaving the node stopped: the stop ends what was
     * scheduled on it, and the next scheduled buffer plays it again.
     */
    private fun restartEngine() {
        log.info { "Engine is not running under the playing sound, restarting it" }
        KodioPlayerNodeStop(player).throwIfRaised()
        startEngine()
    }

    /** Stops the node and the engine after a failed play, so the next play connects and starts them afresh. */
    private fun stopEngineAfterFailure() = engineLock.withLock {
        attempt("stop the player node") { KodioPlayerNodeStop(player) }
        attempt("stop the engine") { KodioEngineStop(engine) }
    }

    /** Runs a shim call whose failure must not stop what follows it. */
    private inline fun attempt(what: String, call: () -> RaisedNSException?) {
        call()?.let { log.error { "Failed to $what: ${it.name}: ${it.reason}" } }
    }

    abstract fun configureAudioSession()

    override suspend fun preparePlayback(format: AudioFormat): AudioFormat {
        log.info { "preparePlayback() called with format: $format" }

        val playbackFormat = toNativePlaybackFormat(format)
        engineLock.withLock { paused = false }
        if (engineLock.withLock { engine.isRunning() } && ::standardAVFormat.isInitialized &&
            standardAVFormat.sampleRate == playbackFormat.sampleRate.toDouble() &&
            standardAVFormat.channelCount == playbackFormat.channels.count.toUInt()
        ) {
            log.info {
                "preparePlayback(): engine already running with matching format, skipping setup"
            }
            return playbackFormat
        }
        if (playbackFormat != format) {
            log.info { "Format not natively supported, will convert to: $playbackFormat" }
        }

        // Interleaved format matching what the conversion pipeline produces
        interleavedAVFormat = AVAudioFormat(
            commonFormat = AVAudioPCMFormatFloat32,
            sampleRate = playbackFormat.sampleRate.toDouble(),
            channels = playbackFormat.channels.count.toUInt(),
            interleaved = true
        )

        // AVAudioEngine on iOS requires the standard (non-interleaved Float32) format
        standardAVFormat = AVAudioFormat(
            standardFormatWithSampleRate = playbackFormat.sampleRate.toDouble(),
            channels = playbackFormat.channels.count.toUInt()
        )
        log.info {
            "Standard AVAudioFormat: sampleRate=${standardAVFormat.sampleRate}, " +
                "channels=${standardAVFormat.channelCount}, commonFormat=${standardAVFormat.commonFormat}, " +
                "interleaved=${standardAVFormat.isInterleaved()}, isStandard=${standardAVFormat.isStandard()}"
        }

        deinterleaveConverter = if (playbackFormat.channels.count > 1) {
            AVAudioConverter(fromFormat = interleavedAVFormat, toFormat = standardAVFormat).also {
                log.info { "Created AVAudioConverter for interleaved -> non-interleaved conversion" }
            }
        } else null

        engineLock.withLock {
            // The mixer raises when the output device offers no usable format.
            var mixer: AVAudioMixerNode? = null
            KodioEngineMainMixerNode(engine) { mixer = it }.throwIfRaised()
            val mainMixerNode = mixer
                ?: throw AVAudioEngineException.FailedToStart("The engine has no main mixer node")
            var mainMixerOutputFormat: AVAudioFormat? = null
            KodioNodeOutputFormat(mainMixerNode, 0u) { mainMixerOutputFormat = it }.throwIfRaised()
            log.info {
                "mainMixerNode outputFormatForBus(0): sampleRate=${mainMixerOutputFormat?.sampleRate}, " +
                    "channels=${mainMixerOutputFormat?.channelCount}, " +
                    "commonFormat=${mainMixerOutputFormat?.commonFormat}"
            }

            log.info { "Connecting player -> varispeed -> mainMixerNode with standard avFormat" }
            KodioEngineConnect(engine, player, varispeed, standardAVFormat).throwIfRaised()
            KodioEngineConnect(engine, varispeed, mainMixerNode, standardAVFormat).throwIfRaised()
            varispeed.rate = playbackSpeed.value

            log.info { "Configuring audio session" }
            configureAudioSession()

            log.info { "Preparing engine" }
            KodioEnginePrepare(engine).throwIfRaised()

            log.info { "Starting engine" }
            startEngine()
            log.info { "Engine started successfully" }
        }
        return playbackFormat
    }

    /**
     * Always normalize to interleaved Float32 for the conversion pipeline.
     * The actual AVAudioEngine connection uses the standard (non-interleaved)
     * format; an AVAudioConverter handles the deinterleaving per-buffer.
     */
    private fun toNativePlaybackFormat(format: AudioFormat): AudioFormat {
        val enc = format.encoding
        if (enc is SampleEncoding.PcmFloat && enc.precision == FloatPrecision.F32) return format
        return AudioFormat(
            sampleRate = format.sampleRate,
            channels = format.channels,
            encoding = SampleEncoding.PcmFloat(FloatPrecision.F32, SampleLayout.Interleaved)
        )
    }

    override suspend fun playBlocking(audioFlow: AudioFlow) {
        log.info { "playBlocking() called with format: ${audioFlow.format}" }
        val inFlight = CompletableDeferred<Nothing>()
        val generation = engineLock.withLock {
            scheduled = false
            playbackInFlight = inFlight
            ++nodeGeneration
        }
        try {
            coroutineScope {
                // Ends the scheduling and the wait below when the engine is lost under them.
                val engineLost = launch { inFlight.await() }
                scheduleBuffersAndAwait(audioFlow, generation)
                engineLost.cancel()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // Checked and stopped in one step: a stop or the next play takes the node in between.
            engineLock.withLock { if (nodeGeneration == generation) stopEngineAfterFailure() }
            throw failure
        } finally {
            engineLock.withLock {
                if (playbackInFlight === inFlight) playbackInFlight = null
            }
        }
    }

    private suspend fun scheduleBuffersAndAwait(audioFlow: AudioFlow, generation: Long) {
        val converter = deinterleaveConverter
        val bufferFormat = if (converter != null) interleavedAVFormat else standardAVFormat
        log.info {
            "Scheduling buffers: bufferFormat interleaved=${bufferFormat.isInterleaved()}, " +
                "converter=${converter != null}"
        }
        var bufferCount = 0
        val lastCompletable = audioFlow.map { bytes ->
            currentCoroutineContext().ensureActive()
            bufferCount++
            var iosAudioBuffer = bytes.toIosAudioBuffer(bufferFormat)
            if (converter != null) {
                iosAudioBuffer = converter.convertSimple(iosAudioBuffer, standardAVFormat)
            }
            if (bufferCount <= 3 || bufferCount % 50 == 0) {
                log.info { "Scheduling buffer #$bufferCount: ${bytes.size} bytes, ${iosAudioBuffer.frameLength} frames" }
            }
            val iosAudioBufferFinishedIndicator = CompletableDeferred<Unit>()
            engineLock.withLock {
                onBeforeSchedule()
                // A stop (or the next play) took the node: this call's buffers go nowhere.
                if (nodeGeneration != generation) throw CancellationException("The playback was stopped")
                // An engine the system stopped without a word would leave this buffer, and the
                // wait for it, without an end. A paused sound is left for onResume.
                if (!paused && !engine.isRunning()) restartEngine()
                KodioPlayerNodeScheduleBuffer(player, iosAudioBuffer) {
                    iosAudioBufferFinishedIndicator.complete(Unit)
                }.throwIfRaised()
                scheduled = true
                // play() follows the first scheduleBuffer: a node played with nothing
                // scheduled raises "player did not see an IO cycle.".
                if (!paused && !player.isPlaying()) {
                    log.debug { "Buffer scheduled on a stopped node, starting player" }
                    startNode()
                }
            }
            iosAudioBufferFinishedIndicator
        }.lastOrNull()
        log.info { "Awaiting last buffer (total scheduled: $bufferCount)" }
        lastCompletable?.await()
        log.info { "playBlocking() finished" }
    }

    override suspend fun loadEncodedAudio(encodedAudio: EncodedAudio): Duration? {
        if (encodedAudio.fileFormat !is AudioFileFormat.Mp3) {
            throw AudioFileReadError.UnsupportedFormat(
                "Direct playback is only implemented for MP3 on Apple targets."
            )
        }
        val bytes = encodedAudio.toByteArray()
        val path = writeEncodedTempFile(bytes, encodedAudio.fileFormat.extension)
        val url = NSURL.fileURLWithPath(path.toString())
        val player = runErrorCatching { errorVar ->
            AVAudioPlayer(contentsOfURL = url, error = errorVar)
        }.getOrElse {
            throw AudioFileReadError.InvalidFile("Unable to load MP3 data: ${it.message}", it)
        }
        configureAudioSession()
        if (!player.prepareToPlay()) {
            runCatching { SystemFileSystem.delete(path, mustExist = false) }
            throw AudioFileReadError.InvalidFile("Unable to prepare MP3 data for playback.")
        }
        player.enableRate = true
        player.rate = playbackSpeed.value
        releaseLoadedEncodedAudio()
        encodedPlayer = player
        encodedTempPath = path
        return player.duration.seconds
    }

    override suspend fun playEncodedAudioBlocking(encodedAudio: EncodedAudio, startPosition: Duration) {
        val player = encodedPlayer ?: return
        val job = currentCoroutineContext().job
        engineLock.withLock {
            onBeforeEncodedPlay()
            // A stop that held the lock while this job waited for it cancelled the job and
            // stopped the player: playing now would run the MP3 to its end on an idle session.
            if (!job.isActive) throw CancellationException("The playback was stopped")
            player.currentTime = startPosition.inWholeMilliseconds / 1000.0
            // A pause that came before this job ran leaves the sound to onResume.
            if (!paused && !player.play()) {
                throw AudioFileReadError.InvalidFile("Unable to start MP3 playback.")
            }
        }
        while (player.isPlaying() || state.value is AudioPlaybackSession.State.Paused) {
            delay(50.milliseconds)
        }
    }

    /** The loaded MP3 is playing, for tests. */
    internal val isEncodedPlaying: Boolean get() = encodedPlayer?.isPlaying() == true

    override fun seekLoadedEncodedAudio(position: Duration) {
        encodedPlayer?.currentTime = position.inWholeMilliseconds / 1000.0
    }

    override fun onPlaybackSpeedChanged(speed: Float) {
        varispeed.rate = speed
        encodedPlayer?.let {
            it.enableRate = true
            it.rate = speed
        }
    }

    override fun onPause() {
        log.info { "onPause()" }
        engineLock.withLock {
            paused = true
            encodedPlayer?.pause()
            if (player.isPlaying())
                KodioPlayerNodePause(player).throwIfRaised()
        }
    }

    override fun onResume() {
        log.info { "onResume()" }
        val encoded = encodedPlayer
        if (encoded != null) {
            engineLock.withLock {
                paused = false
                encoded.play()
            }
            return
        }
        engineLock.withLock {
            paused = false
            // With nothing scheduled yet the next scheduled buffer plays the node; with no
            // play in flight there is nothing to resume.
            val inFlight = playbackInFlight
            if (inFlight == null || !scheduled) return
            try {
                startNode()
            } catch (failure: Exception) {
                // Failed under the lock, so the play that failed is the one that ends.
                inFlight.completeExceptionally(failure)
                throw failure
            }
        }
    }

    override fun onStop() {
        log.info { "onStop()" }
        encodedPlayer?.let {
            it.stop()
            it.currentTime = 0.0
        }
        // Each step is tried whatever became of the one before: a stop must leave nothing
        // running. The node's stop ends what was scheduled, so the wait for it ends too.
        engineLock.withLock {
            nodeGeneration++
            paused = false
            scheduled = false
            playbackInFlight = null
            attempt("stop the player node") { KodioPlayerNodeStop(player) }
            attempt("stop the engine") { KodioEngineStop(engine) }
            attempt("disconnect the player node") { KodioEngineDisconnectNodeOutput(engine, player) }
            attempt("disconnect the varispeed unit") { KodioEngineDisconnectNodeOutput(engine, varispeed) }
        }
        log.info { "Engine stopped and nodes disconnected" }
    }

    override fun releaseLoadedEncodedAudio() {
        encodedPlayer?.stop()
        encodedPlayer = null
        encodedTempPath?.let {
            runCatching { SystemFileSystem.delete(it, mustExist = false) }
        }
        encodedTempPath = null
    }

    private fun writeEncodedTempFile(bytes: ByteArray, extension: String): Path {
        val path = Path(
            SystemTemporaryDirectory,
            "kodio-${kotlin.random.Random.nextLong().toString(16)}.$extension"
        )
        SystemFileSystem.sink(path).use { sink ->
            val buffer = Buffer().apply { write(bytes) }
            sink.write(buffer, buffer.size)
        }
        return path
    }
}

private inline fun <T> NSRecursiveLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

/** The notification observers of one session, removed together when the session is collected. */
private class NotificationObservers {

    private val lock = NSRecursiveLock()
    private val tokens = mutableListOf<NSObjectProtocol>()

    fun add(name: String?, sender: Any?, onNotified: (NSNotification) -> Unit) {
        val token = NSNotificationCenter.defaultCenter.addObserverForName(
            name = name,
            `object` = sender,
            queue = null,
        ) { notification -> if (notification != null) onNotified(notification) }
        lock.withLock { tokens += token }
    }

    fun removeAll() = lock.withLock {
        tokens.forEach(NSNotificationCenter.defaultCenter::removeObserver)
        tokens.clear()
    }
}
