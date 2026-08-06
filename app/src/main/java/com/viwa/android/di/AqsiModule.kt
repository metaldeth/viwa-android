package com.viwa.android.di

import com.viwa.android.data.payment.aqsi.AqsiLastOperationSnapshotHolder
import com.viwa.android.data.payment.aqsi.AqsiPaymentAuditLogger
import com.viwa.android.data.payment.aqsi.AqsiRepositoryImpl
import com.viwa.android.data.payment.aqsi.AqsiUsbPaymentManager
import com.viwa.android.data.payment.aqsi.network.AqsiPillNetworkRouter
import com.viwa.android.data.payment.aqsi.serial.AqsiUsbSerialAccess
import com.viwa.android.data.payment.aqsi.serial.AndroidAqsiUsbSerialAccess
import com.viwa.android.data.payment.aqsi.setup.AqsiPaymentStartupInitializer
import com.viwa.android.data.repository.CardPaymentMockModeRepositoryImpl
import com.viwa.android.data.repository.CardPaymentMethodRepositoryImpl
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.domain.repository.AqsiRepository
import com.viwa.android.domain.repository.CardPaymentMockModeRepository
import com.viwa.android.domain.repository.CardPaymentMethodRepository
import com.viwa.android.di.AppIoScope
import com.viwa.android.hardware.serial.PaymentSerialPort
import com.viwa.android.hardware.serial.ViwaPaymentSerialPort
import com.viwa.android.services.payment.CardPaymentEventLogger
import com.viwa.android.services.payment.CardPaymentOrchestrator
import com.viwa.android.services.payment.PillUsbSessionOwner
import com.viwa.android.services.payment.PillUsbSessionOwnerImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import android.content.Context
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
abstract class AqsiModule {

    companion object {
        @Provides
        @Singleton
        fun provideAqsiLastOperationSnapshotHolder(): AqsiLastOperationSnapshotHolder =
            AqsiLastOperationSnapshotHolder()

        @Provides
        @Singleton
        fun provideAqsiPillNetworkRouter(
            @ApplicationContext context: Context,
        ): AqsiPillNetworkRouter = AqsiPillNetworkRouter.getInstance(context)

        @Provides
        @Singleton
        @AqsiIoScope
        fun provideAqsiIoScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Provides
        @Singleton
        fun provideAqsiRepository(
            configRepository: ConfigRepository,
            usbPaymentManager: AqsiUsbPaymentManager,
            lastOperationSnapshotHolder: AqsiLastOperationSnapshotHolder,
            paymentEventLogger: CardPaymentEventLogger,
        ): AqsiRepository =
            AqsiRepositoryImpl(
                configRepository = configRepository,
                usbPaymentManager = usbPaymentManager,
                lastOperationSnapshotHolder = lastOperationSnapshotHolder,
                paymentEventLogger = paymentEventLogger,
            )

        @Provides
        @Singleton
        fun provideCardPaymentMethodRepository(
            configRepository: ConfigRepository,
        ): CardPaymentMethodRepository = CardPaymentMethodRepositoryImpl(configRepository)

        @Provides
        @Singleton
        fun provideCardPaymentMockModeRepository(
            configRepository: ConfigRepository,
        ): CardPaymentMockModeRepository = CardPaymentMockModeRepositoryImpl(configRepository)

        @Provides
        @Singleton
        fun provideCardPaymentOrchestrator(
            cardPaymentMockModeRepository: CardPaymentMockModeRepository,
            aqsiRepository: AqsiRepository,
            paymentEventLogger: CardPaymentEventLogger,
        ): CardPaymentOrchestrator =
            CardPaymentOrchestrator(
                cardPaymentMockModeRepository = cardPaymentMockModeRepository,
                aqsiRepository = aqsiRepository,
                paymentEventLogger = paymentEventLogger,
            )
    }

    @Binds
    @Singleton
    abstract fun bindAqsiUsbSerialAccess(impl: AndroidAqsiUsbSerialAccess): AqsiUsbSerialAccess

    @Binds
    @Singleton
    abstract fun bindPillUsbSessionOwner(impl: PillUsbSessionOwnerImpl): PillUsbSessionOwner
}
