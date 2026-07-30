package org.refactorkit.java

import org.refactorkit.core.ProjectSnapshot
import java.nio.file.Path

/** Descriptor layers that must remain complete before a selected binary can be classified. */
enum class MavenSelectedDescriptorLayer(val displayName: String) {
    SELECTED_LEAF_POM("selected leaf POM"),
    REQUIRED_PARENT_POM("required parent POM"),
    REQUIRED_IMPORTED_BOM("required imported BOM"),
    DEPENDENCY_MANAGEMENT_MEDIATION_DECLARATION("dependency-management/mediation declaration"),
    ;
}

data class MavenSelectedDescriptorExpectation(
    val path: Path,
    val layer: MavenSelectedDescriptorLayer,
    val contentSha256: String,
) {
    init {
        require(!path.isAbsolute && !path.normalize().startsWith("..")) {
            "Selected-descriptor expectations must use safe workspace-relative paths"
        }
        require(SHA256.matches(contentSha256)) {
            "Selected-descriptor expectation hashes must be SHA-256"
        }
    }

    private companion object {
        val SHA256 = Regex("[a-f0-9]{64}")
    }
}

/**
 * Engine-captured descriptor baseline for one selected external Maven coordinate.
 *
 * A null [parsedDescriptorIdentityHash] is deliberately representable so a provider that
 * loses parsed model/relocation proof fails closed instead of silently assuming no relocation.
 */
data class MavenSelectedDescriptorAuthorityContext(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val descriptorExpectations: List<MavenSelectedDescriptorExpectation>,
    val parsedDescriptorIdentityHash: String?,
) {
    init {
        require(groupId.isNotBlank() && artifactId.isNotBlank() && version.isNotBlank()) {
            "Selected Maven descriptor authority requires a complete coordinate"
        }
        require(descriptorExpectations.isNotEmpty()) {
            "Selected Maven descriptor authority requires descriptor expectations"
        }
        require(
            descriptorExpectations.distinctBy { it.path.normalize() to it.layer }.size ==
                descriptorExpectations.size,
        ) { "Selected Maven descriptor expectations must have unique path/layer identities" }
        require(descriptorExpectations.any { it.layer == MavenSelectedDescriptorLayer.SELECTED_LEAF_POM }) {
            "Selected Maven descriptor authority requires the selected leaf POM"
        }
        require(parsedDescriptorIdentityHash == null || SHA256.matches(parsedDescriptorIdentityHash)) {
            "Parsed selected-descriptor identity must be SHA-256 when present"
        }
    }

    val coordinate: String get() = "$groupId:$artifactId:$version"

    fun withoutParsedDescriptorIdentity(): MavenSelectedDescriptorAuthorityContext =
        copy(parsedDescriptorIdentityHash = null)

    private companion object {
        val SHA256 = Regex("[a-f0-9]{64}")
    }
}

/** Capture only RefactorKit-emitted selected-descriptor facts from a scanner snapshot. */
fun ProjectSnapshot.captureMavenSelectedDescriptorAuthorityContext(
    selectedCoordinate: String,
): MavenSelectedDescriptorAuthorityContext {
    val coordinateParts = selectedCoordinate.split(':')
    require(coordinateParts.size == 3 && coordinateParts.all(String::isNotBlank)) {
        "Selected Maven coordinate must be groupId:artifactId:version"
    }
    val (groupId, artifactId, version) = coordinateParts
    val captures = buildList {
        modules.forEach { module ->
            val attributes = module.languageSettings
            listOf("java.mainClasspath", "java.testClasspath").forEach { classpathPrefix ->
                val count = attributes["$classpathPrefix.missing.evidence.count"]?.toIntOrNull() ?: 0
                repeat(count) { index ->
                    val prefix = "$classpathPrefix.missing.evidence.$index"
                    if (attributes["$prefix.kind"] != "LOCAL_REPOSITORY_SELECTED_LEAF" ||
                        attributes["$prefix.groupId"] != groupId ||
                        attributes["$prefix.artifactId"] != artifactId ||
                        attributes["$prefix.version"] != version
                    ) return@repeat
                    val factCount = attributes["$prefix.descriptorFact.count"]?.toIntOrNull()
                        ?: error("Selected-descriptor fact count is absent for $selectedCoordinate")
                    val facts = (0 until factCount).map { factIndex ->
                        val factPrefix = "$prefix.descriptorFact.$factIndex"
                        MavenSelectedDescriptorExpectation(
                            path = Path.of(requireNotNull(attributes["$factPrefix.path"])),
                            layer = MavenSelectedDescriptorLayer.valueOf(
                                requireNotNull(attributes["$factPrefix.layer"]),
                            ),
                            contentSha256 = requireNotNull(attributes["$factPrefix.sha256"]),
                        )
                    }.sortedWith(
                        compareBy<MavenSelectedDescriptorExpectation> { it.layer.name }
                            .thenBy { it.path.toString() },
                    )
                    add(
                        MavenSelectedDescriptorAuthorityContext(
                            groupId = groupId,
                            artifactId = artifactId,
                            version = version,
                            descriptorExpectations = facts,
                            parsedDescriptorIdentityHash = attributes["$prefix.parsedDescriptorIdentityHash"],
                        ),
                    )
                }
            }
        }
    }
    require(captures.isNotEmpty()) {
        "No RefactorKit selected-descriptor authority facts exist for $selectedCoordinate"
    }
    val distinct = captures.distinct()
    require(distinct.size == 1) {
        "Selected-descriptor authority facts disagree across source-set projections for $selectedCoordinate"
    }
    val captured = distinct.single()
    require(captured.parsedDescriptorIdentityHash != null) {
        "Parsed selected-descriptor identity proof is absent for $selectedCoordinate"
    }
    return captured
}
