package org.refactorkit.java

import kotlinx.serialization.json.*
import org.refactorkit.core.*
import java.lang.reflect.Modifier
import java.security.MessageDigest

/** Opt-in v2 application/protocol boundary; classic negotiation remains independent. */
internal object JavaModernEntryPoints {
    fun query(request: JavaEntryPointRequest, scan: () -> ProjectSnapshot, cancellation: SemanticCancellationToken,
              indexSnapshotHash: String, indexGeneration: Long): JsonObject {
        fun refusal(code: String, message: String) = buildJsonObject {
            put("schemaVersion", 1); put("kind", "entryPoints"); put("status", "refused"); put("requestId", request.requestId)
            put("snapshotHash", indexSnapshotHash); put("indexGeneration", indexGeneration)
            put("error", buildJsonObject { put("code", code); put("message", message) })
        }
        return try {
            JavaEntryPointDiscovery.current(cancellation)
            if (request.context.expectedSnapshotHash != indexSnapshotHash)
                return refusal("intelligence.snapshotStale", "Expected workspace index snapshot is stale")
            if (request.expectedIndexGeneration != null && request.expectedIndexGeneration != indexGeneration)
                return refusal("intelligence.indexStale", "Expected workspace index generation is stale")
            val profile = when (request.launchProfile) {
                "inherited-v1" -> JavaMainLaunchProfile.INHERITED_CLASSIC
                "java25-v1" -> JavaMainLaunchProfile.JAVA25
                else -> return refusal("java.entryPoints.profileUnqualified", "This v2 launcher profile is unqualified")
            }
            val acquired = JavaNativeEntryPointContext.read(request.context, request.scope, scan, cancellation)
            if (acquired is JavaNativeEntryPointContextResolution.Refused) return refusal(acquired.code, acquired.message)
            val value = acquired as JavaNativeEntryPointContextResolution.Available
            val result = discover(request, profile, value, cancellation)
            JavaEntryPointDiscovery.current(cancellation)
            value.context.verifyAfterQuery(scan)?.let { return refusal(it.code, it.message) }
            JavaEntryPointDiscovery.current(cancellation)
            val json = JavaModernEntryPointJson.render(request, value, result, indexGeneration)
            JavaEntryPointDiscovery.current(cancellation)
            if (json.toString().toByteArray(Charsets.UTF_8).size > JavaEntryPointQueryProtocol.PAYLOAD_MAX_BYTES)
                refusal("java.entryPoints.responseLimit", "The complete v2 response exceeds its UTF-8 payload budget") else json
        } catch (failure: JavaContextAuthorityException) {
            refusal(failure.code, failure.message.orEmpty())
        } catch (_: JdtJavaAnalysisCancelledException) {
            refusal("intelligence.cancelled", "Entry-point discovery was cancelled")
        }
    }

    private fun discover(request: JavaEntryPointRequest, profile: JavaMainLaunchProfile,
                         value: JavaNativeEntryPointContextResolution.Available,
                         cancellation: SemanticCancellationToken): JavaModernEntryPointResult {
        val capture = value.capture
        val files = value.selectedSources.filter { request.path == null || it.path == request.path }
        if (request.path != null && files.size != 1) fail("sourceUnavailable", "The source is not uniquely owned by the selected scope")
        val provenance = identity(listOf("java-entry-points-v2", value.context.snapshot.hash, value.context.view.contextHash,
            value.context.contextSchemaVersion, request.scope.providerId, request.scope.moduleId, request.scope.sourceSetId,
            request.launchProfile, value.scopeSnapshot.hash, capture.platformIdentity, capture.launcherSha256,
            capture.compilerSha256, capture.captureContext.workerSha256) + capture.classpathInputs.flatMap { listOf(it.key.toString(), it.value) })
        val candidates = mutableListOf<JavaModernEntryPointCandidate>()
        val ineligible = mutableListOf<JavaModernIneligibleType>()
        var selection: JavaNativeLaunchTypeReadout.Available? = null
        var typeCount = 0
        for (file in files) {
            JavaEntryPointDiscovery.current(cancellation)
            val types = capture.sources.singleOrNull { it.path == file.path }?.types
                ?: fail("analysisUnavailable", "The source lacks a complete native receipt")
            typeCount += types.size
            if (typeCount > 16384) fail("inputLimit", "Native launch-type inventory exceeds its bound")
            val enclosing = request.position?.let { position -> enclosing(file, types, position) }
            if (enclosing != null) selection = enclosing
            for (type in types) {
                JavaEntryPointDiscovery.current(cancellation)
                if (enclosing != null && type !== enclosing) continue
                val binary = type.graph.rootBinaryName
                val selected = when (val outcome = JavaNativeLaunchSelector.select(profile, capture, value.scopeSnapshot.hash,
                    capture.platformIdentity, file.path, binary)) {
                    JavaMainLaunchSelection.Absent -> continue
                    is JavaMainLaunchSelection.Refused -> {
                        if (outcome.reason == JavaMainLaunchRefusal.INSTANCE_CREATION_INELIGIBLE && request.position == null &&
                            (request.path == null || types.size > 1)) {
                            bounded(ProtocolPath.serialize(file.path), 4096)
                            ineligible.add(JavaModernIneligibleType(binary, file, type))
                            continue
                        }
                        val code = if (outcome.reason == JavaMainLaunchRefusal.INSTANCE_CREATION_INELIGIBLE)
                            "instanceCreationIneligible" else "selectionUnavailable"
                        fail(code, "Native launch selection refused: ${outcome.reason.name}")
                    }
                    is JavaMainLaunchSelection.Selected -> outcome
                }
                val method = type.graph.effectiveMainMethods.singleOrNull { it.declaringBinaryName == selected.declaringBinaryName &&
                    it.descriptor == selected.descriptor && it.returnsVoid &&
                    Modifier.isStatic(it.modifiers) == (selected.invocation == JavaMainInvocation.STATIC) }
                    ?: fail("analysisUnavailable", "The selected main has no unique native declaration")
                if (method.origin is JavaNativeDeclarationOrigin.Unavailable)
                    fail("declarationUnavailable", "The selected main has no attested declaration origin")
                bounded(ProtocolPath.serialize(file.path), 4096)
                val generated = value.context.snapshot.owningJavaBuildSourceRoots(file.path).single().generated
                val id = "java-entry:" + identity(listOf(provenance, binary, selected.declaringBinaryName, selected.descriptor,
                    selected.invocation.name, ProtocolPath.serialize(file.path), type.range.toString(), method.origin.toString()))
                candidates.add(JavaModernEntryPointCandidate(id, selected, file, type, method.origin, generated))
            }
        }
        if (candidates.map { it.selection.binaryName }.distinct().size != candidates.size)
            fail("ownerAmbiguous", "More than one selected declaration has the same binary owner")
        val ordered = candidates.sortedWith(compareBy({ it.selection.binaryName }, { it.file.path.toString() },
            { it.type.range.start.line }, { it.type.range.start.character }))
        val exclusions = ineligible.sortedWith(compareBy({ it.binaryName }, { it.file.path.toString() }))
        return JavaModernEntryPointResult(ordered.take(request.limit), ordered.size, provenance, selection,
            exclusions.take(request.limit), exclusions.size)
    }

    private fun enclosing(file: SourceFile, types: List<JavaNativeLaunchTypeReadout.Available>,
                          position: SourcePosition): JavaNativeLaunchTypeReadout.Available {
        val offset = try { TextEdits.offsetOf(file.content, position) } catch (_: IllegalArgumentException) {
            fail("positionUnavailable", "Position is outside the captured UTF-16 source")
        }
        if (offset > 0 && offset < file.content.length && Character.isHighSurrogate(file.content[offset - 1]) &&
            Character.isLowSurrogate(file.content[offset])) fail("positionUnavailable", "Position splits a UTF-16 surrogate pair")
        val enclosing = types.filter { position >= it.range.start && position < it.range.end }
        val inner = enclosing.maxWithOrNull(compareBy<JavaNativeLaunchTypeReadout.Available> { it.range.start }.thenByDescending { it.range.end })
            ?: fail("enclosingTypeUnavailable", "Position has no native enclosing type")
        if (enclosing.count { it.range == inner.range } != 1 || enclosing.any {
                it.range.start > inner.range.start || it.range.end < inner.range.end
            }) fail("enclosingTypeUnavailable", "Native enclosing-type ranges are ambiguous")
        return inner
    }

    private fun bounded(text: String, bytes: Int) { if (text.toByteArray(Charsets.UTF_8).size > bytes) fail("fieldLimit", "Native entry-point field exceeds its UTF-8 bound") }
    private fun identity(fields: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (field in fields) {
            val bytes = field.toByteArray(Charsets.UTF_8)
            digest.update((bytes.size.toString() + ":").toByteArray(Charsets.US_ASCII)); digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun fail(code: String, message: String): Nothing = throw JavaContextAuthorityException("java.entryPoints.$code", message)
}

private data class JavaModernEntryPointCandidate(val id: String, val selection: JavaMainLaunchSelection.Selected,
    val file: SourceFile, val type: JavaNativeLaunchTypeReadout.Available, val declaration: JavaNativeDeclarationOrigin, val generated: Boolean)
private data class JavaModernEntryPointResult(val candidates: List<JavaModernEntryPointCandidate>, val total: Int,
    val provenance: String, val selection: JavaNativeLaunchTypeReadout.Available?,
    val ineligible: List<JavaModernIneligibleType>, val ineligibleCount: Int)
private data class JavaModernIneligibleType(val binaryName: String, val file: SourceFile,
    val type: JavaNativeLaunchTypeReadout.Available)

/** Wire ACL only: no main eligibility, native hierarchy, or source-closure rules. */
private object JavaModernEntryPointJson {
    fun render(request: JavaEntryPointRequest, value: JavaNativeEntryPointContextResolution.Available,
               result: JavaModernEntryPointResult, generation: Long): JsonObject = buildJsonObject {
        val context = value.context; val capture = value.capture
        put("schemaVersion", "java-entry-points-v2"); put("requestId", request.requestId); put("kind", "entryPoints"); put("status", "ready")
        put("snapshotHash", context.snapshot.hash); put("indexGeneration", generation)
        put("contextHash", context.view.contextHash); put("contextSchemaVersion", context.contextSchemaVersion)
        put("providerId", "java-sdk-launcher-v2"); put("backend", "jdk25-javac"); put("evidence", "compiler"); put("provenanceHash", result.provenance)
        put("scope", buildJsonObject { put("providerId", request.scope.providerId); put("moduleId", request.scope.moduleId); put("sourceSetId", request.scope.sourceSetId) })
        put("platformIdentities", JsonArray(context.view.sdk.map { it.identitySha256 }.sorted().map(::JsonPrimitive)))
        put("launcherRelease", 25); put("launchProfile", request.launchProfile)
        put("launcher", buildJsonObject {
            put("sdkHome", context.view.sdk.single { it.identitySha256 == capture.platformIdentity }.platformHome.toString())
            put("executable", capture.provenance.executable.toString()); put("executableSha256", capture.launcherSha256)
            put("compilerSha256", capture.compilerSha256); put("platformIdentity", capture.platformIdentity)
        })
        put("analysis", buildJsonObject {
            put("snapshotHash", value.scopeSnapshot.hash); put("workerSha256", capture.captureContext.workerSha256)
            put("sourceLevel", capture.captureContext.sourceRelease); put("targetLevel", capture.captureContext.targetRelease)
            put("analyzedSourceCount", value.scopeSnapshot.files.size)
            put("classpathInputs", buildJsonArray { capture.classpathInputs.forEach { (path, hash) -> add(buildJsonObject {
                put("path", path.toString()); put("sha256", hash)
            }) } })
        })
        put("scopeAnalysisComplete", true); put("complete", result.total == result.candidates.size && result.ineligibleCount == result.ineligible.size)
        put("coverage", buildJsonObject {
            put("qualifiedProfiles", JsonArray(listOf(JsonPrimitive(request.launchProfile))))
            put("unqualifiedProfiles", JsonArray(listOf(JsonPrimitive("compact-preview-v1"))))
            put("qualifiedCell", buildJsonObject { put("sourceLevel", 25); put("targetLevel", 25); put("platformRelease", 25); put("launcherRelease", 25); put("preview", false) })
        })
        put("returnedCount", result.candidates.size); put("totalEligibleCount", result.total); put("observedEligibleLowerBound", result.total)
        put("truncated", result.total > result.candidates.size)
        put("ineligibleCount", result.ineligibleCount); put("ineligibleTypesTruncated", result.ineligibleCount > result.ineligible.size)
        put("ineligibleTypes", buildJsonArray { result.ineligible.forEach { item -> add(buildJsonObject {
            put("binaryName", item.binaryName); put("reason", "java.entryPoints.instanceCreationIneligible")
            put("source", buildJsonObject { put("path", ProtocolPath.serialize(item.file.path)); put("range", range(item.type.range)) })
        }) } })
        result.selection?.let { selected -> put("selection", buildJsonObject {
            put("mode", "enclosing-type"); put("path", ProtocolPath.serialize(requireNotNull(request.path)))
            put("position", point(requireNotNull(request.position))); put("binaryName", selected.graph.rootBinaryName); put("range", range(selected.range))
        }) }
        put("candidates", buildJsonArray { result.candidates.forEach { candidate -> add(buildJsonObject {
            val main = candidate.selection
            put("entryPointId", candidate.id); put("binaryName", main.binaryName); put("declaringBinaryName", main.declaringBinaryName)
            put("descriptor", main.descriptor); put("invocation", main.invocation.name.lowercase()); put("inherited", main.inherited)
            put("generated", candidate.generated); put("sourceLevel", 25); put("targetLevel", 25)
            put("source", buildJsonObject {
                put("path", ProtocolPath.serialize(candidate.file.path)); put("range", range(candidate.type.range))
                put("form", candidate.type.sourceForm.name.lowercase())
            })
            put("declaration", declaration(candidate.declaration))
            main.constructionDescriptor?.let { descriptor -> put("construction", buildJsonObject { put("binaryName", main.binaryName); put("descriptor", descriptor) }) }
        }) } })
    }

    private fun declaration(origin: JavaNativeDeclarationOrigin): JsonObject = when (origin) {
        is JavaNativeDeclarationOrigin.Source -> buildJsonObject { put("kind", "source"); put("path", ProtocolPath.serialize(origin.path)); put("range", range(origin.range)) }
        is JavaNativeDeclarationOrigin.Binary -> buildJsonObject {
            put("kind", "binary"); put("originKind", origin.kind.name.lowercase()); put("uri", origin.uri)
            origin.moduleName?.let { put("moduleName", it) }; put("inputPath", origin.inputPath.toString()); put("inputSha256", origin.inputSha256)
        }
        is JavaNativeDeclarationOrigin.Unavailable -> error("Unattested declaration cannot be rendered")
    }
    private fun point(position: SourcePosition) = buildJsonObject { put("line", position.line); put("character", position.character) }
    private fun range(range: SourceRange) = buildJsonObject { put("start", point(range.start)); put("end", point(range.end)) }
}
