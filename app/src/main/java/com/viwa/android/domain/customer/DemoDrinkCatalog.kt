package com.viwa.android.domain.customer

import com.viwa.android.domain.model.customer.DrinkContainer
import com.viwa.android.domain.model.customer.DrinkDosage
import com.viwa.android.domain.model.customer.DrinkPrice
import com.viwa.android.domain.model.customer.DrinkProduct
import com.viwa.android.domain.model.customer.DrinkTaste

/**
 * Тестовый каталог (юнит-тесты, ручные сценарии). Экран выбора напитка больше не подставляет его по умолчанию —
 * список контейнеров приходит только из телеметрии (`telemetryMergedInventory`).
 * после телеметрии.
 */
object DemoDrinkCatalog {
    fun productName(productId: Int): String =
        containers.firstOrNull { it.product.id == productId }?.product?.name ?: "Напиток"

    val containers: List<DrinkContainer> =
        listOf(
            DrinkContainer(
                containerNumber = 1,
                sodaStatus = false,
                product =
                    DrinkProduct(
                        id = 101,
                        name = "Вишня",
                        taste =
                            DrinkTaste(
                                id = 1,
                                name = "Вишня",
                                mediaKey = "cherry",
                                hexColor = "#E53935",
                            ),
                        dosage =
                            DrinkDosage(
                                conversionFactor = 10.0,
                                drinkVolume = 700,
                                product = 70.0,
                                water = 630.0,
                            ),
                        dPrices =
                            listOf(
                                DrinkPrice(300, 120),
                                DrinkPrice(700, 180),
                            ),
                    ),
                productUuid = "demo-cherry-uuid",
                volumeMl = 5000,
                minVolumeMl = 200,
            ),
            DrinkContainer(
                containerNumber = 2,
                sodaStatus = true,
                product =
                    DrinkProduct(
                        id = 102,
                        name = "Лайм",
                        taste =
                            DrinkTaste(
                                id = 2,
                                name = "Лайм",
                                mediaKey = "lime",
                                hexColor = "#63E515",
                            ),
                        dosage =
                            DrinkDosage(
                                conversionFactor = 10.0,
                                drinkVolume = 700,
                                product = 65.0,
                                water = 635.0,
                            ),
                        dPrices =
                            listOf(
                                DrinkPrice(300, 110),
                                DrinkPrice(700, 170),
                            ),
                    ),
                productUuid = "demo-lime-uuid",
                volumeMl = 100,
                minVolumeMl = 500,
            ),
        )
}
