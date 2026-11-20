package org.refactorkit.c

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Pure provenance encoding; validation and native artifact capture are separate. */
internal object ClangToolchainIdentity {
    fun of(provenance: ClangToolchainProvenance): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.frame("refactorkit.clang.toolchain.identity.v3")
        digest.frame(provenance.providerId)
        digest.frame(provenance.clangVersion)
        digest.frame(provenance.clangdVersion)
        digest.frame(provenance.clangFormatVersion)
        digest.frame(provenance.targetTriple)
        digest.frame(provenance.resourceDir)
        digest.frame(provenance.nativeRuntimeIdentity)
        val records = provenance.evidence.map { it.copy(path = it.path.normalize()) }.sortedWith(
            compareBy<ClangToolchainFileEvidence> { it.role }.thenBy { it.path.toString() }
                .thenBy { it.size }.thenBy { it.sha256 })
        digest.update(ByteBuffer.allocate(4).putInt(records.size).array())
        for (record in records) {
            digest.frame(record.role)
            digest.frame(record.path.toString())
            digest.update(ByteBuffer.allocate(8).putLong(record.size).array())
            digest.frame(record.sha256)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun MessageDigest.frame(value: String?) {
        if (value == null) update(0.toByte()) else {
            val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
            update(1.toByte())
            update(ByteBuffer.allocate(4).putInt(bytes.remaining()).array())
            update(bytes)
        }
    }
}
