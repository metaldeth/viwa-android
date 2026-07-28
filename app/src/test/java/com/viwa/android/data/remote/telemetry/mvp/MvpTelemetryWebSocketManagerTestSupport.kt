package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.network.NetworkTrafficLogger
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope

internal fun mockOtaCoordinatorProvider(): javax.inject.Provider<com.viwa.android.domain.ota.AppUpdateCoordinator> {
    val otaCoordinator = mockk<com.viwa.android.domain.ota.AppUpdateCoordinator>(relaxed = true)
    val otaProvider = mockk<javax.inject.Provider<com.viwa.android.domain.ota.AppUpdateCoordinator>>()
    io.mockk.every { otaProvider.get() } returns otaCoordinator
    return otaProvider
}

internal fun TestScope.createWsManagerForTests(
    trafficLogger: NetworkTrafficLogger = NetworkTrafficLogger(),
): MvpTelemetryWebSocketManager {
    val otaProvider = mockOtaCoordinatorProvider()
    return MvpTelemetryWebSocketManager(
        appScope = this,
        networkTrafficLogger = trafficLogger,
        ackRouter = mockk(relaxed = true),
        outboxDrainCoordinator = mockk(relaxed = true),
        offlineEntitlementCoordinator = mockk(relaxed = true),
        technicianKeySessionCoordinator = mockk(relaxed = true),
        appUpdateCoordinatorProvider = otaProvider,
    )
}
