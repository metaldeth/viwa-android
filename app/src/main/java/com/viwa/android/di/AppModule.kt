package com.viwa.android.di

import android.content.Context
import com.viwa.android.data.local.db.JsonStoreDao
import com.viwa.android.data.network.NetworkTrafficInterceptor
import com.viwa.android.data.network.PaymasterFriendlyDns
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.data.repository.ConfigRepositoryImpl
import com.viwa.android.data.repository.UpdateRepository
import com.viwa.android.data.repository.UpdateRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    @AppIoScope
    fun provideAppIoScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideOkHttpClient(networkTrafficInterceptor: NetworkTrafficInterceptor): OkHttpClient =
        OkHttpClient
            .Builder()
            .dns(PaymasterFriendlyDns())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .addInterceptor(networkTrafficInterceptor)
            .build()

    @Provides
    @Singleton
    fun provideConfigRepository(dao: JsonStoreDao): ConfigRepository = ConfigRepositoryImpl(dao)

    @Provides
    @Singleton
    fun provideUpdateRepository(
        client: OkHttpClient,
        config: ConfigRepository,
        @ApplicationContext ctx: Context,
        appUpdateCoordinator: com.viwa.android.domain.ota.AppUpdateCoordinator,
        apkVerifier: com.viwa.android.domain.ota.OtaApkVerifier,
        @AppIoScope scope: CoroutineScope,
    ): UpdateRepository = UpdateRepositoryImpl(client, config, ctx, appUpdateCoordinator, apkVerifier, scope)
}
