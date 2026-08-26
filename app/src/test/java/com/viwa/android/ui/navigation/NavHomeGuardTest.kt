package com.viwa.android.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class NavHomeGuardTest {
    @Test
    fun alreadyOnHomeDoesNotPop() {
        assertEquals(HomeNavAction.AlreadyHome, resolveReturnToHome(Routes.Home, homeOnStack = true))
        assertEquals(HomeNavAction.AlreadyHome, resolveReturnToHome(Routes.Home, homeOnStack = false))
    }

    @Test
    fun offerOrServicePopsToHomeWhenHomeIsOnStack() {
        assertEquals(HomeNavAction.PopToHome, resolveReturnToHome(Routes.FreeDrinkOffer, homeOnStack = true))
        assertEquals(HomeNavAction.PopToHome, resolveReturnToHome(Routes.Service, homeOnStack = true))
    }

    @Test
    fun emptyStackNavigatesHome() {
        assertEquals(HomeNavAction.NavigateHome, resolveReturnToHome(null, homeOnStack = false))
        assertEquals(HomeNavAction.NavigateHome, resolveReturnToHome(Routes.FreeDrinkOffer, homeOnStack = false))
    }
}
