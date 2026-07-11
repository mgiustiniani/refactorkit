package org.refactorkit.core

import kotlin.test.Test
import kotlin.test.assertEquals

class JsonRpcErrorCodesTest {
    @Test
    fun mapsApplyRefusalsToStableCategories() {
        val cases = mapOf(
            "snapshot.scopeChanged" to JsonRpcErrorCodes.SNAPSHOT_CHANGED,
            "file.preconditionChanged" to JsonRpcErrorCodes.SNAPSHOT_CHANGED,
            "edit.overlap" to JsonRpcErrorCodes.PLAN_VALIDATION_FAILED,
            "path.outsideWorkspace" to JsonRpcErrorCodes.UNSAFE_PATH,
            "file.exists" to JsonRpcErrorCodes.FILE_CONFLICT,
            "approval.required" to JsonRpcErrorCodes.APPROVAL_REQUIRED,
            "diagnostics.regression" to JsonRpcErrorCodes.DIAGNOSTICS_FAILED,
            "workspace.locked" to JsonRpcErrorCodes.WORKSPACE_LOCKED,
            "filesystem.capabilityUnsupported" to JsonRpcErrorCodes.FILESYSTEM_UNSUPPORTED,
            "transaction.journalFailed" to JsonRpcErrorCodes.APPLY_FAILED,
            "transaction.recoveryRequired" to JsonRpcErrorCodes.RECOVERY_REQUIRED,
        )

        cases.forEach { (diagnosticCode, expected) ->
            assertEquals(
                expected,
                JsonRpcErrorCodes.applyRefusalCode(listOf(Diagnostic("test", Diagnostic.Severity.ERROR, code = diagnosticCode))),
                diagnosticCode,
            )
        }
    }

    @Test
    fun highestRiskApplyCategoryWinsRegardlessOfDiagnosticOrder() {
        val diagnostics = listOf(
            Diagnostic("validation", Diagnostic.Severity.ERROR, code = "edit.overlap"),
            Diagnostic("recovery", Diagnostic.Severity.ERROR, code = "transaction.recoveryRequired"),
        )

        assertEquals(JsonRpcErrorCodes.RECOVERY_REQUIRED, JsonRpcErrorCodes.applyRefusalCode(diagnostics))
        assertEquals(JsonRpcErrorCodes.RECOVERY_REQUIRED, JsonRpcErrorCodes.applyRefusalCode(diagnostics.reversed()))
    }
}
