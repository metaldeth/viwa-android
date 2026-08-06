package com.viwa.android.ui.screens.customer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrinkListUiStateIdleBlockingTest {
    @Test
    fun `default state does not block idle overlay`() {
        assertFalse(DrinkListUiState().blocksIdleVideoOverlay())
    }

    @Test
    fun `payment sheet visible blocks idle overlay`() {
        assertTrue(DrinkListUiState(paymentSheetVisible = true).blocksIdleVideoOverlay())
    }

    @Test
    fun `subscription level picker blocks idle overlay`() {
        assertTrue(DrinkListUiState(subscriptionLevelPickerVisible = true).blocksIdleVideoOverlay())
    }

    @Test
    fun `invalid subscription card blocks idle overlay`() {
        assertTrue(DrinkListUiState(invalidSubscriptionCardVisible = true).blocksIdleVideoOverlay())
    }

    @Test
    fun `payment processing blocks idle overlay`() {
        assertTrue(DrinkListUiState(isProcessingPay = true).blocksIdleVideoOverlay())
    }

    @Test
    fun `active water pour blocks idle overlay`() {
        assertTrue(DrinkListUiState(isWaterPourActive = true).blocksIdleVideoOverlay())
    }

    @Test
    fun `subscription receipt url blocks idle overlay`() {
        assertTrue(DrinkListUiState(subscriptionReceiptUrl = "https://check.example/1").blocksIdleVideoOverlay())
    }

    @Test
    fun `subscription receipt loading blocks idle overlay`() {
        assertTrue(DrinkListUiState(subscriptionReceiptLoading = true).blocksIdleVideoOverlay())
    }
}
