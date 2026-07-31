package org.refactorkit.core

import java.util.ArrayList
import java.util.Collections

enum class RollbackLookupVisibility {
    APPLIED_ONLY,
    JOURNAL_RECORD,
}

sealed interface RollbackPreflightDecision<out SurfaceRejection> {
    data object Allow : RollbackPreflightDecision<Nothing>

    data class Reject<out SurfaceRejection>(
        val surfaceRejection: SurfaceRejection,
    ) : RollbackPreflightDecision<SurfaceRejection>
}

fun interface RollbackPreflightGuard<out SurfaceRejection> {
    fun evaluate(record: TransactionJournalRecord): RollbackPreflightDecision<SurfaceRejection>
}

sealed interface ManagedRollbackOutcome<out SurfaceRejection> {
    data class InvalidTransactionId(
        val rawId: String,
    ) : ManagedRollbackOutcome<Nothing>

    data class TransactionNotFound(
        val parsedId: TransactionId,
        val visibility: RollbackLookupVisibility,
    ) : ManagedRollbackOutcome<Nothing>

    data class JournalLookupFailed(
        val parsedId: TransactionId,
        val failure: TransactionLogException,
    ) : ManagedRollbackOutcome<Nothing>

    data class PreflightRejected<out SurfaceRejection>(
        val resolvedRecord: TransactionJournalRecord,
        val surfaceRejection: SurfaceRejection,
    ) : ManagedRollbackOutcome<SurfaceRejection>

    data class RollbackCallJournalFailed(
        val resolvedRecord: TransactionJournalRecord,
        val failure: TransactionLogException,
    ) : ManagedRollbackOutcome<Nothing>

    data class RolledBack(
        val resolvedRecord: TransactionJournalRecord,
        val appliedTransaction: Transaction,
    ) : ManagedRollbackOutcome<Nothing>

    class Refused(
        val resolvedRecord: TransactionJournalRecord,
        diagnostics: List<Diagnostic>,
    ) : ManagedRollbackOutcome<Nothing> {
        private val diagnosticValues: List<Diagnostic> =
            Collections.unmodifiableList(ArrayList(diagnostics))

        val diagnostics: List<Diagnostic> get() = diagnosticValues
    }
}

/**
 * Executes the shared managed-rollback sequence for
 * REQ-MANAGED-ROLLBACK-EXECUTOR-001 and REQ-MANAGED-ROLLBACK-EXECUTOR-002.
 * Surface-specific rendering, lifecycle, refresh, and diagnostics remain with callers.
 */
class ManagedRollbackExecutor(
    private val transactionLog: TransactionLog,
    private val patchEngine: PatchEngine,
    private val transactionIdParser: (String) -> TransactionId? = { rawId ->
        TransactionId.parseOrNull(rawId)
    },
    private val recordLoader: (TransactionLog, TransactionId) -> TransactionJournalRecord? = { log, id ->
        log.loadRecord(id)
    },
    private val rollbackCall: (PatchEngine, Transaction, RollbackMode) -> ApplyResult = { engine, transaction, mode ->
        engine.rollback(transaction, mode)
    },
) {
    fun <SurfaceRejection> execute(
        rawTransactionId: String,
        visibility: RollbackLookupVisibility,
        mode: RollbackMode,
        guard: RollbackPreflightGuard<SurfaceRejection>,
    ): ManagedRollbackOutcome<SurfaceRejection> {
        val transactionId = transactionIdParser(rawTransactionId)
            ?: return ManagedRollbackOutcome.InvalidTransactionId(rawTransactionId)
        val resolvedRecord = try {
            recordLoader(transactionLog, transactionId)
        } catch (failure: TransactionLogException) {
            return ManagedRollbackOutcome.JournalLookupFailed(transactionId, failure)
        }
        val visibleRecord = resolvedRecord?.takeIf { record ->
            visibility == RollbackLookupVisibility.JOURNAL_RECORD || record.state == JournalState.APPLIED
        } ?: return ManagedRollbackOutcome.TransactionNotFound(transactionId, visibility)

        when (val decision = guard.evaluate(visibleRecord)) {
            RollbackPreflightDecision.Allow -> Unit
            is RollbackPreflightDecision.Reject ->
                return ManagedRollbackOutcome.PreflightRejected(visibleRecord, decision.surfaceRejection)
        }

        val result = try {
            rollbackCall(patchEngine, visibleRecord.transaction, mode)
        } catch (failure: TransactionLogException) {
            return ManagedRollbackOutcome.RollbackCallJournalFailed(visibleRecord, failure)
        }
        return when (result) {
            is ApplyResult.Applied -> ManagedRollbackOutcome.RolledBack(visibleRecord, result.transaction)
            is ApplyResult.Refused -> ManagedRollbackOutcome.Refused(visibleRecord, result.diagnostics)
        }
    }
}
