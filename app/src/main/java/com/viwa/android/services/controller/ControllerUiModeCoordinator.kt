package com.viwa.android.services.controller

import com.viwa.android.hardware.controller.ControllerHardwareManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the controller mode synchronized with the active customer/service UI.
 *
 * The desired mode is retained while the controller is disconnected and applied after reconnect.
 */
@Singleton
class ControllerUiModeCoordinator
    @Inject
    constructor(
        private val hardware: ControllerHardwareManager,
    ) {
        private enum class DesiredMode {
            AUTO,
            SERVICE,
        }

        private val applyMutex = Mutex()

        @Volatile
        private var desiredMode = DesiredMode.AUTO

        init {
            hardware.registerAfterInitializeFromConfig {
                applyDesiredMode()
            }
        }

        suspend fun enterServiceMode() {
            desiredMode = DesiredMode.SERVICE
            applyDesiredMode()
        }

        suspend fun enterAutoMode() {
            desiredMode = DesiredMode.AUTO
            applyDesiredMode()
        }

        private suspend fun applyDesiredMode() {
            applyMutex.withLock {
                when (desiredMode) {
                    DesiredMode.AUTO -> hardware.setAutoModeCommand()
                    DesiredMode.SERVICE -> hardware.setServiceModeCommand()
                }
                Timber.tag(TAG).i("Controller UI mode applied: %s", desiredMode)
            }
        }

        private companion object {
            const val TAG = "ControllerUiMode"
        }
    }
