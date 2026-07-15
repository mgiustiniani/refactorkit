package org.refactorkit.daemon

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class SavedWorkspaceWatcherStatus(
    val state: String,
    val dirty: Boolean,
    val watchedDirectories: Int,
    val observedEvents: Long,
    val overflowed: Boolean,
    val failure: String? = null,
)

/** Bounded recursive watcher. It records invalidation only; the session worker rescans. */
internal class SavedWorkspaceWatcher private constructor(
    private val root: Path,
    private val service: WatchService,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val dirty = AtomicBoolean(false)
    private val observedEvents = AtomicLong()
    private val overflowed = AtomicBoolean(false)
    private val keys = ConcurrentHashMap<WatchKey, Path>()
    @Volatile private var failure: String? = null
    private val thread = Thread(::runLoop, "refactorkit-workspace-watch").apply {
        isDaemon = true
    }

    init {
        registerTree(root)
        thread.start()
    }

    fun consumeDirty(): Boolean = dirty.getAndSet(false)

    fun markDirty() = dirty.set(true)

    fun status(): SavedWorkspaceWatcherStatus = SavedWorkspaceWatcherStatus(
        state = when {
            running.get() -> "active"
            failure != null -> "failed"
            else -> "closed"
        },
        dirty = dirty.get(),
        watchedDirectories = keys.size,
        observedEvents = observedEvents.get(),
        overflowed = overflowed.get(),
        failure = failure,
    )

    private fun runLoop() {
        try {
            while (running.get()) {
                val key = service.take()
                val directory = keys[key]
                key.pollEvents().forEach { event ->
                    observedEvents.incrementAndGet()
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        overflowed.set(true)
                        dirty.set(true)
                        return@forEach
                    }
                    val context = event.context() as? Path ?: return@forEach
                    val changed = directory?.resolve(context)?.normalize() ?: return@forEach
                    if (ignored(root.relativize(changed))) return@forEach
                    dirty.set(true)
                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE &&
                        Files.isDirectory(changed, LinkOption.NOFOLLOW_LINKS)) {
                        registerTree(changed)
                    }
                }
                if (!key.reset()) keys.remove(key)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (problem: Exception) {
            if (running.get()) {
                failure = problem.javaClass.simpleName
                dirty.set(true)
            }
        } finally {
            running.set(false)
        }
    }

    private fun registerTree(start: Path) {
        if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(start).use { paths ->
            paths.filter { path ->
                Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !ignored(root.relativize(path))
            }.forEach { directory ->
                if (keys.size >= MAX_WATCHED_DIRECTORIES) {
                    overflowed.set(true)
                    dirty.set(true)
                    return@forEach
                }
                val key = directory.register(
                    service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE,
                )
                keys[key] = directory
            }
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { service.close() }
        thread.interrupt()
        thread.join(1_000)
        keys.clear()
    }

    companion object {
        const val MAX_WATCHED_DIRECTORIES = 4_096
        private val IGNORED_SEGMENTS = setOf(
            ".git", ".gradle", ".idea", ".refactorkit", "node_modules",
        )

        fun start(root: Path): Result<SavedWorkspaceWatcher> = runCatching {
            val normalized = root.toAbsolutePath().normalize()
            require(Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) { "workspace root is not a directory" }
            val service = FileSystems.getDefault().newWatchService()
            try {
                SavedWorkspaceWatcher(normalized, service)
            } catch (problem: Exception) {
                runCatching { service.close() }
                throw problem
            }
        }

        private fun ignored(relative: Path): Boolean = relative.any { it.toString() in IGNORED_SEGMENTS }
    }
}
