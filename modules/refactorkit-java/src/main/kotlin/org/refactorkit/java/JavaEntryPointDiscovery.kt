package org.refactorkit.java

import kotlinx.serialization.json.*
import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.ITypeBinding
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.Modifier
import org.refactorkit.core.*
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections

/** Detached native facts; no live compiler binding escapes the parser resource scope. */
data class JavaNativeMainMethod(
    val binaryName: String?, val descriptor: String?, val modifiers: Int,
    val range: SourceRange, val resolved: Boolean,
)
data class JavaNativeEntryPointType(val binaryName: String?, val range: SourceRange?, val resolved: Boolean)
class JavaNativeEntryPointSource(val path: Path, val complete: Boolean, methods: List<JavaNativeMainMethod>,
                               types: List<JavaNativeEntryPointType> = emptyList()) {
    val methods: List<JavaNativeMainMethod> = Collections.unmodifiableList(ArrayList(methods))
    val types: List<JavaNativeEntryPointType> = Collections.unmodifiableList(ArrayList(types))
}

/** JDT ACL. A native binary name is mandatory; qualified-name fallback is forbidden. */
internal object JavaEntryPointNativeProjection {
    fun type(file: SourceFile, node: ASTNode, binding: ITypeBinding?): JavaNativeEntryPointType {
        val binary = binding?.typeDeclaration?.takeUnless { it.isRecovered }?.binaryName?.takeIf { it.isNotBlank() }
        val range = if (node.startPosition >= 0 && node.length > 0 &&
            node.startPosition.toLong() + node.length <= file.content.length)
            TextEdits.rangeForOffset(file.content, node.startPosition, node.length) else null
        return JavaNativeEntryPointType(binary, range, binding != null && !binding.isRecovered && binary != null && range != null)
    }
    fun method(file: SourceFile, node: MethodDeclaration, binding: IMethodBinding?): JavaNativeMainMethod {
        val owner = binding?.declaringClass?.typeDeclaration
        val name = owner?.takeUnless { it.isRecovered }?.binaryName?.takeIf { it.isNotBlank() }
        val parameters = binding?.parameterTypes?.map { descriptor(it) }
        val result = binding?.returnType?.let(::descriptor)
        val shape = if (parameters != null && parameters.all { it != null } && result != null)
            "(" + parameters.joinToString("") + ")" + result else null
        return JavaNativeMainMethod(name, shape, binding?.modifiers ?: 0,
            TextEdits.rangeForOffset(file.content, node.name.startPosition, node.name.length),
            binding != null && !binding.isRecovered && name != null && shape != null)
    }

    private fun descriptor(type: ITypeBinding): String? {
        if (type.isRecovered) return null
        if (type.isArray) {
            if (type.dimensions !in 1..255) return null
            return descriptor(type.elementType)?.let { "[".repeat(type.dimensions) + it }
        }
        if (type.isPrimitive) return when (type.name) {
            "void" -> "V"; "boolean" -> "Z"; "byte" -> "B"; "char" -> "C"; "short" -> "S"
            "int" -> "I"; "long" -> "J"; "float" -> "F"; "double" -> "D"; else -> null
        }
        val erased = type.erasure
        if (erased.isRecovered) return null
        val binary = erased.binaryName?.takeIf { it.isNotBlank() } ?: return null
        return "L" + binary.replace('.', '/') + ";"
    }
}

internal data class JavaEntryPointScope(val providerId: String, val moduleId: String, val sourceSetId: String)
internal data class JavaEntryPointRequest(
    val requestId: String, val context: JavaProjectContextRequest, val scope: JavaEntryPointScope,
    val launchProfile: String, val path: Path?, val limit: Int, val expectedIndexGeneration: Long?,
    val position: SourcePosition?,
    val entryPointSchemaVersion: String = "java-entry-points-v1",
)
internal data class JavaEntryPointCandidate(
    val id: String, val binaryName: String, val path: Path, val range: SourceRange,
    val generated: Boolean, val sourceLevel: Int, val targetLevel: Int,
)
internal data class JavaEntryPointDiscoveryResult(
    val context: JavaBoundContext, val candidates: List<JavaEntryPointCandidate>,
    val total: Int, val launcherRelease: Int, val provenanceHash: String,
    val selection: JavaNativeEntryPointType?,
)

internal sealed interface JavaEntryPointQueryOutcome {
    data class Available(val value: JavaEntryPointDiscoveryResult) : JavaEntryPointQueryOutcome
    data class Refused(val code: String, val message: String) : JavaEntryPointQueryOutcome
}

/** Application service over provider-owned context, captured sources and native compiler facts. */
internal object JavaEntryPointDiscovery {
    const val PROFILE = "classic-declared-v1"
    const val DESCRIPTOR = "([Ljava/lang/String;)V"

    fun query(request: JavaEntryPointRequest, scan: () -> ProjectSnapshot,
              cancellation: SemanticCancellationToken, indexSnapshotHash: String, indexGeneration: Long): JavaEntryPointQueryOutcome {
        fun refusal(code: String, message: String) = JavaEntryPointQueryOutcome.Refused(code, message)
        return try {
            current(cancellation)
            if (request.context.expectedSnapshotHash != indexSnapshotHash)
                return refusal("intelligence.snapshotStale", "Expected workspace index snapshot is stale")
            if (request.expectedIndexGeneration != null && request.expectedIndexGeneration != indexGeneration)
                return refusal("intelligence.indexStale", "Expected workspace index generation is stale")
            if (request.launchProfile != PROFILE)
                return refusal("java.entryPoints.profileUnqualified", "The requested launcher profile has not been qualified")
            val result = JavaBoundContext.read(request.context, scan, cancellation) { context ->
                discover(request, context, cancellation)
            }
            current(cancellation)
            when (result) {
                is JavaBoundReadResult.Refused -> refusal(result.code, result.message)
                is JavaBoundReadResult.Available -> JavaEntryPointQueryOutcome.Available(result.value)
            }
        } catch (failure: JavaContextAuthorityException) {
            refusal(failure.code, failure.message.orEmpty())
        } catch (_: JdtJavaAnalysisCancelledException) {
            refusal("intelligence.cancelled", "Entry-point discovery was cancelled")
        }
    }

    private fun discover(request: JavaEntryPointRequest, context: JavaBoundContext,
                 cancellation: SemanticCancellationToken): JavaEntryPointDiscoveryResult {
        current(cancellation)
        val snapshot = context.snapshot
        val scope = request.scope
        val model = snapshot.buildModels.singleOrNull { it.providerId == scope.providerId }
            ?: fail("scopeUnavailable", "The selected source provider is absent or ambiguous")
        val module = model.modules.singleOrNull { it.id == scope.moduleId }
            ?: fail("scopeUnavailable", "The selected module is absent or ambiguous")
        val set = module.sourceSets.singleOrNull { it.id == scope.sourceSetId }
            ?: fail("scopeUnavailable", "The selected source set is absent or ambiguous")
        val environment = context.view.environments[JavaEffectiveProjectContextProvider.key(scope.providerId, scope.moduleId, scope.sourceSetId)]
            ?: fail("scopeUnavailable", "The selected compiler environment is unavailable")
        fun compilerOption(name: String): String? {
            val options = environment.compilerOptions.filter { it.name == name }
            if (options.size > 1 || options.any { it.redacted })
                fail("targetUnavailable", "The effective compiler $name option is ambiguous or redacted")
            return options.singleOrNull()?.value
        }
        val targetLevel = (compilerOption("release") ?: set.attributes["java.release"]
            ?: compilerOption("target") ?: set.attributes["java.targetLevel"])
            ?.removePrefix("1.")?.toIntOrNull()
            ?: fail("targetUnavailable", "The effective target level is unavailable")
        val sdk = context.view.sdk
        val launcher = sdk.map { it.platformRelease }.distinct().singleOrNull()
            ?: fail("launcherUnavailable", "The selected SDK has no single attested launcher release")
        val configuredHome = runCatching { requireNotNull(request.context.sdkHome).toRealPath() }.getOrNull()
            ?: fail("launcherUnavailable", "The selected SDK is no longer available")
        if (sdk.any { it.platformHome != configuredHome })
            fail("launcherUnavailable", "Compiler/platform and application launcher SDK identities differ")
        val files = snapshot.files.filter { it.languageId == "java" }.filter { source ->
            val owners = snapshot.owningJavaBuildSourceRoots(source.path)
            val selected = owners.filter { it.providerId == scope.providerId && it.module.id == scope.moduleId && it.sourceSet.id == scope.sourceSetId }
            if (selected.isNotEmpty() && owners.size != 1) fail("ownerAmbiguous", "A selected source has ambiguous provider ownership")
            selected.isNotEmpty()
        }.filter { request.path == null || it.path == request.path }
        if (request.path != null && files.size != 1) fail("sourceUnavailable", "The requested source is not uniquely owned by the selected scope")
        val cached = context.cache.get(snapshot, cancellation)
        val sources = cached.analysis.entryPointSources
        val candidates = mutableListOf<JavaEntryPointCandidate>()
        var selection: JavaNativeEntryPointType? = null
        var methodCount = 0
        for (file in files) {
            current(cancellation)
            val facts = sources.singleOrNull { it.path == file.path }
                ?: fail("analysisUnavailable", "The selected source has no complete native analysis receipt")
            if (facts.types.size > 16_384) fail("inputLimit", "The native enclosing-type inventory exceeds its bound")
            if (!facts.complete) fail("analysisUnavailable", "The selected source has compiler errors or recovered syntax")
            val selectedType = request.position?.let { position ->
                current(cancellation)
                val offset = try { TextEdits.offsetOf(file.content, position) } catch (_: IllegalArgumentException) {
                    fail("positionUnavailable", "The requested UTF-16 position is outside the captured source")
                }
                if (offset > 0 && offset < file.content.length &&
                    Character.isHighSurrogate(file.content[offset - 1]) && Character.isLowSurrogate(file.content[offset]))
                    fail("positionUnavailable", "The requested position splits a UTF-16 surrogate pair")
                if (facts.types.any { !it.resolved || it.range == null })
                    fail("analysisUnavailable", "The enclosing-type inventory lacks complete native bindings or ranges")
                val enclosing = facts.types.filter { type ->
                    current(cancellation)
                    requireNotNull(type.range).let { position >= it.start && position < it.end }
                }
                val innermost = enclosing.maxWithOrNull(compareBy<JavaNativeEntryPointType> { requireNotNull(it.range).start }
                    .thenByDescending { requireNotNull(it.range).end })
                    ?: fail("enclosingTypeUnavailable", "The position has no uniquely resolved innermost enclosing type")
                val innerRange = requireNotNull(innermost.range)
                var matchingRanges = 0
                for (type in enclosing) {
                    current(cancellation)
                    val outerRange = requireNotNull(type.range)
                    if (outerRange == innerRange) matchingRanges++
                    if (outerRange.start > innerRange.start || outerRange.end < innerRange.end)
                        fail("enclosingTypeUnavailable", "Native enclosing-type ranges overlap without unique ownership")
                }
                if (matchingRanges != 1) fail("enclosingTypeUnavailable", "Native enclosing-type ownership is ambiguous")
                if (requireNotNull(innermost.binaryName).toByteArray(Charsets.UTF_8).size > 1_024)
                    fail("fieldLimit", "The native enclosing binary name exceeds its UTF-8 bound")
                innermost.also { selection = it }
            }
            for (method in facts.methods) {
                current(cancellation)
                if (++methodCount > 16_384) fail("inputLimit", "The native main-method inventory exceeds its bound")
                if (!method.resolved) fail("analysisUnavailable", "A main declaration lacks complete native method/type bindings")
                if (selectedType != null && method.binaryName != selectedType.binaryName) continue
                if (method.descriptor != DESCRIPTOR || !Modifier.isPublic(method.modifiers) || !Modifier.isStatic(method.modifiers)) continue
                val binary = requireNotNull(method.binaryName)
                if (binary.toByteArray(Charsets.UTF_8).size > 1_024) fail("fieldLimit", "A native binary name exceeds its UTF-8 bound")
                val path = ProtocolPath.serialize(file.path)
                if (path.toByteArray(Charsets.UTF_8).size > 4_096) fail("fieldLimit", "A source path exceeds its UTF-8 bound")
                val start = TextEdits.offsetOf(file.content, method.range.start)
                val end = TextEdits.offsetOf(file.content, method.range.end)
                if (start > end || file.content.substring(start, end) != "main")
                    fail("analysisUnavailable", "The native main source range is invalid")
                val owner = snapshot.owningJavaBuildSourceRoots(file.path).single()
                val identity = listOf("java-entry-points-v1", snapshot.hash, context.view.contextHash, context.contextSchemaVersion,
                    scope.providerId, scope.moduleId, scope.sourceSetId, binary, DESCRIPTOR, path,
                    method.range.toString(), environment.sourceLevel.toString(), targetLevel.toString(), launcher.toString()) +
                    sdk.map { it.identitySha256 }.sorted()
                candidates += JavaEntryPointCandidate("java-entry:" + identitySha(identity), binary, file.path,
                    method.range, owner.generated, environment.sourceLevel, targetLevel)
            }
        }
        if (candidates.groupBy { it.binaryName }.any { it.value.size != 1 })
            fail("ownerAmbiguous", "More than one eligible declaration has the same native binary name in this scope")
        current(cancellation)
        val ordered = candidates.sortedWith(compareBy({ it.binaryName }, { it.path.toString() },
            { it.range.start.line }, { it.range.start.character }))
        return JavaEntryPointDiscoveryResult(context, ordered.take(request.limit), ordered.size, launcher, cached.provenanceHash, selection)
    }

    internal fun current(cancellation: SemanticCancellationToken) {
        if (cancellation.isCancellationRequested()) throw JavaContextAuthorityException("intelligence.cancelled", "Entry-point discovery was cancelled")
    }
    private fun fail(code: String, message: String): Nothing =
        throw JavaContextAuthorityException("java.entryPoints." + code, message)
    private fun identitySha(fields: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (field in fields) {
            val bytes = field.toByteArray(Charsets.UTF_8)
            digest.update((bytes.size.toString() + ":").toByteArray(Charsets.US_ASCII)); digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Explicit protocol ACL: validate/extract, delegate, render. No launcher rules live here. */
object JavaEntryPointQueryProtocol {
    const val PAYLOAD_MAX_BYTES = 1_048_576 - 4_096

    fun query(params: JsonObject, base: JavaProjectContextRequest, scan: () -> ProjectSnapshot,
              cancellation: SemanticCancellationToken, indexSnapshotHash: String, indexGeneration: Long): JsonObject {
        val request = parse(params, base)
        if (request.entryPointSchemaVersion == "java-entry-points-v2")
            return JavaModernEntryPoints.query(request, scan, cancellation, indexSnapshotHash, indexGeneration)
        fun refusal(code: String, message: String) = buildJsonObject {
            put("schemaVersion", 1); put("requestId", request.requestId); put("kind", "entryPoints"); put("status", "refused")
            put("snapshotHash", indexSnapshotHash); put("indexGeneration", indexGeneration)
            put("error", buildJsonObject { put("code", code); put("message", message) })
        }
        return when (val outcome = JavaEntryPointDiscovery.query(request, scan, cancellation, indexSnapshotHash, indexGeneration)) {
            is JavaEntryPointQueryOutcome.Refused -> refusal(outcome.code, outcome.message)
            is JavaEntryPointQueryOutcome.Available -> {
                val response = render(request, outcome.value, indexGeneration)
                if (response.toString().toByteArray(Charsets.UTF_8).size > PAYLOAD_MAX_BYTES)
                    refusal("java.entryPoints.responseLimit", "The complete response exceeds its UTF-8 payload budget")
                else response
            }
        }
    }

    private fun parse(p: JsonObject, base: JavaProjectContextRequest): JavaEntryPointRequest {
        require(p.keys.all { it in setOf("requestId", "kind", "languageId", "expectedSnapshotHash", "expectedIndexGeneration",
            "scope", "javaContext", "launchProfile", "path", "position", "limit", "priority", "entryPointSchemaVersion") }) { "Unknown entry-point request field" }
        fun text(o: JsonObject, key: String): String {
            val value = o[key] as? JsonPrimitive
            require(value != null && value.isString && value.content.isNotBlank() && '\u0000' !in value.content) { key + " must be a nonblank string" }
            return value.content
        }
        require(text(p, "languageId") == "java") { "Entry-point discovery requires languageId=java" }
        val id = text(p, "requestId")
        require(id.length <= ProtocolLimits.MAX_DIAGNOSTICS_REQUEST_ID_CHARS) { "requestId is too long" }
        require(p["priority"] == null || text(p, "priority") in setOf("interactive", "normal", "background")) { "Invalid query priority" }
        val s = requireNotNull(p["scope"] as? JsonObject) { "scope is required" }
        require(s.keys == setOf("providerId", "moduleId", "sourceSetId")) { "scope requires exact provider/module/source-set selectors" }
        val scope = JavaEntryPointScope(text(s, "providerId"), text(s, "moduleId"), text(s, "sourceSetId"))
        require(listOf(scope.providerId, scope.moduleId, scope.sourceSetId).all { it.length <= 1_024 }) { "scope selector exceeds its bound" }
        val path = p["path"]?.let {
            val raw = text(p, "path")
            require(raw.toByteArray(Charsets.UTF_8).size <= 4_096 && '\\' !in raw) { "path exceeds its bound or uses invalid separators" }
            Path.of(raw).also { value ->
                require(!value.isAbsolute && value == value.normalize() && !value.startsWith("..") && value.toString() != ".") { "path must be workspace-relative and normalized" }
            }
        }
        val limit = p["limit"]?.let {
            require(it is JsonPrimitive && !it.isString && it.intOrNull != null) { "limit must be an integer" }
            it.int
        } ?: 100
        require(limit in 1..ProtocolLimits.MAX_SYMBOL_RESULTS) { "limit must be between 1 and 200" }
        val position = p["position"]?.let { value ->
            require(path != null) { "position requires an owned source path" }
            val point = value as? JsonObject ?: throw IllegalArgumentException("position must be an object")
            require(point.keys == setOf("line", "character")) { "position requires exactly line and character" }
            fun coordinate(name: String): Int {
                val number = point[name] as? JsonPrimitive
                require(number != null && !number.isString && number.intOrNull != null && number.int >= 0) {
                    "position coordinates must be nonnegative int32 UTF-16 values"
                }
                return number.int
            }
            SourcePosition(coordinate("line"), coordinate("character"))
        }
        val generation = p["expectedIndexGeneration"]?.let {
            require(it is JsonPrimitive && !it.isString && it.longOrNull != null && it.long >= 1) { "expectedIndexGeneration must be a positive integer" }
            it.long
        }
        val context = base.copy(providerId = scope.providerId, moduleId = scope.moduleId, sourceSetId = scope.sourceSetId,
            requiredFacts = base.requiredFacts + JavaProjectContextFact.COMPILER_OPTIONS)
        val version = p["entryPointSchemaVersion"]?.let { text(p, "entryPointSchemaVersion") } ?: "java-entry-points-v1"
        require(version in setOf("java-entry-points-v1", "java-entry-points-v2")) { "Unknown entryPointSchemaVersion" }
        return JavaEntryPointRequest(id, context, scope, text(p, "launchProfile"), path, limit, generation, position, version)
    }

    private fun render(request: JavaEntryPointRequest, value: JavaEntryPointDiscoveryResult, generation: Long): JsonObject = buildJsonObject {
        val context = value.context
        put("schemaVersion", "java-entry-points-v1"); put("requestId", request.requestId); put("kind", "entryPoints"); put("status", "ready")
        put("snapshotHash", context.snapshot.hash); put("indexGeneration", generation)
        put("contextHash", context.view.contextHash); put("contextSchemaVersion", context.contextSchemaVersion)
        put("providerId", JdtJavaAnalysisCache.PROVIDER_ID); put("backend", JdtJavaAnalysisCache.BACKEND)
        put("evidence", "compiler"); put("provenanceHash", value.provenanceHash)
        put("scope", buildJsonObject {
            put("providerId", request.scope.providerId); put("moduleId", request.scope.moduleId); put("sourceSetId", request.scope.sourceSetId)
        })
        put("platformIdentities", JsonArray(context.view.sdk.map { JsonPrimitive(it.identitySha256) }))
        put("launcherRelease", value.launcherRelease); put("launchProfile", request.launchProfile)
        put("scopeAnalysisComplete", true); put("complete", false)
        put("coverage", buildJsonObject {
            put("qualifiedProfiles", JsonArray(listOf(JsonPrimitive(JavaEntryPointDiscovery.PROFILE))))
            put("unqualifiedProfiles", JsonArray(listOf("inherited-v1", "java25-v1", "compact-preview-v1").map(::JsonPrimitive)))
        })
        put("returnedCount", value.candidates.size); put("totalEligibleCount", value.total)
        value.selection?.let { type -> put("selection", buildJsonObject {
            put("mode", "enclosing-type"); put("path", ProtocolPath.serialize(requireNotNull(request.path)))
            put("position", buildJsonObject {
                put("line", requireNotNull(request.position).line); put("character", request.position.character)
            })
            put("binaryName", requireNotNull(type.binaryName))
            put("range", range(requireNotNull(type.range)))
        }) }
        put("observedEligibleLowerBound", value.total); put("truncated", value.total > value.candidates.size)
        put("candidates", buildJsonArray { value.candidates.forEach { item -> add(buildJsonObject {
            put("entryPointId", item.id); put("binaryName", item.binaryName); put("declaringBinaryName", item.binaryName)
            put("descriptor", JavaEntryPointDiscovery.DESCRIPTOR); put("invocation", "static"); put("inherited", false)
            put("generated", item.generated); put("sourceLevel", item.sourceLevel); put("targetLevel", item.targetLevel)
            put("source", buildJsonObject {
                put("path", ProtocolPath.serialize(item.path))
                put("range", range(item.range))
            })
        }) } })
    }

    private fun range(value: SourceRange): JsonObject = buildJsonObject {
        put("start", buildJsonObject { put("line", value.start.line); put("character", value.start.character) })
        put("end", buildJsonObject { put("line", value.end.line); put("character", value.end.character) })
    }
}
