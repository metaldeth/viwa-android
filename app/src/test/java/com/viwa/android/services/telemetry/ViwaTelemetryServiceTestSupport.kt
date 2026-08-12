package com.viwa.android.services.telemetry

import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.remote.telemetry.mvp.MvpTelemetryLoyaltySyncHandler
import com.viwa.android.data.remote.telemetry.mvp.MvpTelemetryWebSocketManager
import com.viwa.android.data.remote.telemetry.mvp.SimpleTelemetryCoordinator
import com.viwa.android.data.remote.telemetry.v3.TelemetryDispenseSyncCoordinator
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.domain.model.MachineRegistration
import com.viwa.android.domain.offline.OfflinePourAuthorizationService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope

/**
 * Shared MockK stubs for [ViwaTelemetryService] unit tests.
 *
 * DEBUG cold-start bootstrap may call [SimpleTelemetryCoordinator.registerMachine]; relaxed MockK
 * defaults to `Result.success(Object())`, which breaks [Result.onSuccess] when `T` is [Unit].
 */
internal fun mockSimpleTelemetryCoordinatorForServiceTests(
    configure: SimpleTelemetryCoordinator.() -> Unit = {},
): SimpleTelemetryCoordinator {
    val coordinator = mockk<SimpleTelemetryCoordinator>(relaxed = true)
    coEvery { coordinator.registerMachine(any(), any()) } returns Result.success(Unit)
    coEvery { coordinator.enrollMachine(any(), any()) } returns Result.success(Unit)
    coEvery { coordinator.loadMachineRegistration() } returns MachineRegistration()
    coEvery { coordinator.hasStableSecret(any()) } returns false
    coEvery { coordinator.canReconnectWithPersistedCredentials() } returns false
    configure(coordinator)
    return coordinator
}

internal fun CoroutineScope.createViwaTelemetryServiceForTests(
    wsManager: MvpTelemetryWebSocketManager = mockk(relaxed = true),
    configRepository: ConfigRepository = mockk(relaxed = true),
    coordinator: SimpleTelemetryCoordinator = mockSimpleTelemetryCoordinatorForServiceTests(),
    dispenseSyncCoordinator: TelemetryDispenseSyncCoordinator = mockk(relaxed = true),
    offlinePourAuthorizationService: OfflinePourAuthorizationService = mockk(relaxed = true),
): Pair<ViwaTelemetryService, MvpTelemetryLoyaltySyncHandler> {
    coEvery { configRepository.get(JsonStoreKeys.TELEMETRY_PAUSED_BY_USER) } returns "false"
    var handler: MvpTelemetryLoyaltySyncHandler? = null
    every { wsManager.loyaltySyncHandler = any() } answers {
        handler = firstArg()
        Unit
    }
    every { wsManager.loyaltySyncHandler } answers { handler }
    val service =
        ViwaTelemetryService(
            configRepository = configRepository,
            mvpCoordinator = coordinator,
            wsManager = wsManager,
            dispenseSyncCoordinator = dispenseSyncCoordinator,
            offlinePourAuthorizationService = offlinePourAuthorizationService,
            scope = this,
        )
    return service to requireNotNull(handler)
}
