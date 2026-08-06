package com.viwa.android.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import com.viwa.android.ui.screens.customer.DrinkListScreen
import com.viwa.android.ui.screens.customer.DrinkListViewModel

@Composable
fun HomeScreen(
    onOpenService: () -> Unit,
    onOpenFreeDrinkOffer: () -> Unit,
    onNavigateToPreparing: (tasteId: Int, productName: String, estSeconds: Int, mediaKey: String?, payMethod: String, priceRub: Int) -> Unit,
    onHomeIdleBlockingChanged: (Boolean) -> Unit = {},
) {
    val vm: DrinkListViewModel = hiltViewModel()
    DrinkListScreen(
        viewModel = vm,
        onOpenService = onOpenService,
        onOpenFreeDrinkOffer = onOpenFreeDrinkOffer,
        onNavigateToPreparing = onNavigateToPreparing,
        onHomeIdleBlockingChanged = onHomeIdleBlockingChanged,
    )
}
