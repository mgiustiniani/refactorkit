package org.refactorkit.c

import java.nio.file.Path
import java.util.Collections

internal enum class CCompilerReadSourceRefusal { INPUT_LIMIT, INVALID_SOURCE_MAPPING, OWNERSHIP_COLLISION, INVALID_SDK_SCOPE, INVALID_SDK_SOURCE, SOURCE_UNSUPPORTED }
internal sealed interface CCompilerReadSourceResult {
    data class Available(val sources: CCompilerReadSources) : CCompilerReadSourceResult
    data class Refused(val reason: CCompilerReadSourceRefusal) : CCompilerReadSourceResult
}

/** Query-only logical coordinates. SDK names are never materialized or used as patch ownership. */
internal class CCompilerReadSources private constructor(sources: Map<Path, String>, paths: Map<Path, Path>,
    sdkPaths: Map<Path, Path>, sdkRecords: Map<Path, CReadOnlySdkProvenance>, val sdkIdentity: String?) {
    val sources: Map<Path, String> = Collections.unmodifiableMap(LinkedHashMap(sources))
    val logicalPaths: Map<Path, Path> = Collections.unmodifiableMap(LinkedHashMap(paths))
    private val sdkPaths = Collections.unmodifiableMap(LinkedHashMap(sdkPaths))
    private val sdkRecords = Collections.unmodifiableMap(LinkedHashMap(sdkRecords))
    private val sdkLogicalPaths = Collections.unmodifiableMap(sdkPaths.entries.associate { it.value to it.key })
    fun sdkPath(logical: Path): Path? = sdkPaths[logical]
    fun sdkRecord(logical: Path): CReadOnlySdkProvenance? = sdkRecords[logical]
    fun logicalSdkPath(physical: Path): Path? = sdkLogicalPaths[physical]

    companion object {
        private val SDK_PREFIX = Path.of(".refactorkit", "readonly-sdk")
        fun create(workspace: Path, owned: Map<Path, String>, paths: Map<Path, Path>, readonly: Map<Path, String>,
            records: List<CReadOnlySdkProvenance>): CCompilerReadSourceResult {
            fun no(reason: CCompilerReadSourceRefusal) = CCompilerReadSourceResult.Refused(reason)
            when (CReadOnlySdkPolicy.scope(workspace, records.map { CReadOnlySdkRequest(it.root, it.provenance, it.license) })) {
                CReadOnlySdkScope.INPUT_LIMIT -> return no(CCompilerReadSourceRefusal.INPUT_LIMIT)
                CReadOnlySdkScope.INVALID -> return no(CCompilerReadSourceRefusal.INVALID_SDK_SCOPE)
                CReadOnlySdkScope.ADMITTED -> Unit
            }
            if (owned.isEmpty() || paths.keys != owned.keys || paths.values.toSet().size != paths.size ||
                paths.values.any { it.isAbsolute || it != it.normalize() || it.toString().isBlank() || it.startsWith("..") })
                return no(CCompilerReadSourceRefusal.INVALID_SOURCE_MAPPING)
            if (paths.values.any { it.startsWith(SDK_PREFIX) } || readonly.keys.any { it in owned })
                return no(CCompilerReadSourceRefusal.OWNERSHIP_COLLISION)
            if (records.any { !it.identity.matches(Regex("[0-9a-f]{64}")) } || records.map { it.identity }.distinct().size != records.size)
                return no(CCompilerReadSourceRefusal.INVALID_SDK_SCOPE)
            val allSources = owned + readonly
            when (val admission = CHeaderFieldSourcePolicy.check(allSources)) {
                CHeaderFieldSourceAdmission.Admitted -> Unit
                is CHeaderFieldSourceAdmission.Refused -> return no(if (admission.reason == CHeaderFieldRefusal.INPUT_LIMIT)
                    CCompilerReadSourceRefusal.INPUT_LIMIT else CCompilerReadSourceRefusal.SOURCE_UNSUPPORTED)
            }
            val logical = LinkedHashMap(paths)
            val physicalSdk = linkedMapOf<Path, Path>()
            val origins = linkedMapOf<Path, CReadOnlySdkProvenance>()
            val usedRoots = hashSetOf<Path>()
            for (path in readonly.keys.sortedBy { it.toString() }) {
                if (!path.isAbsolute || path != path.normalize() || !path.toString().endsWith(".h"))
                    return no(CCompilerReadSourceRefusal.INVALID_SDK_SOURCE)
                val record = records.singleOrNull { path.startsWith(it.root.resolve("usr/include")) }
                    ?: return no(CCompilerReadSourceRefusal.INVALID_SDK_SOURCE)
                val name = SDK_PREFIX.resolve(record.identity).resolve(record.root.relativize(path))
                if (name in logical.values || physicalSdk.put(name, path) != null)
                    return no(CCompilerReadSourceRefusal.OWNERSHIP_COLLISION)
                logical[path] = name; origins[name] = record; usedRoots.add(record.root)
            }
            if (usedRoots != records.map { it.root }.toSet()) return no(CCompilerReadSourceRefusal.INVALID_SDK_SOURCE)
            return CCompilerReadSourceResult.Available(CCompilerReadSources(allSources, logical, physicalSdk, origins,
                CReadOnlySdkPolicy.sdkBundleIdentity(records)))
        }
    }
}
