package org.refactorkit.core

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Kernel32Util
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Native-qualified directory metadata flush used by WAL and workspace commits. */
internal object FilesystemDurability {
    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    fun forceDirectory(directory: Path) {
        if (windows) forceWindowsDirectory(directory) else {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        }
    }

    private fun forceWindowsDirectory(directory: Path) {
        val handle = Kernel32.INSTANCE.CreateFile(
            directory.toAbsolutePath().normalize().toString(),
            WinNT.GENERIC_READ or WinNT.GENERIC_WRITE,
            WinNT.FILE_SHARE_READ or WinNT.FILE_SHARE_WRITE or WinNT.FILE_SHARE_DELETE,
            null,
            WinNT.OPEN_EXISTING,
            WinNT.FILE_FLAG_BACKUP_SEMANTICS,
            null,
        )
        if (WinBase.INVALID_HANDLE_VALUE == handle) {
            val code = Kernel32.INSTANCE.GetLastError()
            throw IllegalStateException("CreateFile(directory) failed [$code]: ${Kernel32Util.formatMessage(code)}")
        }
        try {
            if (!Kernel32.INSTANCE.FlushFileBuffers(handle)) {
                val code = Kernel32.INSTANCE.GetLastError()
                throw IllegalStateException("FlushFileBuffers(directory) failed [$code]: ${Kernel32Util.formatMessage(code)}")
            }
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle)
        }
    }
}
