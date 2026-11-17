package space.kodio.core

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSError
import platform.Foundation.NSException as RaisedNSException

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun <T> runErrorCatching(block: (CPointer<ObjCObjectVar<NSError?>>) -> T): Result<T> {
    memScoped {
        val errorVar = alloc<ObjCObjectVar<NSError?>>()
        val result = block(errorVar.ptr)
        val errorValue = errorVar.value
        return if (errorValue != null)
            Result.failure(NSException(errorValue))
        else
            Result.success(result)
    }
}

internal class NSException(error: NSError) : Exception(error.localizedDescription)

/**
 * An `NSException` that AVFAudio raised, caught by the ObjC shim (`KodioAVFAudio.def`)
 * and carried on as a Kotlin exception. Kotlin cannot catch the `NSException` itself.
 */
class AVAudioRaisedException(
    val name: String?,
    val reason: String?,
) : Exception("${name ?: "NSException"}: ${reason ?: "no reason given"}")

/**
 * Throws the `NSException` a call into the ObjC shim returned, as an [AVAudioRaisedException].
 * The shim returns null when nothing was raised.
 */
internal fun RaisedNSException?.throwIfRaised() {
    if (this != null) throw AVAudioRaisedException(name, reason)
}
