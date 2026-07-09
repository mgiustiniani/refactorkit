package org.refactorkit.core

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TransactionLogTest {

    @Test
    fun savesAndLoadsTransaction() {
        val logDir = Files.createTempDirectory("refactorkit-txlog")
        val log = TransactionLog(logDir)

        val tx = Transaction(
            planId = PlanId("plan-abc"),
            snapshotHashBefore = "hash-before",
            rollbackEdit = WorkspaceEdit(
                listOf(
                    FileEdit.Create(Paths.get("src/Foo.java"), "content", overwrite = true),
                    FileEdit.Delete(Paths.get("src/Bar.java")),
                    FileEdit.Rename(Paths.get("src/Baz.java"), Paths.get("src/Qux.java")),
                ),
            ),
        )

        log.save(tx)
        val loaded = log.load(tx.id)

        assertNotNull(loaded)
        assertEquals(tx.id, loaded.id)
        assertEquals(tx.planId, loaded.planId)
        assertEquals(tx.snapshotHashBefore, loaded.snapshotHashBefore)
        assertEquals(3, loaded.rollbackEdit.edits.size)
    }

    @Test
    fun returnsNullForMissingTransaction() {
        val logDir = Files.createTempDirectory("refactorkit-txlog-empty")
        val log = TransactionLog(logDir)
        assertNull(log.load(TransactionId("does-not-exist")))
    }

    @Test
    fun listsAndDeletesTransactions() {
        val logDir = Files.createTempDirectory("refactorkit-txlog-list")
        val log = TransactionLog(logDir)

        val tx = Transaction(
            planId = PlanId("plan-xyz"),
            snapshotHashBefore = "hash",
            rollbackEdit = WorkspaceEdit(),
        )
        log.save(tx)
        assertEquals(1, log.list().size)

        log.delete(tx.id)
        assertEquals(0, log.list().size)
    }

    @Test
    fun roundTripsContentWithSpecialCharacters() {
        val logDir = Files.createTempDirectory("refactorkit-txlog-special")
        val log = TransactionLog(logDir)

        val content = "package com.example;\n\"quoted\"\n\t\u0000tab\r\nnewline\\"
        val tx = Transaction(
            planId = PlanId("plan-special"),
            snapshotHashBefore = "hash",
            rollbackEdit = WorkspaceEdit(listOf(FileEdit.Create(Paths.get("Foo.java"), content, overwrite = true))),
        )
        log.save(tx)
        val loaded = log.load(tx.id)!!
        val create = loaded.rollbackEdit.edits.first() as FileEdit.Create
        assertEquals(content, create.content)
    }
}
