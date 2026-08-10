package com.viwa.android.di

import com.viwa.android.data.local.security.EncryptedMachineSecretStore
import com.viwa.android.data.local.security.MachineSecretStore
import com.viwa.android.data.network.NetworkTrafficLogger
import com.viwa.android.data.remote.telemetry.mvp.EpochMillisClock
import com.viwa.android.data.remote.telemetry.mvp.MvpTelemetryApiClient
import com.viwa.android.data.remote.telemetry.mvp.SystemEpochMillisClock
import com.viwa.android.domain.model.TelemetryConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@Module
@InstallIn(SingletonComponent::class)
object TelemetryModule {
    @Provides
    @Singleton
    @Named("telemetry")
    fun provideTelemetryRetrofit(
        @Named("telemetryHttp") okHttpClient: OkHttpClient,
    ): Retrofit {
        val json =
            Json {
                ignoreUnknownKeys = true
            }
        return Retrofit.Builder()
            .baseUrl("${TelemetryConfig.DEFAULT_API_URL}/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    @Named("telemetryHttp")
    fun provideTelemetryOkHttpClient(baseClient: OkHttpClient): OkHttpClient =
        baseClient
            .newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()

    @Provides
    @Singleton
    fun provideMachineSecretStore(store: EncryptedMachineSecretStore): MachineSecretStore = store

    @Provides
    @Singleton
    fun provideEpochMillisClock(): EpochMillisClock = SystemEpochMillisClock()

    @Provides
    @Singleton
    fun provideMvpTelemetryApiClient(
        @Named("telemetryHttp") okHttpClient: OkHttpClient,
    ): MvpTelemetryApiClient {
        val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            }
        return MvpTelemetryApiClient(
            httpClient = okHttpClient,
            json = json,
            enrollmentKeyProvider = { com.viwa.android.BuildConfig.TELEMETRY_ENROLLMENT_KEY },
        )
    }
}
