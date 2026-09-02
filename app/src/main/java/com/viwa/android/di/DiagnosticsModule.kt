package com.viwa.android.di

import com.viwa.android.logging.diagnostics.ControllerCommandDiagnostics
import com.viwa.android.logging.diagnostics.ControllerCommandDiagnosticsListener
import com.viwa.android.logging.diagnostics.AndroidUsageStatsForegroundProvider
import com.viwa.android.logging.diagnostics.UsageStatsForegroundProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsModule {
    @Binds
    @Singleton
    abstract fun bindControllerCommandDiagnostics(
        impl: ControllerCommandDiagnostics,
    ): ControllerCommandDiagnosticsListener

    @Binds
    @Singleton
    abstract fun bindUsageStatsForegroundProvider(
        impl: AndroidUsageStatsForegroundProvider,
    ): UsageStatsForegroundProvider
}
