package com.viwa.android.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.viwa.android.ui.screens.customer.FreeDrinkOfferScreen
import com.viwa.android.ui.screens.customer.PreparingScreen
import com.viwa.android.ui.screens.home.HomeScreen
import com.viwa.android.ui.screens.service.ServiceScreen

@Composable
fun ViwaNavGraph(
    navController: NavHostController,
    onOpenService: () -> Unit,
) {
    NavHost(navController = navController, startDestination = Routes.Home) {
        composable(Routes.Home) {
            HomeScreen(
                onOpenService = onOpenService,
                onOpenFreeDrinkOffer = { navController.navigate(Routes.FreeDrinkOffer) },
                onNavigateToPreparing = { tasteId, productName, estSeconds, mediaKey, payMethod, priceRub ->
                    navController.navigate(
                        "${Routes.Preparing}/$tasteId/${Uri.encode(productName)}/$estSeconds/" +
                            "${Uri.encode(mediaKey ?: "none")}/$payMethod/$priceRub",
                    )
                },
            )
        }
        composable(Routes.FreeDrinkOffer) {
            FreeDrinkOfferScreen(onClose = { navController.popBackStack() })
        }
        composable(Routes.Service) {
            ServiceScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = "${Routes.Preparing}/{tasteId}/{productName}/{estSeconds}/{mediaKey}/{payMethod}/{priceRub}",
            arguments =
                listOf(
                    navArgument("tasteId") { type = NavType.IntType },
                    navArgument("productName") { type = NavType.StringType },
                    navArgument("estSeconds") { type = NavType.IntType },
                    navArgument("mediaKey") { type = NavType.StringType; nullable = true },
                    navArgument("payMethod") { type = NavType.StringType },
                    navArgument("priceRub") { type = NavType.IntType },
                ),
        ) { entry ->
            if (entry.arguments?.getInt("tasteId") == null) return@composable
            val productName = entry.arguments?.getString("productName").orEmpty()
            val estSeconds = entry.arguments?.getInt("estSeconds") ?: return@composable
            val mediaKey = entry.arguments?.getString("mediaKey")?.takeIf { it.isNotBlank() && it != "none" }
            PreparingScreen(
                productName = productName.ifBlank { "Напиток" },
                estSeconds = estSeconds,
                mediaKey = mediaKey,
                onBackToMenu = {
                    navController.popBackStack(Routes.Home, inclusive = false)
                },
            )
        }
    }
}
