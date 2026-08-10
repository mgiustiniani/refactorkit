package org.refactorkit.core

/** Typed result of one caller-owned saved-workspace rescan. */
sealed interface WorkspaceRefreshResult<out T> {
    val snapshot: ProjectSnapshot

    data class Unchanged(
        override val snapshot: ProjectSnapshot,
    ) : WorkspaceRefreshResult<Nothing>

    data class Changed<T>(
        val previousSnapshotHash: String,
        override val snapshot: ProjectSnapshot,
        val preparedState: T,
    ) : WorkspaceRefreshResult<T>
}

/**
 * Stateless refresh ordering shared by long-lived surfaces.
 *
 * Scanning and changed-state preparation are caller ports. The coordinator owns
 * only exact current/next validation, hash comparison, and prepare-on-change
 * ordering. It catches no failure and stores no snapshot, index, lease, or policy.
 */
object WorkspaceRefreshCoordinator {
    fun <T> refresh(
        current: ProjectSnapshot,
        scan: () -> ProjectSnapshot,
        prepareChangedState: (current: ProjectSnapshot, next: ProjectSnapshot) -> T,
    ): WorkspaceRefreshResult<T> {
        val next = scan()
        require(next.workspace.root.toAbsolutePath().normalize() ==
            current.workspace.root.toAbsolutePath().normalize()) {
            "workspace refresh scan changed the authoritative root"
        }
        if (next.hash == current.hash) return WorkspaceRefreshResult.Unchanged(current)
        val prepared = prepareChangedState(current, next)
        return WorkspaceRefreshResult.Changed(current.hash, next, prepared)
    }

    fun refresh(
        current: ProjectSnapshot,
        scan: () -> ProjectSnapshot,
    ): WorkspaceRefreshResult<Unit> = refresh(current, scan) { _, _ -> Unit }
}
