package org.refactorkit.core

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Canonical language-neutral identity of one normalized [WorkspaceEdit].
 *
 * File-edit and text-edit order, edit type, exact ranges, replacement/content
 * bytes, overwrite policy, and normalized path components all participate. The
 * length-prefixed encoding prevents field-boundary ambiguity for arbitrary
 * UTF-8 content.
 */
object WorkspaceEditIdentity {
    fun sha256(workspaceEdit: WorkspaceEdit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val normalized = WorkspaceEditSimulator.normalize(workspaceEdit)
        putString(digest, "refactorkit.workspace-edit/v1")
        putInt(digest, normalized.edits.size)
        normalized.edits.forEach { edit ->
            when (edit) {
                is FileEdit.Modify -> {
                    putString(digest, "MODIFY")
                    putPath(digest, edit.path)
                    putInt(digest, edit.textEdits.size)
                    edit.textEdits.forEach { textEdit ->
                        putInt(digest, textEdit.range.start.line)
                        putInt(digest, textEdit.range.start.character)
                        putInt(digest, textEdit.range.end.line)
                        putInt(digest, textEdit.range.end.character)
                        putString(digest, textEdit.newText)
                    }
                }
                is FileEdit.Create -> {
                    putString(digest, "CREATE")
                    putPath(digest, edit.path)
                    putInt(digest, if (edit.overwrite) 1 else 0)
                    putString(digest, edit.content)
                }
                is FileEdit.Delete -> {
                    putString(digest, "DELETE")
                    putPath(digest, edit.path)
                }
                is FileEdit.Rename -> {
                    putString(digest, "RENAME")
                    putPath(digest, edit.path)
                    putPath(digest, edit.newPath)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun putPath(digest: MessageDigest, path: java.nio.file.Path) {
        val normalized = path.normalize()
        putInt(digest, if (normalized.isAbsolute) 1 else 0)
        putString(digest, normalized.root?.toString().orEmpty().replace('\\', '/'))
        putInt(digest, normalized.nameCount)
        normalized.forEach { component -> putString(digest, component.toString()) }
    }

    private fun putString(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        putInt(digest, bytes.size)
        digest.update(bytes)
    }

    private fun putInt(digest: MessageDigest, value: Int) {
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())
    }
}
