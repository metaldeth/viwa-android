package com.viwa.android.ui.screens.customer

import com.viwa.android.domain.model.MachineRegistration
import com.viwa.android.services.telemetry.ViwaTelemetryService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FreeDrinkOfferViewModelTest {
    @Before
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun T11_9_qrUrlContainsMachineSerialPathWithoutOrganizationId() = runTest {
        
        // given
        val telemetry = mockk<ViwaTelemetryService>()
        coEvery { telemetry.loadMachineRegistration() } returns
            MachineRegistration(
                serialNumber = "VIWA-000004",
                organizationId = "99",
            )

        // when
        val viewModel = FreeDrinkOfferViewModel(telemetry)
        advanceUntilIdle()

        // then
        val url = viewModel.qrUrl.value
        assertNotNull(url)
        assertEquals("https://cabinet.vitamin-water.ru/m/VIWA-000004/auth", url)
        assertFalse(url!!.contains("organizationId"))
        assertFalse(url!!.contains("/99/"))
    }
}
