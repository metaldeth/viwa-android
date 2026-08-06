package com.viwa.android.data.local.db



import androidx.room.Database

import androidx.room.RoomDatabase

import androidx.room.migration.Migration

import androidx.sqlite.db.SupportSQLiteDatabase

import com.viwa.android.data.local.entitlement.EntitlementCacheDao

import com.viwa.android.data.local.entitlement.EntitlementCacheEntity

import com.viwa.android.data.local.entitlement.OfflineUsageLedgerDao

import com.viwa.android.data.local.entitlement.OfflineUsageLedgerEntity

import com.viwa.android.data.local.technician.TechnicianAllowlistDao
import com.viwa.android.data.local.technician.TechnicianAllowlistStateDao
import com.viwa.android.data.local.technician.TechnicianAuditOutboxDao

import com.viwa.android.data.local.outbox.MachineOutboxDao
import com.viwa.android.data.local.outbox.MachineOutboxEntryEntity
import com.viwa.android.data.local.recipe.CellAssignmentBaseEntity
import com.viwa.android.data.local.recipe.CellEffectiveRecipeEntity
import com.viwa.android.data.local.technician.TechnicianAllowlistEntity
import com.viwa.android.data.local.technician.TechnicianAllowlistStateEntity
import com.viwa.android.data.local.technician.TechnicianAuditOutboxEntity



@Database(

    entities = [

        JsonStoreEntity::class,

        MachineOutboxEntryEntity::class,

        EntitlementCacheEntity::class,

        OfflineUsageLedgerEntity::class,

        TechnicianAllowlistEntity::class,
        TechnicianAllowlistStateEntity::class,
        TechnicianAuditOutboxEntity::class,
        CellEffectiveRecipeEntity::class,
        CellAssignmentBaseEntity::class,
    ],

    version = 8,

    exportSchema = true,

)

abstract class ViwaDatabase : RoomDatabase() {

    abstract fun jsonStoreDao(): JsonStoreDao



    abstract fun machineOutboxDao(): MachineOutboxDao



    abstract fun entitlementCacheDao(): EntitlementCacheDao



    abstract fun offlineUsageLedgerDao(): OfflineUsageLedgerDao

    abstract fun technicianAllowlistDao(): TechnicianAllowlistDao

    abstract fun technicianAllowlistStateDao(): TechnicianAllowlistStateDao

    abstract fun technicianAuditOutboxDao(): TechnicianAuditOutboxDao

    abstract fun cellEffectiveRecipeDao(): com.viwa.android.data.local.recipe.CellEffectiveRecipeDao

    abstract fun cellAssignmentBaseDao(): com.viwa.android.data.local.recipe.CellAssignmentBaseDao

    companion object {

        val MIGRATION_1_2: Migration =

            object : Migration(1, 2) {

                override fun migrate(db: SupportSQLiteDatabase) {

                    db.execSQL(

                        """

                        CREATE TABLE IF NOT EXISTS machine_outbox (

                            local_id TEXT NOT NULL PRIMARY KEY,

                            kind TEXT NOT NULL,

                            idempotency_key TEXT NOT NULL,

                            message_id TEXT NOT NULL,

                            payload_json TEXT NOT NULL,

                            status TEXT NOT NULL,

                            attempts INTEGER NOT NULL DEFAULT 0,

                            ws_ack_failures INTEGER NOT NULL DEFAULT 0,

                            next_retry_at_ms INTEGER NOT NULL DEFAULT 0,

                            last_error TEXT,

                            session_generation_at_send INTEGER,

                            created_at_ms INTEGER NOT NULL,

                            acked_at_ms INTEGER,

                            in_flight_since_ms INTEGER

                        )

                        """.trimIndent(),

                    )

                    db.execSQL(

                        """

                        CREATE UNIQUE INDEX IF NOT EXISTS index_machine_outbox_kind_idempotency_key

                        ON machine_outbox(kind, idempotency_key)

                        """.trimIndent(),

                    )

                    db.execSQL(

                        """

                        CREATE INDEX IF NOT EXISTS index_machine_outbox_status_next_retry_at_ms

                        ON machine_outbox(status, next_retry_at_ms)

                        """.trimIndent(),

                    )

                    db.execSQL(

                        """

                        CREATE INDEX IF NOT EXISTS index_machine_outbox_message_id

                        ON machine_outbox(message_id)

                        """.trimIndent(),

                    )

                }

            }



        val MIGRATION_2_3: Migration =

            object : Migration(2, 3) {

                override fun migrate(db: SupportSQLiteDatabase) {

                    db.execSQL(

                        """

                        CREATE TABLE IF NOT EXISTS entitlement_cache (

                            subject_hash TEXT NOT NULL,

                            machine_id TEXT NOT NULL,

                            grant_id TEXT NOT NULL,

                            subscription_level_id TEXT NOT NULL,

                            issued_at_ms INTEGER NOT NULL,

                            expires_at_ms INTEGER NOT NULL,

                            daily_remaining_ml_at_issue INTEGER NOT NULL,

                            max_offline_pours INTEGER NOT NULL,

                            max_offline_volume_ml INTEGER NOT NULL,

                            signing_key_id TEXT NOT NULL,

                            revocation_epoch INTEGER NOT NULL,

                            revision TEXT NOT NULL,

                            signature TEXT NOT NULL,

                            grant_json TEXT NOT NULL,

                            revoked INTEGER NOT NULL DEFAULT 0,

                            updated_at_ms INTEGER NOT NULL,

                            PRIMARY KEY(subject_hash, machine_id)

                        )

                        """.trimIndent(),

                    )

                    db.execSQL(

                        """

                        CREATE UNIQUE INDEX IF NOT EXISTS index_entitlement_cache_grant_id

                        ON entitlement_cache(grant_id)

                        """.trimIndent(),

                    )

                    db.execSQL(

                        """

                        CREATE INDEX IF NOT EXISTS index_entitlement_cache_expires_at_ms

                        ON entitlement_cache(expires_at_ms)

                        """.trimIndent(),

                    )

                    db.execSQL(

                        """

                        CREATE INDEX IF NOT EXISTS index_entitlement_cache_revoked

                        ON entitlement_cache(revoked)

                        """.trimIndent(),

                    )

                    db.execSQL(

                        """

                        CREATE TABLE IF NOT EXISTS offline_usage_ledger (

                            request_uuid TEXT NOT NULL PRIMARY KEY,

                            grant_id TEXT NOT NULL,

                            subject_hash TEXT NOT NULL,

                            machine_id TEXT NOT NULL,

                            sale_id TEXT NOT NULL,

                            drink_id INTEGER,

                            requested_volume_ml INTEGER NOT NULL,

                            finalized_volume_ml INTEGER,

                            state TEXT NOT NULL,

                            sold_at_ms INTEGER NOT NULL,

                            created_at_ms INTEGER NOT NULL,

                            updated_at_ms INTEGER NOT NULL,

                            reconcile_code TEXT,

                            reconcile_message TEXT

                        )

                        """.trimIndent(),

                    )

                    db.execSQL(

                        """

                        CREATE INDEX IF NOT EXISTS index_offline_usage_ledger_grant_id

                        ON offline_usage_ledger(grant_id)

                        """.trimIndent(),

                    )

                    db.execSQL(

                        """

                        CREATE INDEX IF NOT EXISTS index_offline_usage_ledger_subject_hash

                        ON offline_usage_ledger(subject_hash)

                        """.trimIndent(),

                    )

                    db.execSQL(

                        """

                        CREATE INDEX IF NOT EXISTS index_offline_usage_ledger_state

                        ON offline_usage_ledger(state)

                        """.trimIndent(),

                    )

                    db.execSQL(

                        """

                        CREATE INDEX IF NOT EXISTS index_offline_usage_ledger_sale_id

                        ON offline_usage_ledger(sale_id)

                        """.trimIndent(),

                    )

                }

            }

        val MIGRATION_3_4: Migration =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS technician_allowlist_cache (
                            fingerprint TEXT NOT NULL PRIMARY KEY,
                            key_id TEXT NOT NULL,
                            machine_id TEXT,
                            scopes_json TEXT NOT NULL,
                            expires_at_ms INTEGER,
                            expires_at_iso TEXT,
                            revocation_epoch INTEGER NOT NULL,
                            revision TEXT NOT NULL,
                            signature TEXT NOT NULL,
                            record_json TEXT NOT NULL,
                            revoked INTEGER NOT NULL DEFAULT 0,
                            updated_at_ms INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE UNIQUE INDEX IF NOT EXISTS index_technician_allowlist_cache_key_id
                        ON technician_allowlist_cache(key_id)
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE INDEX IF NOT EXISTS index_technician_allowlist_cache_expires_at_ms
                        ON technician_allowlist_cache(expires_at_ms)
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE INDEX IF NOT EXISTS index_technician_allowlist_cache_revoked
                        ON technician_allowlist_cache(revoked)
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS technician_allowlist_state (
                            id INTEGER NOT NULL PRIMARY KEY,
                            delta_cursor TEXT NOT NULL DEFAULT '0',
                            revocation_epoch INTEGER NOT NULL DEFAULT 0,
                            last_sync_at_ms INTEGER NOT NULL DEFAULT 0,
                            server_technician_keys_enabled INTEGER,
                            offline_scopes_json TEXT NOT NULL DEFAULT '[]',
                            online_only_scopes_json TEXT NOT NULL DEFAULT '[]',
                            capability_json TEXT,
                            has_trusted_allowlist_sync INTEGER NOT NULL DEFAULT 0,
                            policy_updated_at_ms INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS technician_audit_outbox (
                            request_uuid TEXT NOT NULL PRIMARY KEY,
                            fingerprint TEXT NOT NULL,
                            technician_key_id TEXT,
                            action TEXT NOT NULL,
                            channel TEXT NOT NULL,
                            outcome TEXT NOT NULL,
                            failure_code TEXT,
                            created_at_ms INTEGER NOT NULL,
                            synced_at_ms INTEGER,
                            sync_status TEXT NOT NULL DEFAULT 'PENDING'
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE INDEX IF NOT EXISTS index_technician_audit_outbox_sync_status
                        ON technician_audit_outbox(sync_status)
                        """.trimIndent(),
                    )
                }
            }

        val MIGRATION_4_5: Migration =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS cell_effective_recipe (
                            cell_id TEXT NOT NULL PRIMARY KEY,
                            base_drink_volume_ml INTEGER,
                            water_deci_ml INTEGER,
                            product_deci_ml INTEGER,
                            fingerprint TEXT,
                            source TEXT NOT NULL,
                            product_id TEXT,
                            base_version_id TEXT,
                            last_applied_command_generation INTEGER NOT NULL DEFAULT 0,
                            cancel_through_generation INTEGER NOT NULL DEFAULT 0,
                            updated_at_ms INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                }
            }

        val MIGRATION_5_6: Migration =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        ALTER TABLE cell_effective_recipe
                        ADD COLUMN device_report_revision INTEGER NOT NULL DEFAULT 0
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        ALTER TABLE cell_effective_recipe
                        ADD COLUMN last_applied_command_id TEXT
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        ALTER TABLE cell_effective_recipe
                        ADD COLUMN last_terminal_ack_status TEXT
                        """.trimIndent(),
                    )
                }
            }

        val MIGRATION_6_7: Migration =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        ALTER TABLE cell_effective_recipe
                        ADD COLUMN last_terminal_command_generation INTEGER NOT NULL DEFAULT 0
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        ALTER TABLE cell_effective_recipe
                        ADD COLUMN last_terminal_ack_failure_code TEXT
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        ALTER TABLE cell_effective_recipe
                        ADD COLUMN terminal_ack_delivered INTEGER NOT NULL DEFAULT 0
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        UPDATE cell_effective_recipe
                        SET last_terminal_command_generation = last_applied_command_generation
                        WHERE last_terminal_ack_status = 'applied'
                          AND last_applied_command_id IS NOT NULL
                          AND last_terminal_command_generation = 0
                        """.trimIndent(),
                    )
                }
            }

        val MIGRATION_7_8: Migration =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS cell_assignment_base (
                            cell_uuid TEXT NOT NULL PRIMARY KEY,
                            status TEXT NOT NULL,
                            product_id TEXT,
                            current_base_version_id TEXT,
                            base_recipe_revision INTEGER,
                            base_drink_volume_ml INTEGER,
                            water_deci_ml INTEGER,
                            product_deci_ml INTEGER,
                            fingerprint TEXT,
                            received_at_ms INTEGER NOT NULL,
                            prior_fingerprint TEXT,
                            prior_received_at_ms INTEGER
                        )
                        """.trimIndent(),
                    )
                }
            }
    }
}

