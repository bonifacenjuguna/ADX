package com.androx.app.platform

import com.androx.app.core.value.Value

/**
 * The hand-over point between Android event sources (Listener service, receivers) and the TriggerHub.
 * The app container sets [sink] to `hub::onEvent` when it starts.
 */
object EventBus {
    @Volatile
    var sink: ((typeId: String, data: Map<String, Value>) -> Unit)? = null

    fun emit(typeId: String, data: Map<String, Value> = emptyMap()) {
        sink?.invoke(typeId, data)
    }
}

/** Last seen flashlight state; filled while the "torch" source is being listened to. */
object TorchState {
    @Volatile
    var value: Boolean? = null
}
