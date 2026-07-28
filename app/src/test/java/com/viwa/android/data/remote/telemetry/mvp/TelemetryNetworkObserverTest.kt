package com.viwa.android.data.remote.telemetry.mvp

import android.content.Context
import android.net.ConnectivityManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryNetworkObserverTest {
    @Test
    fun `should debounce validated available callback`() =
        runTest {
            // given
            val context = mockk<Context>()
            val connectivityManager = mockk<ConnectivityManager>(relaxed = true)
            every { context.applicationContext } returns context
            every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
            var availableCount = 0
            val observer = TelemetryNetworkObserver(context, this)
            observer.onValidatedAvailable = { availableCount += 1 }

            // when
            observer.applyValidatedStateForTests(validated = true)
            advanceTimeBy(TelemetryNetworkObserver.DEBOUNCE_MS - 1)
            assertEquals(0, availableCount)
            advanceTimeBy(2)
            assertEquals(1, availableCount)
        }

    @Test
    fun `should invoke lost callback immediately without debounce`() =
        runTest {
            // given
            val context = mockk<Context>()
            val connectivityManager = mockk<ConnectivityManager>(relaxed = true)
            every { context.applicationContext } returns context
            every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
            var lostCount = 0
            val observer = TelemetryNetworkObserver(context, this)
            observer.applyValidatedStateForTests(validated = true)
            observer.onValidatedLost = { lostCount += 1 }

            // when
            observer.applyValidatedStateForTests(validated = false)

            // then
            assertEquals(1, lostCount)
            assertFalse(observer.isValidatedAvailable)
        }

    @Test
    fun `stop unregisters network callback`() {
        // given
        val context = mockk<Context>()
        val connectivityManager = mockk<ConnectivityManager>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        val observer =
            TelemetryNetworkObserver(
                context,
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
            )
        observer.start()

        // when
        observer.stop()

        // then
        verify { connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
    }
}
