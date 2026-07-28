package com.viwa.android.data.local.outbox

interface MachineOutboxPersistence {
    suspend fun insert(entry: MachineOutboxEntryEntity): Long

    suspend fun update(entry: MachineOutboxEntryEntity)

    suspend fun listDrainable(nowMs: Long, limit: Int = 50): List<MachineOutboxEntryEntity>

    suspend fun findByMessageId(messageId: String): MachineOutboxEntryEntity?

    suspend fun findByKindAndIdempotencyKey(kind: String, idempotencyKey: String): MachineOutboxEntryEntity?

    suspend fun countPendingOrInFlight(): Int

    suspend fun recoverAllInFlightToPending(): Int

    suspend fun purgeAckedBefore(beforeMs: Long): Int

    suspend fun deleteAckedByMessageIds(messageIds: List<String>): Int
}

class RoomMachineOutboxPersistence(
    private val dao: MachineOutboxDao,
) : MachineOutboxPersistence {
    override suspend fun insert(entry: MachineOutboxEntryEntity): Long = dao.insert(entry)

    override suspend fun update(entry: MachineOutboxEntryEntity) = dao.update(entry)

    override suspend fun listDrainable(nowMs: Long, limit: Int): List<MachineOutboxEntryEntity> =
        dao.listDrainable(nowMs, limit)

    override suspend fun findByMessageId(messageId: String): MachineOutboxEntryEntity? =
        dao.findByMessageId(messageId)

    override suspend fun findByKindAndIdempotencyKey(kind: String, idempotencyKey: String): MachineOutboxEntryEntity? =
        dao.findByKindAndIdempotencyKey(kind, idempotencyKey)

    override suspend fun countPendingOrInFlight(): Int = dao.countPendingOrInFlight()

    override suspend fun recoverAllInFlightToPending(): Int = dao.recoverAllInFlightToPending()

    override suspend fun purgeAckedBefore(beforeMs: Long): Int = dao.purgeAckedBefore(beforeMs)

    override suspend fun deleteAckedByMessageIds(messageIds: List<String>): Int =
        if (messageIds.isEmpty()) 0 else dao.deleteAckedByMessageIds(messageIds)
}
