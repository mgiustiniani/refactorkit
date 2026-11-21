package org.refactorkit.java

import org.eclipse.jdt.core.dom.Annotation
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.IAnnotationBinding
import org.eclipse.jdt.core.dom.ITypeBinding
import org.eclipse.jdt.core.dom.IVariableBinding
import org.eclipse.jdt.core.dom.Modifier
import org.eclipse.jdt.core.dom.NormalAnnotation
import org.eclipse.jdt.core.dom.SingleMemberAnnotation
import org.eclipse.jdt.core.dom.StringLiteral
import org.eclipse.jdt.core.dom.TypeDeclaration
import org.eclipse.jdt.core.dom.VariableDeclarationFragment
import org.refactorkit.core.BuildFactAvailability
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.ClasspathEvidenceKind
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.OwnedSourceCaptureException
import org.refactorkit.core.OwnedSourceInventoryReader
import org.refactorkit.core.PlanReferenceAnalysis
import org.refactorkit.core.PlanReferenceCompleteness
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SourceSetKind
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditIdentity
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.owningBuildSourceRoots
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import javax.lang.model.SourceVersion
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal data class ManagedJpqlRename(
    val references: PlanReferenceAnalysis,
    val lease: OperationAuthorityLease,
)

internal object JavaManagedJpqlRename {
    fun prove(
        snapshot: ProjectSnapshot,
        targetPath: Path,
        owner: String,
        from: String,
        to: String,
        edit: WorkspaceEdit,
        preview: PlanReferenceAnalysis,
    ): ManagedJpqlRename? {
        if (preview.references.isEmpty() || !persistentIdentifier(from) || !persistentIdentifier(to)) return null
        if (snapshot.semanticAnalysisAuthority !is JavaContextAuthority || !hasClosedMavenInputs(snapshot)) return null

        val before = inspect(snapshot, targetPath, owner, from) ?: return null
        if (before.queryLocations != preview.references.map { it.location }.toSet()) return null
        val after = inspect(WorkspaceEditSimulator.apply(snapshot, edit), targetPath, owner, to) ?: return null
        if (before.column != after.column || before.queryLocations.size != after.queryLocations.size) return null
        val scope = "java-spring-data-jpql-maven-field-closure-v1"
        return ManagedJpqlRename(
            PlanReferenceAnalysis(
                preview.schemaVersion, scope, PlanReferenceCompleteness.COMPLETE, true, "managed-stable",
                preview.references, listOf("The captured Maven workspace proves every supported JPQL property use. The explicit database column stays unchanged."),
            ),
            OperationAuthorityLease(
                kind = scope,
                operation = "renameMember",
                snapshotHash = snapshot.hash,
                evidenceHash = snapshot.hash,
                workspaceEditSha256 = WorkspaceEditIdentity.sha256(edit),
                requiredClasspathEvidence = snapshot.classpathEvidence,
                attributes = mapOf(OperationAuthorityLease.REQUIRE_EXACT_WORKSPACE_INVENTORY to "true"),
            ),
        )
    }

    private data class EntityMapping(val column: String, val idType: String)
    private data class PropertyClosure(val column: String, val queryLocations: Set<SourceLocation>)

    private fun hasClosedMavenInputs(snapshot: ProjectSnapshot): Boolean {
        val pom = snapshot.auxiliaryFiles.singleOrNull() ?: return false
        if (pom.path != Path.of("pom.xml") || pom.languageId != "maven-pom" || !closedPom(pom.content)) return false
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val model = snapshot.buildModels.singleOrNull() ?: return false
        if (model.providerId != "maven-effective-v1" || model.status != BuildModelStatus.AVAILABLE ||
            model.diagnostics.isNotEmpty() || model.attributes["buildCodeExecution"] != "denied" ||
            model.attributes[JAVA_SAFE_DELETE_TEXT_SCAN_STATUS_KEY] != "complete" ||
            model.attributes[JAVA_SAFE_DELETE_TEXT_SCAN_BINARY_INPUTS_KEY] != "0" ||
            model.attributes[JAVA_SAFE_DELETE_TEXT_SCAN_UNMODELED_INPUTS_KEY] != "0"
        ) return false
        val module = model.modules.singleOrNull() ?: return false
        if (root.resolve(module.root).normalize() != root) return false
        val main = module.sourceSets.singleOrNull { it.kind == SourceSetKind.MAIN } ?: return false
        val artifacts = main.resolvedArtifacts
        if (main.resolvedArtifactAvailability != BuildFactAvailability.COMPLETE ||
            artifacts.map { it.path.normalize() }.toSet() != main.classpathEntries.map(Path::normalize).toSet() ||
            artifacts.any { artifact ->
                !frameworkArtifact(artifact.coordinates.groupId, artifact.coordinates.artifactId) ||
                    artifact.extension != "jar" || artifact.classifier != null ||
                    snapshot.classpathEvidence.none { evidence ->
                        evidence.path.normalize() == artifact.path.normalize() &&
                            evidence.kind in ARTIFACT_EVIDENCE
                    }
            }
        ) return false
        val processors = main.annotationProcessors ?: return false
        if (processors.availability != BuildFactAvailability.COMPLETE || processors.mode != "none" ||
            processors.processorPath.isNotEmpty() || processors.processorPathEntries.isNotEmpty() || processors.processorNames.isNotEmpty()
        ) return false
        if (module.sourceSets.any { set ->
                set.generatedSourceRoots.isNotEmpty() || set.outputDirectories.any { Files.exists(root.resolve(it)) }
            } || main.sourceRootEvidence.size != main.sourceRoots.size ||
            main.sourceRootEvidence.any { it.availability != BuildFactAvailability.COMPLETE }
        ) return false
        if (snapshot.files.any { file ->
                val ownership = snapshot.owningBuildSourceRoots(file.path).singleOrNull()
                file.languageId != "java" || ownership == null || ownership.generated ||
                    ownership.providerId != model.providerId || ownership.sourceSet.kind != SourceSetKind.MAIN ||
                    JavaGeneratedSourcePolicy.reason(file) != null
            }
        ) return false

        val expected = snapshot.trackedFiles.mapTo(hashSetOf()) { it.path.normalize() }
        return try {
            OwnedSourceInventoryReader.captureWorkspaceFilePaths(snapshot) == expected
        } catch (_: OwnedSourceCaptureException) {
            false
        }
    }

    private fun inspect(snapshot: ProjectSnapshot, targetPath: Path, owner: String, property: String): PropertyClosure? {
        val parser = JdtJavaSemanticAnalyzer()
        val entity = parser.withBoundCompilationUnit(snapshot, targetPath) { unit, _ ->
            topLevel(unit)?.let { inspectEntity(it, owner, property) }
        }.resolved() ?: return null
        val locations = linkedSetOf<SourceLocation>()
        for (file in snapshot.files) {
            if (file.path == targetPath) continue
            val references = parser.withBoundCompilationUnit(snapshot, file.path) { unit, _ ->
                topLevel(unit)?.let { inspectRepository(it, file, owner, entity.idType, property) }
            }.resolved() ?: return null
            locations.addAll(references)
        }
        return PropertyClosure(entity.column, locations)
    }

    private fun <T> JdtBoundCompilationUnitResolution<T>.resolved(): T? =
        (this as? JdtBoundCompilationUnitResolution.Available)?.value

    private fun topLevel(unit: CompilationUnit): TypeDeclaration? {
        if (unit.problems.any { it.isError }) return null
        val type = unit.types().singleOrNull() as? TypeDeclaration ?: return null
        if (type.resolveBinding()?.isRecovered != false || type.typeParameters().isNotEmpty() ||
            type.types.isNotEmpty() || type.bodyDeclarations().size != type.fields.size + type.methods.size
        ) return null
        return type
    }

    private fun inspectEntity(type: TypeDeclaration, owner: String, property: String): EntityMapping? {
        if (type.isInterface || Modifier.isAbstract(type.modifiers) || Modifier.isFinal(type.modifiers) ||
            type.resolveBinding()?.qualifiedName != owner || type.superclassType != null || type.superInterfaceTypes().isNotEmpty()
        ) return null
        if (type.methods.isEmpty() && !Modifier.isPublic(type.modifiers)) return null
        if (type.methods.size > 1 || type.methods.any {
                !it.isConstructor || !(Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers)) ||
                    it.parameters().isNotEmpty() || it.modifiers().any { modifier -> modifier is Annotation } ||
                    it.body?.statements()?.isEmpty() != true
            }
        ) return null

        val mapping = annotations(type.modifiers()) ?: return null
        if (mapping.keys !in setOf(setOf(ENTITY, ACCESS), setOf(ENTITY, ACCESS, TABLE)) ||
            mapping[ENTITY]?.declaredMemberValuePairs?.isNotEmpty() != false
        ) return null
        val access = mapping[ACCESS]?.onlyValue("value") as? IVariableBinding ?: return null
        if (!access.isEnumConstant || access.name != "FIELD" || !external(access.declaringClass, "jakarta.persistence.AccessType")) return null
        if (TABLE in mapping && (mapping[TABLE]?.onlyValue("name") as? String).isNullOrBlank()) return null

        var idType: String? = null
        var column: String? = null
        for (field in type.fields) {
            if (field.modifiers != Modifier.PRIVATE) return null
            val variable = field.fragments().singleOrNull() as? VariableDeclarationFragment ?: return null
            val binding = variable.resolveBinding() ?: return null
            if (binding.isRecovered || !binding.isField || variable.initializer != null) return null
            val annotations = annotations(field.modifiers()) ?: return null
            if (binding.name == property) {
                if (binding.type.qualifiedName != "java.lang.String" || annotations.keys != setOf(COLUMN)) return null
                column = annotations[COLUMN]?.onlyValue("name") as? String ?: return null
                if (column.isBlank()) return null
            } else {
                if (annotations.keys != setOf(ID) || idType != null ||
                    annotations[ID]?.declaredMemberValuePairs?.isNotEmpty() != false ||
                    binding.type.qualifiedName !in ID_TYPES
                ) return null
                idType = binding.type.qualifiedName
            }
        }
        return EntityMapping(column ?: return null, idType ?: return null)
    }

    private fun inspectRepository(
        type: TypeDeclaration,
        file: SourceFile,
        owner: String,
        idType: String,
        property: String,
    ): List<SourceLocation>? {
        if (!type.isInterface || type.fields.isNotEmpty() || type.modifiers().any { it is Annotation } || type.methods.isEmpty()) return null
        val supertype = type.resolveBinding()?.interfaces?.singleOrNull() ?: return null
        if (!external(supertype.erasure, "org.springframework.data.jpa.repository.JpaRepository") ||
            supertype.typeArguments.map(ITypeBinding::getQualifiedName) != listOf(owner, idType)
        ) return null
        return type.methods.flatMap { method ->
            if (method.body != null || method.parameters().isNotEmpty() || method.typeParameters().isNotEmpty()) return null
            val result = method.resolveBinding()?.returnType ?: return null
            if (result.qualifiedName != "java.lang.String" &&
                !(result.erasure.qualifiedName == "java.util.List" && result.typeArguments.singleOrNull()?.qualifiedName == "java.lang.String")
            ) return null
            val annotation = method.modifiers().filterIsInstance<Annotation>().singleOrNull() ?: return null
            val binding = annotation.resolveAnnotationBinding() ?: return null
            if (!external(binding.annotationType, QUERY) || binding.allMemberValuePairs.find { it.name == "nativeQuery" }?.value != false) return null
            val literal = when (annotation) {
                is SingleMemberAnnotation -> annotation.value as? StringLiteral
                is NormalAnnotation -> (annotation.values().singleOrNull() as? org.eclipse.jdt.core.dom.MemberValuePair)
                    ?.takeIf { it.name.identifier == "value" }?.value as? StringLiteral
                else -> null
            } ?: return null
            val value = literal.literalValue
            if (file.content.substring(literal.startPosition, literal.startPosition + literal.length) != "\"$value\"") return null
            val query = QUERY_SHAPE.matchEntire(value) ?: return null
            val alias = query.groups["rootAlias"]!!.value
            val selected = query.groups["selected"]!!
            val filtered = query.groups["filtered"]
            if (query.groups["entity"]!!.value != owner.substringAfterLast('.') || !persistentIdentifier(alias) ||
                !alias.equals(query.groups["selectAlias"]!!.value, ignoreCase = true) || selected.value != property ||
                (filtered != null && (filtered.value != property || !alias.equals(query.groups["filterAlias"]!!.value, ignoreCase = true)))
            ) return null
            listOfNotNull(selected, filtered).map { token ->
                SourceLocation(file.path, TextEdits.rangeForOffset(file.content, literal.startPosition + 1 + token.range.first, token.value.length))
            }
        }
    }

    private fun annotations(modifiers: List<*>): Map<String, IAnnotationBinding>? {
        val bindings = linkedMapOf<String, IAnnotationBinding>()
        for (modifier in modifiers.filterIsInstance<Annotation>()) {
            val binding = modifier.resolveAnnotationBinding() ?: return null
            val type = binding.annotationType
            if (type.isRecovered || type.isFromSource || bindings.put(type.qualifiedName, binding) != null) return null
        }
        return bindings
    }

    private fun IAnnotationBinding.onlyValue(name: String): Any? =
        declaredMemberValuePairs.singleOrNull()?.takeIf { it.name == name }?.value

    private fun external(type: ITypeBinding, name: String): Boolean =
        !type.isRecovered && !type.isFromSource && type.qualifiedName == name

    private fun persistentIdentifier(name: String): Boolean =
        IDENTIFIER.matches(name) && !SourceVersion.isKeyword(name) && name.lowercase() !in JPQL_KEYWORDS

    private fun closedPom(text: String): Boolean {
        return try {
            if (text.contains("${'$'}{")) return false
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val pom = factory.newDocumentBuilder().parse(InputSource(StringReader(text))).documentElement
            val sections = pom.parts("modelVersion", "groupId", "artifactId", "version", "packaging", "properties", "dependencies", "build")
                ?: return false
            if (pom.tagName != "project" || sections["modelVersion"]?.scalar() != "4.0.0" ||
                (sections["packaging"]?.scalar() ?: "jar") != "jar"
            ) return false
            val properties = sections["properties"]
            if (properties != null && properties.parts("maven.compiler.release", "maven.compiler.proc") == null) return false
            val compiler = sections["build"]?.parts("plugins")?.get("plugins")?.parts("plugin")?.get("plugin")
                ?.parts("groupId", "artifactId", "version", "configuration") ?: return false
            if ((compiler["groupId"]?.scalar() ?: "org.apache.maven.plugins") != "org.apache.maven.plugins" ||
                compiler["artifactId"]?.scalar() != "maven-compiler-plugin" ||
                compiler["version"]?.scalar()?.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) != true
            ) return false
            val args = compiler["configuration"]?.parts("release", "proc") ?: return false
            if (args["release"]?.scalar()?.toIntOrNull() == null || args["proc"]?.scalar() != "none") return false
            val dependencies = sections["dependencies"] ?: return false
            dependencies.attributes.length == 0 && dependencies.elements().all { dependency ->
                val fields = dependency.parts("groupId", "artifactId", "version", "scope", "systemPath") ?: return@all false
                dependency.tagName == "dependency" &&
                    frameworkArtifact(fields["groupId"]?.scalar(), fields["artifactId"]?.scalar()) &&
                    fields["version"]?.scalar()?.isNotBlank() == true &&
                    (fields["scope"]?.scalar() ?: "compile") in setOf("compile", "provided", "system")
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun Element.elements(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private fun Element.parts(vararg allowed: String): Map<String, Element>? {
        if (tagName != "project" && attributes.length != 0) return null
        val children = elements()
        val byName = children.associateBy(Element::getTagName)
        return byName.takeIf { it.size == children.size && allowed.toSet().containsAll(it.keys) }
    }

    private fun Element.scalar(): String? =
        textContent.trim().takeIf { attributes.length == 0 && elements().isEmpty() }

    private fun frameworkArtifact(group: String?, artifact: String?): Boolean = when (group) {
        "jakarta.persistence" -> artifact == "jakarta.persistence-api"
        "org.springframework.data" -> artifact in setOf("spring-data-jpa", "spring-data-commons")
        "org.springframework" -> artifact == "spring-core"
        else -> false
    }

    private val ARTIFACT_EVIDENCE = setOf(ClasspathEvidenceKind.SYSTEM_PATH_ARTIFACT, ClasspathEvidenceKind.LOCAL_REPOSITORY_ARTIFACT)
    private val ID_TYPES = setOf("java.lang.Long", "java.lang.Integer", "long", "int")
    private const val ENTITY = "jakarta.persistence.Entity"
    private const val ACCESS = "jakarta.persistence.Access"
    private const val TABLE = "jakarta.persistence.Table"
    private const val COLUMN = "jakarta.persistence.Column"
    private const val ID = "jakarta.persistence.Id"
    private const val QUERY = "org.springframework.data.jpa.repository.Query"
    private const val WORD = "[A-Za-z][A-Za-z0-9_]*"
    private val IDENTIFIER = Regex(WORD)
    private val QUERY_SHAPE = Regex(
        "\\s*(?i:select)\\s+(?<selectAlias>$WORD)\\s*\\.\\s*(?<selected>$WORD)" +
            "\\s+(?i:from)\\s+(?<entity>$WORD)\\s+(?:(?i:as)\\s+)?(?<rootAlias>$WORD)" +
            "(?:\\s+(?i:where)\\s+(?<filterAlias>$WORD)\\s*\\.\\s*(?<filtered>$WORD)\\s+(?i:is)(?:\\s+(?i:not))?\\s+(?i:null))?\\s*",
    )
    private val JPQL_KEYWORDS = """
        abs all and any as asc avg between bit_length both by case char_length character_length class coalesce concat count
        current_date current_time current_timestamp delete desc distinct else empty end entry escape exists false fetch from
        group having in index inner is join key leading left length like local_date local_datetime local_time locate lower
        max member min mod new not null nullif object of on or order outer position right select set size some sqrt
        substring sum then trailing treat trim true type union update upper value when where
    """.trim().split(Regex("\\s+")).toSet()
}
