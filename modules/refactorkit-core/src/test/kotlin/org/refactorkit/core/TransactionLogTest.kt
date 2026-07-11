package org.refactorkit.core

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        if (Files.getFileAttributeView(logDir, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) != null) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
                Files.getPosixFilePermissions(logDir),
            )
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(logDir.resolve("${tx.id.value}.json")),
            )
        }
        val loaded = log.load(tx.id)
        val record = log.loadRecord(tx.id)

        assertNotNull(loaded)
        assertEquals(JournalState.APPLIED, record?.state)
        assertEquals(TransactionJournalRecord.CURRENT_SCHEMA_VERSION, record?.schemaVersion)
        assertEquals(tx.id, loaded.id)
        assertEquals(tx.planId, loaded.planId)
        assertEquals(tx.snapshotHashBefore, loaded.snapshotHashBefore)
        assertEquals(3, loaded.rollbackEdit.edits.size)
    }

    @Test
    fun atomicallyAdvancesJournalLifecycleWithoutTemporaryFiles() {
        val logDir = Files.createTempDirectory("refactorkit-txlog-lifecycle")
        val log = TransactionLog(logDir)
        val transaction = Transaction(
            planId = PlanId("plan-lifecycle"),
            snapshotHashBefore = "hash",
            rollbackEdit = WorkspaceEdit(),
        )
        val prepared = TransactionJournalRecord(
            transaction = transaction,
            operation = "test",
            forwardEdit = WorkspaceEdit(),
            preImages = emptyList(),
            postImages = emptyList(),
            state = JournalState.PREPARED,
        )

        log.prepare(prepared)
        assertEquals(JournalState.PREPARED, log.loadRecord(transaction.id)?.state)
        log.update(prepared.copy(state = JournalState.APPLYING))
        assertEquals(JournalState.APPLYING, log.loadRecord(transaction.id)?.state)
        assertTrue(Files.list(logDir).use { stream -> stream.noneMatch { it.fileName.toString().contains(".tmp-") } })
    }

    @Test
    fun returnsNullForMissingTransaction() {
        val logDir = Files.createTempDirectory("refactorkit-txlog-empty")
        val log = TransactionLog(logDir)
        assertNull(log.load(TransactionId.new()))
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

    @Test
    fun rejectsTransactionIdsOutsideGeneratedGrammar() {
        listOf(
            "../outside",
            "transaction-../../outside",
            "does-not-exist",
            "transaction-00000000-0000-0000-0000-000000000000",
            "transaction-550e8400-e29b-41d4-a716-446655440000.json",
        ).forEach { value ->
            assertNull(TransactionId.parseOrNull(value))
            assertFailsWith<IllegalArgumentException> { TransactionId(value) }
        }
        assertNotNull(TransactionId.parseOrNull("transaction-550e8400-e29b-41d4-a716-446655440000"))
    }

    @Test
    fun rejectsSymbolicLinkTransactionDirectoryWithoutWritingTarget() {
        val root = Files.createTempDirectory("refactorkit-txlog-link")
        val target = Files.createDirectory(root.resolve("outside"))
        val link = root.resolve("transactions")
        try {
            Files.createSymbolicLink(link, target)
        } catch (_: UnsupportedOperationException) {
            return
        }
        val error = assertFailsWith<TransactionLogException> {
            TransactionLog(link).save(Transaction(planId = PlanId("plan-link"), snapshotHashBefore = "hash", rollbackEdit = WorkspaceEdit()))
        }
        assertEquals("transaction.pathUnsafe", error.code)
        assertTrue(Files.list(target).use { it.findAny().isEmpty })
    }

    @Test
    fun rejectsSymbolicLinkTransactionRecord() {
        val logDir = Files.createTempDirectory("refactorkit-txlog-file-link")
        val outside = Files.createTempFile("refactorkit-outside", ".json")
        val id = TransactionId.new()
        val link = logDir.resolve("${id.value}.json")
        try {
            Files.createSymbolicLink(link, outside)
        } catch (_: UnsupportedOperationException) {
            return
        }
        val error = assertFailsWith<TransactionLogException> { TransactionLog(logDir).load(id) }
        assertEquals("transaction.pathUnsafe", error.code)
    }

    @Test
    fun detectsChecksumTampering() {
        val logDir = Files.createTempDirectory("refactorkit-txlog-checksum")
        val log = TransactionLog(logDir)
        val transaction = Transaction(
            planId = PlanId("plan-checksum"),
            snapshotHashBefore = "hash-before",
            rollbackEdit = WorkspaceEdit(),
        )
        log.save(transaction)
        val file = logDir.resolve("${transaction.id.value}.json")
        val persisted = Files.readString(file)
        assertTrue(persisted.contains("\"checksum\""))
        Files.writeString(file, persisted.replace("hash-before", "hash-after"))

        val error = assertFailsWith<TransactionLogException> { log.loadRecord(transaction.id) }

        assertEquals("transaction.quarantined", error.code)
        assertTrue(error.cause?.message?.contains("checksum mismatch") == true)
        val quarantine = logDir.resolve(".quarantine")
        assertTrue(Files.list(quarantine).use { it.count() == 1L })
        val blocked = assertFailsWith<TransactionLogException> { log.list() }
        assertEquals("transaction.quarantined", blocked.code)
    }

    @Test
    fun readsSchemaOneAndMigratesOnLifecycleUpdate() {
        val logDir = Files.createTempDirectory("refactorkit-txlog-migration")
        val log = TransactionLog(logDir)
        val transaction = Transaction(
            planId = PlanId("plan-migration"),
            snapshotHashBefore = "hash",
            rollbackEdit = WorkspaceEdit(),
        )
        val prepared = TransactionJournalRecord(
            transaction = transaction,
            operation = "migration",
            forwardEdit = WorkspaceEdit(),
            preImages = emptyList(),
            postImages = emptyList(),
            state = JournalState.PREPARED,
        )
        log.prepare(prepared)
        val file = logDir.resolve("${transaction.id.value}.json")
        val schemaOne = Files.readString(file)
            .replace("\"schemaVersion\": 3", "\"schemaVersion\": 1")
            .lineSequence()
            .filterNot { it.trimStart().startsWith("\"checksum\"") }
            .joinToString("\n")
            .replace(",\n}", "\n}")
        Files.writeString(file, schemaOne)

        val legacy = log.loadRecord(transaction.id)!!
        assertEquals(1, legacy.schemaVersion)
        log.update(legacy.copy(state = JournalState.APPLYING))

        val migrated = log.loadRecord(transaction.id)!!
        assertEquals(TransactionJournalRecord.CURRENT_SCHEMA_VERSION, migrated.schemaVersion)
        assertTrue(Files.readString(file).contains("\"checksum\""))
    }

    @Test
    fun reportsCorruptTransactionRecordStructurally() {
        val logDir = Files.createTempDirectory("refactorkit-txlog-corrupt")
        val id = TransactionId.new()
        Files.writeString(logDir.resolve("${id.value}.json"), "{not-json")

        val log = TransactionLog(logDir)
        val error = assertFailsWith<TransactionLogException> { log.load(id) }
        assertEquals("transaction.quarantined", error.code)
        assertTrue(!Files.exists(logDir.resolve("${id.value}.json")))
        assertTrue(Files.list(logDir.resolve(".quarantine")).use { it.findAny().isPresent })
        val blocked = assertFailsWith<TransactionLogException> { log.prepare(TransactionJournalRecord(
            transaction = Transaction(planId = PlanId("plan-blocked"), snapshotHashBefore = "hash", rollbackEdit = WorkspaceEdit()),
            operation = "blocked",
            forwardEdit = WorkspaceEdit(),
            preImages = emptyList(),
            postImages = emptyList(),
            state = JournalState.PREPARED,
        )) }
        assertEquals("transaction.quarantined", blocked.code)
    }
}
