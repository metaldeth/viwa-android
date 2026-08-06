package com.viwa.android.data.local.recipe

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.viwa.android.data.local.db.ViwaDatabase
import com.viwa.android.domain.recipe.CellEffectiveRecipeDefaults
import com.viwa.android.domain.recipe.CellEffectiveRecipeSource
import com.viwa.android.domain.recipe.RecipeCanonical
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Production v4 shipped with `ViwaDatabase` version=4 and `exportSchema=false` — no historical
 * `4.json` in repo until task-14 round 2 backfill (generated from commit HEAD 7f7f99c via ksp).
 *
 * Migration proof runs the canonical SQL chain 3→4→5 (all [ViwaDatabase] migration objects),
 * inserts representative rows on every production v4 entity, then opens Room v5 to validate schema
 * identity hash against exported `5.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CellEffectiveRecipeRoomTest {
    private lateinit var context: Context
    private var db: ViwaDatabase? = null
    private var openHelper: SupportSQLiteOpenHelper? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(MIGRATION_DB)
    }

    @After
    fun tearDown() {
        db?.close()
        db = null
        openHelper?.close()
        context.deleteDatabase(MIGRATION_DB)
    }

    @Test
    fun `migration chain 3 to 4 to 5 preserves all production v4 tables and opens Room v5`() {
        openHelper = createProductionVersion3OpenHelper(context, MIGRATION_DB)
        val sqlite = openHelper!!.writableDatabase

        ViwaDatabase.MIGRATION_3_4.migrate(sqlite)
        insertRepresentativeProductionV4Rows(sqlite)
        ViwaDatabase.MIGRATION_4_5.migrate(sqlite)
        sqlite.version = 5
        ViwaDatabase.MIGRATION_5_6.migrate(sqlite)
        sqlite.version = 6
        sqlite.execSQL(
            """
            INSERT INTO cell_effective_recipe (
                cell_id, base_drink_volume_ml, water_deci_ml, product_deci_ml, fingerprint,
                source, product_id, base_version_id, last_applied_command_generation,
                cancel_through_generation, device_report_revision, last_applied_command_id,
                last_terminal_ack_status, updated_at_ms
            ) VALUES (
                'cell-v6', 300, 100, 200, 'abc', 'COMMAND', NULL, 'base-1', 5,
                0, 1, 'cmd-v6', 'applied', 1000
            )
            """.trimIndent(),
        )
        ViwaDatabase.MIGRATION_6_7.migrate(sqlite)
        sqlite.version = 7
        ViwaDatabase.MIGRATION_7_8.migrate(sqlite)
        sqlite.version = 8

        assertTableRowCount(sqlite, "json_data", 1)
        assertTableRowCount(sqlite, "machine_outbox", 1)
        assertTableRowCount(sqlite, "entitlement_cache", 1)
        assertTableRowCount(sqlite, "offline_usage_ledger", 1)
        assertTableRowCount(sqlite, "technician_allowlist_cache", 1)
        assertTableRowCount(sqlite, "technician_allowlist_state", 1)
        assertTableRowCount(sqlite, "technician_audit_outbox", 1)
        sqlite.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='cell_effective_recipe'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
        sqlite.close()
        openHelper!!.close()
        openHelper = null

        db =
            Room.databaseBuilder(context, ViwaDatabase::class.java, MIGRATION_DB)
                .allowMainThreadQueries()
                .build()
        val validated = db!!.openHelper.writableDatabase
        assertTableRowCount(validated, "technician_audit_outbox", 1)
        validated.query(
            """
            SELECT last_terminal_command_generation, terminal_ack_delivered
            FROM cell_effective_recipe WHERE cell_id = 'cell-v6'
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(5L, cursor.getLong(0))
            assertEquals(0, cursor.getInt(1))
        }
        validated.close()
    }

    @Test
    fun `deviceReportRevision persists in Room row`() = runTest {
        db =
            Room.inMemoryDatabaseBuilder(context, ViwaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val dao = db!!.cellEffectiveRecipeDao()
        val store =
            CellEffectiveRecipeStore(
                database = db!!,
                dao = dao,
            )
        store.setRuntimeManagedModeActive(true)
        assertEquals(1L, store.nextDeviceReportRevision("cell-rev"))
        assertEquals(1L, store.peekDeviceReportRevision("cell-rev"))
    }

    @Test
    fun `in-memory dao upsert and read round trip with nullable control row`() = runTest {
        db =
            Room.inMemoryDatabaseBuilder(context, ViwaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val dao = db!!.cellEffectiveRecipeDao()

        val control =
            CellEffectiveRecipeEntity.controlOnly(
                cellId = "cell-control",
                cancelThroughGeneration = 9L,
                updatedAtMs = 10L,
            )
        dao.upsert(control)
        val loadedControl = dao.findByCellId("cell-control")
        assertNotNull(loadedControl)
        assertNull(loadedControl!!.baseDrinkVolumeMl)
        assertNull(loadedControl.fingerprint)
        assertEquals(CellEffectiveRecipeSource.UNINITIALIZED.name, loadedControl.source)

        val triple = CellEffectiveRecipeDefaults.legacyTriple
        val fingerprint = RecipeCanonical.fingerprint(triple)
        dao.upsert(
            CellEffectiveRecipeEntity(
                cellId = "cell-room-1",
                baseDrinkVolumeMl = triple.baseDrinkVolumeMl,
                waterDeciMl = triple.waterDeciMl,
                productDeciMl = triple.productDeciMl,
                fingerprint = fingerprint,
                source = CellEffectiveRecipeSource.COMMAND.name,
                productId = "prod-1",
                baseVersionId = "base-1",
                lastAppliedCommandGeneration = 2L,
                cancelThroughGeneration = 1L,
                updatedAtMs = 42L,
            ),
        )
        val loaded = dao.findByCellId("cell-room-1")
        assertNotNull(loaded)
        assertEquals(fingerprint, loaded!!.fingerprint)
    }

    @Test
    fun `migration metadata spans version 4 to 7`() {
        assertEquals(4, ViwaDatabase.MIGRATION_4_5.startVersion)
        assertEquals(5, ViwaDatabase.MIGRATION_4_5.endVersion)
        assertEquals(5, ViwaDatabase.MIGRATION_5_6.startVersion)
        assertEquals(6, ViwaDatabase.MIGRATION_5_6.endVersion)
        assertEquals(6, ViwaDatabase.MIGRATION_6_7.startVersion)
        assertEquals(7, ViwaDatabase.MIGRATION_6_7.endVersion)
        assertEquals(7, ViwaDatabase.MIGRATION_7_8.startVersion)
        assertEquals(8, ViwaDatabase.MIGRATION_7_8.endVersion)
    }

    private fun assertTableRowCount(db: SupportSQLiteDatabase, table: String, expected: Int) {
        db.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            assertEquals(expected, cursor.getInt(0))
        }
    }

    companion object {
        private const val MIGRATION_DB = "migration-recipe-chain"

        fun createProductionVersion3OpenHelper(
            context: Context,
            dbName: String,
        ): SupportSQLiteOpenHelper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(dbName)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(3) {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                db.execSQL(
                                    """
                                    CREATE TABLE IF NOT EXISTS json_data (
                                        name TEXT NOT NULL PRIMARY KEY,
                                        data TEXT NOT NULL
                                    )
                                    """.trimIndent(),
                                )
                                ViwaDatabase.MIGRATION_1_2.migrate(db)
                                ViwaDatabase.MIGRATION_2_3.migrate(db)
                            }

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )

        fun insertRepresentativeProductionV4Rows(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                INSERT INTO json_data (name, data)
                VALUES ('cells.snapshot', '{"cells":[{"uuid":"cell-1"}]}')
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO machine_outbox (
                    local_id, kind, idempotency_key, message_id, payload_json,
                    status, attempts, ws_ack_failures, next_retry_at_ms, created_at_ms
                ) VALUES (
                    'local-1', 'TELEMETRY_POUR_REPORT', 'pour-1', 'msg-1', '{}',
                    'PENDING', 0, 0, 0, 1000
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO entitlement_cache (
                    subject_hash, machine_id, grant_id, subscription_level_id,
                    issued_at_ms, expires_at_ms, daily_remaining_ml_at_issue,
                    max_offline_pours, max_offline_volume_ml, signing_key_id,
                    revocation_epoch, revision, signature, grant_json, revoked, updated_at_ms
                ) VALUES (
                    'subj', 'machine-1', 'grant-1', 'level-1',
                    1000, 9999999, 1000, 5, 3000, 'key-1',
                    0, '1', 'sig', '{}', 0, 1000
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO offline_usage_ledger (
                    request_uuid, grant_id, subject_hash, machine_id, sale_id,
                    drink_id, requested_volume_ml, finalized_volume_ml, state,
                    sold_at_ms, created_at_ms, updated_at_ms
                ) VALUES (
                    'req-1', 'grant-1', 'subj', 'machine-1', 'sale-1',
                    1, 300, 300, 'FINALIZED', 1000, 1000, 1000
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO technician_allowlist_cache (
                    fingerprint, key_id, machine_id, scopes_json, expires_at_ms,
                    expires_at_iso, revocation_epoch, revision, signature,
                    record_json, revoked, updated_at_ms
                ) VALUES (
                    'fp-1', 'key-1', 'machine-1', '[]', NULL,
                    NULL, 0, '1', 'sig', '{}', 0, 1000
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO technician_allowlist_state (
                    id, delta_cursor, revocation_epoch, last_sync_at_ms,
                    server_technician_keys_enabled, offline_scopes_json,
                    online_only_scopes_json, capability_json,
                    has_trusted_allowlist_sync, policy_updated_at_ms
                ) VALUES (
                    1, '0', 0, 1000, 1, '[]', '[]', NULL, 1, 1000
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO technician_audit_outbox (
                    request_uuid, fingerprint, technician_key_id, action, channel,
                    outcome, failure_code, created_at_ms, synced_at_ms, sync_status
                ) VALUES (
                    'audit-1', 'fp-1', 'key-1', 'AUTH', 'OFFLINE',
                    'GRANTED', NULL, 1000, NULL, 'PENDING'
                )
                """.trimIndent(),
            )
        }
    }
}
