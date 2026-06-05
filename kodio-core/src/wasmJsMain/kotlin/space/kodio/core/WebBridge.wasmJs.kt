@file:OptIn(ExperimentalWasmJsInterop::class)

package space.kodio.core

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.toJsString
import js.buffer.ArrayBuffer
import js.buffer.ArrayBufferLike
import js.typedarrays.Float32Array
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.writeShortLe
import web.audio.AudioContextLatencyCategory
import web.audio.AudioContextOptions
import web.audio.AudioWorkletNode
import web.audio.AudioWorkletProcessorName
import web.audio.BaseAudioContext
import web.blob.Blob
import web.mediastreams.MediaStreamConstraints
import web.mediastreams.MediaTrackConstraints
import web.permissions.PermissionDescriptor
import web.url.URL
import kotlin.math.max
import kotlin.math.min

actual fun createCodeBlobUrl(code: String): String {
    @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
    val blob = Blob(
        blobParts = arrayOf(AUDIO_PROCESSOR_CODE.toJsString()).toJsArray(),
        options = BlobPropertyBag(type = "application/javascript")
    )
    return URL.createObjectURL(blob)
}

@Suppress("unused")
private fun BlobPropertyBag(type: String): web.blob.BlobPropertyBag {
    js("return { type };")
}

actual fun <B : ArrayBufferLike> Float32Array<B>.encodeAs16BitPcmByteArray(): ByteArray {
    val buffer = Buffer()
    for (i in 0 until this.length) {
        val s = max(-1.0f, min(1.0f, get(i).toDouble().toFloat()))
        val value = (s * 32767.0f).toInt().toShort()
        buffer.writeShortLe(value)
    }
    return buffer.readByteArray()
}

actual fun createAudioContextOptions(latencyHint: AudioContextLatencyCategory, sampleRate: Int): AudioContextOptions {
    js("return { latencyHint, sampleRate };")
}

actual fun createMediaStreamConstraints(audio: MediaTrackConstraints): MediaStreamConstraints {
    js("return { audio };")
}

actual fun createMediaTrackConstraints(deviceId: String?, sampleRate: Int, sampleSize: Int, channelCount: Int): MediaTrackConstraints =
    newMediaTrackConstraints(deviceId?.toJsString(), sampleRate.toJsNumber(), sampleSize.toJsNumber(), channelCount.toJsNumber())
@Suppress("unused")
private fun newMediaTrackConstraints(deviceId: JsAny?, sampleRate: JsAny?, sampleSize: JsAny?, channelCount: JsAny?): MediaTrackConstraints {
    js("return { deviceId, sampleRate, sampleSize, channelCount };")
}
actual val microphonePermissionDescriptor: PermissionDescriptor =
    js("({name: 'microphone'})")

internal actual fun FloatArray.toJsFloat32Array(): Float32Array<ArrayBuffer> {
    val out = newJsFloat32Array(size)
    for (i in 0 until size) {
        out[i] = this[i].toDouble().toJsNumber()
    }
    return out
}

@Suppress("unused")
private fun newJsFloat32Array(length: Int): Float32Array<ArrayBuffer> =
    js("new Float32Array(length)")

actual fun <T : JsAny?> JsArray<T>.toList(): List<T> =
    this.toArray().toList()

actual fun createAudioWorkletNode(context: BaseAudioContext, name: String): AudioWorkletNode =
    AudioWorkletNode(context, name.toJsString().unsafeCast<AudioWorkletProcessorName>())

internal actual fun createEncodedAudioElement(bytes: ByteArray, mimeType: String): JsAny {
    val typedBytes = newJsUint8Array(bytes.size)
    for (i in bytes.indices) {
        setJsUint8ArrayValue(typedBytes, i, bytes[i].toInt() and 0xFF)
    }
    return createEncodedAudioElementFromBytes(typedBytes, mimeType.toJsString())
}

@Suppress("unused")
private fun newJsUint8Array(length: Int): JsAny =
    js("new Uint8Array(length)")

@Suppress("unused")
private fun setJsUint8ArrayValue(array: JsAny, index: Int, value: Int) {
    js("array[index] = value;")
}

@Suppress("unused")
private fun createEncodedAudioElementFromBytes(bytes: JsAny, mimeType: JsAny): JsAny {
    js(
        """
        const url = URL.createObjectURL(new Blob([bytes], { type: mimeType }));
        const audio = new Audio(url);
        audio.preload = 'auto';
        audio.__kodioObjectUrl = url;
        return audio;
        """
    )
}

internal actual fun loadEncodedAudioElement(element: JsAny) {
    js("element.load();")
}

internal actual fun encodedAudioElementReadyState(element: JsAny): Int =
    js("element.readyState")

internal actual fun encodedAudioElementDuration(element: JsAny): Double =
    js("element.duration")

internal actual fun encodedAudioElementEnded(element: JsAny): Boolean =
    js("element.ended")

internal actual fun encodedAudioElementSetCurrentTime(element: JsAny, seconds: Double) {
    js("element.currentTime = seconds;")
}

internal actual fun encodedAudioElementSetPlaybackRate(element: JsAny, speed: Float) {
    js("element.playbackRate = speed;")
}

internal actual fun encodedAudioElementPlay(element: JsAny) {
    js(
        """
        element.__kodioPlayError = null;
        const promise = element.play();
        if (promise && typeof promise.catch === 'function') {
          promise.catch(error => {
            element.__kodioPlayError =
              error && error.message ? error.message : String(error);
          });
        }
        """
    )
}

internal actual fun encodedAudioElementPlayError(element: JsAny): String? =
    js("element.__kodioPlayError || null")

internal actual fun encodedAudioElementPause(element: JsAny) {
    js("element.pause();")
}

internal actual fun encodedAudioElementStopAndRelease(element: JsAny) {
    js(
        """
        element.pause();
        element.removeAttribute('src');
        element.load();
        if (element.__kodioObjectUrl) {
          URL.revokeObjectURL(element.__kodioObjectUrl);
          element.__kodioObjectUrl = null;
        }
        """
    )
}
