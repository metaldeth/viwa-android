package com.viwa.android.data.local.outbox



import com.viwa.android.domain.model.customer.DrinkConcentration

import com.viwa.android.domain.model.customer.DrinkDosage

import com.viwa.android.domain.telemetry.DispenseTelemetryFactory
import com.viwa.android.domain.telemetry.PlainWaterType



internal object TestOutboxFixtures {

    private val dosage = DrinkDosage(conversionFactor = 0.5, drinkVolume = 300, product = 30.0, water = 270.0)



    suspend fun enqueueTestPaidComplete(

        commerceOutboxStore: CommerceOutboxStore,

        transactionId: String = "sale-1",

    ) {

        commerceOutboxStore.enqueuePaidComplete(

            DispenseTelemetryFactory.paidComplete(

                transactionId = transactionId,

                requestUuid = "pour-$transactionId",

                volumeMl = 300,

                amountRub = 150.0,

                payMethod = "CARD",

                productId = "prod-test",

                productNameSnapshot = "Test",

                concentration = DrinkConcentration.Standard,

                dosage = dosage,

            ),

        )

    }



    suspend fun enqueueTestPour(

        pourOutboxStore: PourOutboxStore,

        requestUuid: String = "pour-1",

        volumeMl: Int = 200,

    ) {

        pourOutboxStore.enqueuePourReport(

            DispenseTelemetryFactory.flavoredPourEvent(

                requestUuid = requestUuid,

                volumeMl = volumeMl,

                productId = "prod",

                productNameSnapshot = "Test",

                concentration = DrinkConcentration.Standard,

                dosage = dosage,

                clientId = "client-1",

            ),

        )

    }



    suspend fun enqueueTestPlainWaterPour(

        pourOutboxStore: PourOutboxStore,

        requestUuid: String = "plain-pour-1",

        volumeMl: Int = 150,

        clientId: String? = "client-1",

        plainWaterType: PlainWaterType = PlainWaterType.FILTERED,

    ) {

        pourOutboxStore.enqueuePourReport(

            DispenseTelemetryFactory.plainPourEvent(

                requestUuid = requestUuid,

                volumeMl = volumeMl,

                clientId = clientId,

                plainWaterType = plainWaterType,

            ),

        )

    }

}


