package com.viwa.android.di

import android.content.Context
import androidx.room.Room
import com.viwa.android.data.local.db.ViwaDatabase
import com.viwa.android.data.local.db.JsonStoreDao
import com.viwa.android.data.local.outbox.MachineOutboxDao
import com.viwa.android.data.local.entitlement.EntitlementCacheDao
import com.viwa.android.data.local.entitlement.OfflineUsageLedgerDao
import com.viwa.android.data.local.technician.TechnicianAllowlistDao
import com.viwa.android.data.local.technician.TechnicianAllowlistStateDao
import com.viwa.android.data.local.technician.TechnicianAuditOutboxDao
import com.viwa.android.data.local.outbox.MachineOutboxPersistence
import com.viwa.android.data.local.recipe.CellAssignmentBaseDao
import com.viwa.android.data.local.recipe.CellEffectiveRecipeDao
import com.viwa.android.data.local.outbox.RoomMachineOutboxPersistence
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ViwaDatabase =
        Room
            .databaseBuilder(context, ViwaDatabase::class.java, "wiva.db")
            .addMigrations(
                ViwaDatabase.MIGRATION_1_2,
                ViwaDatabase.MIGRATION_2_3,
                ViwaDatabase.MIGRATION_3_4,
                ViwaDatabase.MIGRATION_4_5,
                ViwaDatabase.MIGRATION_5_6,
                ViwaDatabase.MIGRATION_6_7,
                ViwaDatabase.MIGRATION_7_8,
            )
            .build()

    @Provides
    fun provideJsonStoreDao(db: ViwaDatabase): JsonStoreDao = db.jsonStoreDao()

    @Provides
    fun provideMachineOutboxDao(db: ViwaDatabase): MachineOutboxDao = db.machineOutboxDao()

    @Provides
    fun provideEntitlementCacheDao(db: ViwaDatabase): EntitlementCacheDao = db.entitlementCacheDao()

    @Provides
    fun provideOfflineUsageLedgerDao(db: ViwaDatabase): OfflineUsageLedgerDao = db.offlineUsageLedgerDao()

    @Provides
    fun provideTechnicianAllowlistDao(db: ViwaDatabase): TechnicianAllowlistDao = db.technicianAllowlistDao()

    @Provides
    fun provideTechnicianAllowlistStateDao(db: ViwaDatabase): TechnicianAllowlistStateDao = db.technicianAllowlistStateDao()

    @Provides
    fun provideTechnicianAuditOutboxDao(db: ViwaDatabase): TechnicianAuditOutboxDao = db.technicianAuditOutboxDao()

    @Provides
    fun provideCellEffectiveRecipeDao(db: ViwaDatabase): CellEffectiveRecipeDao = db.cellEffectiveRecipeDao()

    @Provides
    fun provideCellAssignmentBaseDao(db: ViwaDatabase): CellAssignmentBaseDao = db.cellAssignmentBaseDao()

    @Provides
    @Singleton
    fun provideMachineOutboxPersistence(dao: MachineOutboxDao): MachineOutboxPersistence =
        RoomMachineOutboxPersistence(dao)
}
