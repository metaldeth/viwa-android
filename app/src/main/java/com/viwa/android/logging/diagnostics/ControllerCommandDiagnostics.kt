package com.viwa.android.logging.diagnostics

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

interface ControllerCommandDiagnosticsListener {
    fun nextOpId(): Long

    fun onOpBegin(
        opId: Long,
        cmdCode: Int,
    )

    fun onOpWritten(
        opId: Long,
        cmdCode: Int,
        writeMs: Long,
        mock: Boolean,
    )

    fun onOpFailed(
        opId: Long,
        cmdCode: Int,
        errorType: String,
    )
}

@Singleton
class ControllerCommandDiagnostics
@Inject
constructor(
    private val breadcrumbStore: DiagnosticBreadcrumbStore,
) : ControllerCommandDiagnosticsListener {
    private val opIdSeq = AtomicLong(0L)

    override fun nextOpId(): Long = opIdSeq.incrementAndGet()

    override fun onOpBegin(
        opId: Long,
        cmdCode: Int,
    ) {
        breadcrumbStore.recordControllerOp(opId, "begin", formatCmd(cmdCode))
    }

    override fun onOpWritten(
        opId: Long,
        cmdCode: Int,
        writeMs: Long,
        mock: Boolean,
    ) {
        val cmdHex = formatCmd(cmdCode)
        val mockSuffix = if (mock) " mock=true" else ""
        Timber.tag(TAG).i(
            "controller.op.written opId=%d cmd=%s writeMs=%d%s",
            opId,
            cmdHex,
            writeMs,
            mockSuffix,
        )
        breadcrumbStore.recordControllerOp(opId, "written", cmdHex, writeMs)
    }

    override fun onOpFailed(
        opId: Long,
        cmdCode: Int,
        errorType: String,
    ) {
        val cmdHex = formatCmd(cmdCode)
        Timber.tag(TAG).w(
            "controller.op.failed opId=%d cmd=%s errorType=%s",
            opId,
            cmdHex,
            errorType,
        )
        breadcrumbStore.recordControllerOp(opId, "failed:$errorType", cmdHex)
    }

    private fun formatCmd(cmdCode: Int): String = "0x%02x".format(cmdCode)

    companion object {
        private const val TAG = "ViwaController"
    }
}

object NoOpControllerCommandDiagnostics : ControllerCommandDiagnosticsListener {
    private val opIdSeq = AtomicLong(0L)

    override fun nextOpId(): Long = opIdSeq.incrementAndGet()

    override fun onOpBegin(
        opId: Long,
        cmdCode: Int,
    ) = Unit

    override fun onOpWritten(
        opId: Long,
        cmdCode: Int,
        writeMs: Long,
        mock: Boolean,
    ) = Unit

    override fun onOpFailed(
        opId: Long,
        cmdCode: Int,
        errorType: String,
    ) = Unit
}
