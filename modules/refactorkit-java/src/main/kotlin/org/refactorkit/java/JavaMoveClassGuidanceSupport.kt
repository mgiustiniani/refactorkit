package org.refactorkit.java

import org.refactorkit.core.SourceRange
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import kotlin.io.path.invariantSeparatorsPathString

internal val JAVA_MOVE_GUIDANCE_SHA256_PATTERN = Regex("[a-f0-9]{64}")

internal fun javaMoveGuidanceValidateBlockerIdentity(module: String, sourceSet: String, path: Path) {
    require(module.isNotBlank()) { "blocker Maven module must not be blank" }
    require(sourceSet.isNotBlank()) { "blocker source set must not be blank" }
    require(javaMoveGuidanceIsSafeRelative(path)) {
        "blocker path must be normalized and workspace-relative"
    }
}

internal fun javaMoveGuidanceValidateOccurrenceIdentity(
    snapshotSha256: String,
    path: Path,
    sourceRange: SourceRange,
    contentSha256: String,
) {
    javaMoveGuidanceRequireSha256(snapshotSha256, "occurrence snapshot identity")
    require(javaMoveGuidanceIsSafeRelative(path)) {
        "occurrence path must be normalized and workspace-relative"
    }
    require(sourceRange.start < sourceRange.end) { "occurrence range must not be empty" }
    javaMoveGuidanceRequireSha256(contentSha256, "occurrence content identity")
}

internal fun javaMoveGuidanceIsSafeRelative(path: Path): Boolean =
    !path.isAbsolute && path == path.normalize() && !path.startsWith("..")

internal fun javaMoveGuidanceRequireSha256(value: String, label: String) {
    require(JAVA_MOVE_GUIDANCE_SHA256_PATTERN.matches(value)) { "$label must be lowercase SHA-256" }
}

internal fun javaMoveGuidanceRangeIdentity(range: SourceRange): String =
    "${range.start.line}:${range.start.character}-${range.end.line}:${range.end.character}"

internal fun javaMoveGuidanceOccurrenceKey(occurrence: JavaMoveClassGuidanceOccurrence): String =
    "${occurrence::class.qualifiedName}|${occurrence.path.invariantSeparatorsPathString}|" +
        javaMoveGuidanceRangeIdentity(occurrence.sourceRange)

internal fun javaMoveGuidanceSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte) }

internal fun javaMoveGuidanceHashParts(parts: List<Any?>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { part ->
        digest.update(part.toString().toByteArray(Charsets.UTF_8))
        digest.update(0)
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

internal fun <T> javaMoveGuidanceImmutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

internal fun <T> javaMoveGuidanceImmutableSorted(
    values: Collection<T>,
    comparator: Comparator<T>,
): List<T> = javaMoveGuidanceImmutableList(values.sortedWith(comparator))

internal val JAVA_MOVE_GUIDANCE_BLOCKER_ORDER = compareBy<JavaMoveClassGuidanceBlocker> { it.code }
    .thenBy { it.mavenModule }
    .thenBy { it.sourceSet }
    .thenBy { it.path.invariantSeparatorsPathString }

internal val JAVA_MOVE_GUIDANCE_CANDIDATE_ORDER = compareBy<JavaMoveClassGuidanceJavaCandidate> {
    it.path.invariantSeparatorsPathString
}.thenBy { it.sourceRange.start.line }
    .thenBy { it.sourceRange.start.character }
    .thenBy { it.classification.name }

internal val JAVA_MOVE_GUIDANCE_RESIDUAL_ORDER = compareBy<JavaMoveClassGuidanceResidual> {
    it.path.invariantSeparatorsPathString
}.thenBy { it.sourceRange.start.line }
    .thenBy { it.sourceRange.start.character }
    .thenBy { it.kind.name }

internal val JAVA_MOVE_GUIDANCE_OMISSION_ORDER = compareBy<JavaMoveClassGuidanceOmission> { it.kind.name }
    .thenBy { it.mavenModule }
    .thenBy { it.sourceSet }
    .thenBy { it.path.invariantSeparatorsPathString }

internal val JAVA_MOVE_GUIDANCE_FIXED_VCS_CHECKLIST: List<JavaMoveClassVcsChecklistItem> =
    javaMoveGuidanceImmutableList(listOf(
        JavaMoveClassVcsChecklistItem(1, "create a VCS checkpoint"),
        JavaMoveClassVcsChecklistItem(2, "inspect every candidate and omission"),
        JavaMoveClassVcsChecklistItem(3, "restore authority or make only confirmed manual changes"),
        JavaMoveClassVcsChecklistItem(4, "review Java non-code and non-Java residual risks"),
        JavaMoveClassVcsChecklistItem(5, "run appropriate supplemental builds and tests"),
        JavaMoveClassVcsChecklistItem(6, "inspect the final diff"),
        JavaMoveClassVcsChecklistItem(7, "use VCS for recovery"),
    ))
