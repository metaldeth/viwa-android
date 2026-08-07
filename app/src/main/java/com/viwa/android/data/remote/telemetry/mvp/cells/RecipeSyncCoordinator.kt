package com.viwa.android.data.remote.telemetry.mvp.cells



import com.viwa.android.data.local.recipe.RecipeSyncFeatureFlags

import com.viwa.android.data.remote.telemetry.mvp.MvpHelloPayloadDto

import javax.inject.Inject

import javax.inject.Singleton

import kotlinx.coroutines.channels.BufferOverflow

import kotlinx.coroutines.channels.Channel

import kotlinx.coroutines.channels.ClosedReceiveChannelException

import kotlinx.coroutines.flow.Flow

import kotlinx.coroutines.flow.MutableSharedFlow

import kotlinx.coroutines.flow.SharedFlow

import kotlinx.coroutines.flow.asSharedFlow

import kotlinx.coroutines.flow.channelFlow

import kotlinx.coroutines.sync.Mutex

import kotlinx.coroutines.sync.withLock

import timber.log.Timber



sealed class RecipeDownlinkEvent {

    data class SyncControl(val cells: List<RecipeSyncControlCell>) : RecipeDownlinkEvent()



    data class Command(val command: RecipeCommandDownlink) : RecipeDownlinkEvent()

}



/** Emitted when downlink backpressure exceeds safe bounds — consumer should force reconnect. */

data class RecipeDownlinkOverflow(

    val bufferedPreFence: Int,

    val channelCapacity: Int,

    val droppedEventType: String?,

)



/**

 * Recipe WS runtime gate, uplink-phase fencing, and **lossless** ordered downlink channel (task-15/16/18).

 *

 * Never silently drops sync.control/commands: pre-fence buffer is bounded by server protocol max;

 * post-fence channel uses [BufferOverflow.SUSPEND]; overflow surfaces via [overflowEvents].

 */

@Singleton

class RecipeSyncCoordinator

@Inject

constructor(

    private val recipeCodec: RecipeMessageCodec,

) {

    private var featureEnabledSupplier: () -> Boolean = { RecipeSyncFeatureFlags.FEATURE_RECIPE_SYNC }



    internal constructor(

        recipeCodec: RecipeMessageCodec,

        featureEnabled: () -> Boolean,

    ) : this(recipeCodec) {

        featureEnabledSupplier = featureEnabled

    }



    private val mutex = Mutex()

    private val downlinkBuffer = ArrayDeque<RecipeDownlinkEvent>()



    private val downlinkChannel =

        Channel<RecipeDownlinkEvent>(

            capacity = DOWNLINK_CHANNEL_CAPACITY,

            onBufferOverflow = BufferOverflow.SUSPEND,

        )



    private val _overflowEvents = MutableSharedFlow<RecipeDownlinkOverflow>(extraBufferCapacity = 1)

    val overflowEvents: SharedFlow<RecipeDownlinkOverflow> = _overflowEvents.asSharedFlow()



    /** Subscribe before [completeUplinkPhase] — single ordered consumer (task-16). */

    val downlinkEvents: Flow<RecipeDownlinkEvent> = downlinkChannel.asFlow()



    @Volatile

    private var helloEligible = false



    @Volatile

    private var initialSyncBegun = false



    @Volatile

    private var uplinkPhaseComplete = false



    @Volatile

    private var managedModeReady = false



    fun configureFromHello(hello: MvpHelloPayloadDto) {

        helloEligible =

            featureEnabledSupplier() &&

                hello.protocolVersion >= 4 &&

                hello.capabilities?.recipeSync != null

        Timber.d(

            "RecipeSyncCoordinator: hello eligible=$helloEligible protocol=${hello.protocolVersion}",

        )

    }



    fun isHelloEligible(): Boolean = helloEligible



    /** Runtime managed gate: hello negotiated + uplink fence opened + orchestrator ready. */

    fun isManagedModeActive(): Boolean = helloEligible && initialSyncBegun && uplinkPhaseComplete && managedModeReady



    fun isUplinkPhaseComplete(): Boolean = uplinkPhaseComplete



    fun shouldProcessRecipeDownlink(): Boolean = isManagedModeActive()



    fun isRecipeWireType(type: String): Boolean = type in RECIPE_WS_ALL_TYPES



    suspend fun beginInitialSync() {

        mutex.withLock {

            if (!helloEligible) return

            initialSyncBegun = true

            uplinkPhaseComplete = false

            managedModeReady = false

            downlinkBuffer.clear()

        }

        Timber.i("RecipeSyncCoordinator: initial sync coordination begun")

    }



    suspend fun completeUplinkPhase(success: Boolean) {

        if (!success) {

            Timber.w("RecipeSyncCoordinator: uplink phase failed — downlink fence stays closed")

            return

        }

        val toFlush =

            mutex.withLock {

                if (!helloEligible || !initialSyncBegun) return

                uplinkPhaseComplete = true

                downlinkBuffer.toList().also { downlinkBuffer.clear() }

            }

        Timber.i(

            "RecipeSyncCoordinator: uplink phase complete, flushing ${toFlush.size} buffered downlink frames",

        )

        toFlush.forEach { event -> enqueueDownlink(event) }

    }



    suspend fun markManagedModeReady() {

        mutex.withLock {

            managedModeReady = true

        }

    }



    suspend fun handleSyncControl(payloadJson: String) {

        if (!helloEligible) {

            Timber.d("RecipeSyncCoordinator: ignoring sync.control — recipe sync not negotiated")

            return

        }

        when (val decoded = recipeCodec.decodeSyncControlPayload(payloadJson)) {

            is RecipeDecodeResult.Invalid -> {

                Timber.w("RecipeSyncCoordinator: skip sync.control — ${decoded.reason}")

            }

            is RecipeDecodeResult.Success -> {

                emitOrBuffer(RecipeDownlinkEvent.SyncControl(decoded.value))

            }

        }

    }



    suspend fun handleCommand(payloadJson: String) {

        if (!helloEligible) {

            Timber.d("RecipeSyncCoordinator: ignoring command — recipe sync not negotiated")

            return

        }

        when (val decoded = recipeCodec.decodeCommandPayload(payloadJson)) {

            is RecipeDecodeResult.Invalid -> {

                Timber.w("RecipeSyncCoordinator: skip command — ${decoded.reason}")

            }

            is RecipeDecodeResult.Success -> {

                emitOrBuffer(RecipeDownlinkEvent.Command(decoded.value))

            }

        }

    }



    suspend fun resetOnDisconnect() {

        mutex.withLock {

            helloEligible = false

            initialSyncBegun = false

            uplinkPhaseComplete = false

            managedModeReady = false

            downlinkBuffer.clear()

        }

        while (downlinkChannel.tryReceive().isSuccess) {

            // drain transient channel only — persisted effective/revision untouched

        }

        Timber.d("RecipeSyncCoordinator: reset on disconnect")

    }



    suspend fun bufferedDownlinkCount(): Int =

        mutex.withLock {

            downlinkBuffer.size

        }



    private suspend fun emitOrBuffer(event: RecipeDownlinkEvent) {

        val emitNow =

            mutex.withLock {

                if (!initialSyncBegun) {

                    bufferPreFence(event)

                    false

                } else if (!uplinkPhaseComplete) {

                    bufferPreFence(event)

                    false

                } else {

                    true

                }

            }

        if (emitNow) {

            enqueueDownlink(event)

        }

    }



    private fun bufferPreFence(event: RecipeDownlinkEvent) {

        if (downlinkBuffer.size >= MAX_PRE_FENCE_BUFFER) {

            Timber.e(

                "RecipeSyncCoordinator: pre-fence buffer overflow (${downlinkBuffer.size}) — " +

                    "forcing overflow signal for ${event::class.simpleName}",

            )

            _overflowEvents.tryEmit(

                RecipeDownlinkOverflow(

                    bufferedPreFence = downlinkBuffer.size,

                    channelCapacity = DOWNLINK_CHANNEL_CAPACITY,

                    droppedEventType = event::class.simpleName,

                ),

            )

            return

        }

        downlinkBuffer.addLast(event)

    }



    private suspend fun enqueueDownlink(event: RecipeDownlinkEvent) {

        runCatching {

            downlinkChannel.send(event)

        }.onFailure { error ->

            Timber.e(error, "RecipeSyncCoordinator: downlink channel send failed for ${event::class.simpleName}")

            _overflowEvents.tryEmit(

                RecipeDownlinkOverflow(

                    bufferedPreFence = downlinkBuffer.size,

                    channelCapacity = DOWNLINK_CHANNEL_CAPACITY,

                    droppedEventType = event::class.simpleName,

                ),

            )

        }

    }



    companion object {

        /** Server caps downlink commands at 64; buffer headroom for control + refresh batches. */

        const val DOWNLINK_CHANNEL_CAPACITY = 128

        const val MAX_PRE_FENCE_BUFFER = 256



        internal fun forTests(

            recipeCodec: RecipeMessageCodec = RecipeMessageCodec(),

            featureEnabled: () -> Boolean = { true },

        ): RecipeSyncCoordinator =

            RecipeSyncCoordinator(

                recipeCodec = recipeCodec,

                featureEnabled = featureEnabled,

            )

    }

}



private fun Channel<RecipeDownlinkEvent>.asFlow(): Flow<RecipeDownlinkEvent> =

    channelFlow {

        while (true) {

            try {

                send(receive())

            } catch (_: ClosedReceiveChannelException) {

                break

            }

        }

    }

