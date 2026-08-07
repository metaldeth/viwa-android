package com.viwa.android.domain.recipe



import com.viwa.android.data.local.recipe.CellAssignmentBaseStore

import com.viwa.android.data.local.recipe.CellEffectiveRecipeStore

import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeDownlinkEvent

import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeSyncCoordinator

import javax.inject.Inject

import javax.inject.Singleton

import kotlinx.coroutines.CoroutineScope

import kotlinx.coroutines.Job

import kotlinx.coroutines.launch

import timber.log.Timber



/**

 * Domain orchestration: subscribe downlink actor, persist assignment+watermark-before-inbox, apply commands, emit acks.

 */

@Singleton

class RecipeSyncOrchestrator

@Inject

constructor(

    private val wsCoordinator: RecipeSyncCoordinator,

    private val inbox: RecipeCommandInbox,

    private val effectiveRecipeStore: CellEffectiveRecipeStore,

    private val assignmentBaseStore: CellAssignmentBaseStore,

) {

    private var collectorJob: Job? = null



    fun startDownlinkProcessing(scope: CoroutineScope) {

        if (collectorJob?.isActive == true) return

        collectorJob =

            scope.launch {

                wsCoordinator.downlinkEvents.collect { event ->

                    when (event) {

                        is RecipeDownlinkEvent.SyncControl -> {

                            assignmentBaseStore.mergeFromSyncControl(event.cells)

                            inbox.enqueueSyncControl(event.cells)

                        }

                        is RecipeDownlinkEvent.Command -> inbox.enqueueCommand(event.command)

                    }

                    inbox.drain()

                }

            }

    }



    suspend fun stopDownlinkProcessing() {

        collectorJob?.cancel()

        collectorJob = null

        inbox.resetTransientState()

    }



    suspend fun onUplinkFenceOpened() {

        effectiveRecipeStore.setRuntimeManagedModeActive(true)

        wsCoordinator.markManagedModeReady()

    }



    suspend fun onDisconnect() {

        stopDownlinkProcessing()

        effectiveRecipeStore.setRuntimeManagedModeActive(false)

    }

}

