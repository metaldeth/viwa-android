package com.viwa.android.data.local.outbox

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "machine_outbox",
    indices = [
        Index(value = ["kind", "idempotency_key"], unique = true),
        Index(value = ["status", "next_retry_at_ms"]),
        Index(value = ["message_id"]),
    ],
)
data class MachineOutboxEntryEntity(
    @PrimaryKey
    @ColumnInfo(name = "local_id")
    val localId: String,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    /** Stable transport id — reused across retry attempts for this row. */
    @ColumnInfo(name = "message_id")
    val messageId: String,
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "attempts")
    val attempts: Int = 0,
    @ColumnInfo(name = "ws_ack_failures")
    val wsAckFailures: Int = 0,
    @ColumnInfo(name = "next_retry_at_ms")
    val nextRetryAtMs: Long = 0L,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
    @ColumnInfo(name = "session_generation_at_send")
    val sessionGenerationAtSend: Long? = null,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "acked_at_ms")
    val ackedAtMs: Long? = null,
    @ColumnInfo(name = "in_flight_since_ms")
    val inFlightSinceMs: Long? = null,
)
