package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.network.NetworkTrafficLogger
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeMessageCodec
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeSyncCoordinator
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope

internal fun mockLogShipCoordinator(): LogShipCoordinator = mockk(relaxed = true)

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
        ackRouter = TelemetryAckRouter(
            outboxStore = mockk(relaxed = true),
            recipeCodec = RecipeMessageCodec(),
        ),
        cellsContentReportAckAwaiter = mockk(relaxed = true),
        recipeSyncCoordinator = RecipeSyncCoordinator(RecipeMessageCodec()),
        outboxDrainCoordinator = mockk(relaxed = true),
        outboxStore = mockk(relaxed = true),
        recipeOutboxStore = mockk(relaxed = true),
        recipeMessageCodec = RecipeMessageCodec(),
        offlineEntitlementCoordinator = mockk(relaxed = true),
        technicianKeySessionCoordinator = mockk(relaxed = true),
        logShipCoordinator = mockLogShipCoordinator(),
        appUpdateCoordinatorProvider = otaProvider,
    )
}
