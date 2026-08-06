package com.viwa.android.domain.model

/** Строка таблицы наполнения MVP (telemetryCellsSnapshot). */
data class MvpInventoryTableRow(
    val uuid: String,
    val cellNumber: Int,
    val productUuid: String?,
    val productName: String?,
    val tasteMediaKey: String?,
    val price300Kopecks: Int?,
    val price700Kopecks: Int?,
    val volumeMl: Int,
    val blockVolume: Int,
    val sosVolume: Int,
    val maxVolume: Int,
    val volumeStatus: CellVolumeStatus,
    val conversionFactor: Double = TelemetryCell.DEFAULT_CONVERSION_FACTOR,
)

data class MvpInventoryContentUpdate(
    val cellUuid: String,
    val productUuid: String?,
    val dosage1PriceKopecks: Int?,
    val dosage2PriceKopecks: Int?,
)

fun TelemetryCell.toMvpInventoryTableRow(): MvpInventoryTableRow =
    MvpInventoryTableRow(
        uuid = uuid,
        cellNumber = cellNumber,
        productUuid = productUuid,
        productName = productName,
        tasteMediaKey = tasteMediaKey,
        price300Kopecks = dosage1Price,
        price700Kopecks = dosage2Price,
        volumeMl = volume,
        blockVolume = blockVolume,
        sosVolume = sosVolume,
        maxVolume = maxVolume,
        volumeStatus = resolveCellVolumeStatus(volume, blockVolume, sosVolume),
        conversionFactor = conversionFactor,
    )

fun mapMvpInventoryFromSnapshot(snapshot: TelemetryCellsSnapshot?): Pair<List<MvpInventoryTableRow>, List<TelemetryProduct>> {
    if (snapshot == null) return emptyList<MvpInventoryTableRow>() to emptyList()
    val rows = snapshot.cells.sortedBy { it.cellNumber }.map { it.toMvpInventoryTableRow() }
    return rows to snapshot.products
}
