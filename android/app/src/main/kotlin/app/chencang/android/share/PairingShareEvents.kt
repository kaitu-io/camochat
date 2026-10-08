package app.chencang.android.share

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-process bus from [PairingShareReceiver] to an open pairing wizard: emits the pairingId /
 * fingerprint whose pairing code the share sheet just handed to a chosen app, after it has been
 * marked shared. A wizard that is not open simply misses it (the mark is already on disk).
 */
object PairingShareEvents {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun emit(id: String) {
        _events.tryEmit(id)
    }
}
