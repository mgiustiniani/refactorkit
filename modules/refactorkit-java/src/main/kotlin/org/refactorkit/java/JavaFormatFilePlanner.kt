package org.refactorkit.java

import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.ToolFactory
import org.eclipse.jdt.core.formatter.CodeFormatter
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants
import org.eclipse.text.edits.DeleteEdit
import org.eclipse.text.edits.InsertEdit
import org.eclipse.text.edits.ReplaceEdit
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.owningBuildSourceRoots
import org.w3c.dom.Element
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** A caller-selected Eclipse profile. Its identity is independent from the source snapshot hash. */
data class JavaFormatterProfile(
    val path: String,
    val profileName: String,
    val xml: String,
    val sha256: String,
) {
    fun arguments(): Map<String, String> = mapOf(
        ARGUMENT_PATH to path,
        ARGUMENT_NAME to profileName,
        ARGUMENT_XML to xml,
    )

    fun options(): Map<String, String> {
        val defaults = DefaultCodeFormatterConstants.getEclipseDefaultSettings()
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val root = document.documentElement
        require(root.tagName == "profiles") { "Formatter profile XML must have a profiles root" }
        val selected = (0 until root.childNodes.length).mapNotNull { root.childNodes.item(it) as? Element }
            .filter { it.tagName == "profile" && it.getAttribute("kind") == "CodeFormatterProfile" && it.getAttribute("name") == profileName }
        require(selected.size == 1) { "Formatter XML must contain exactly one profile with the selected exact name" }
        val profile = selected.single()
        val settings = (0 until profile.getElementsByTagName("setting").length)
            .map { profile.getElementsByTagName("setting").item(it) as Element }
        require(settings.isNotEmpty()) { "Selected formatter profile has no settings" }
        val result = linkedMapOf<String, String>()
        settings.forEach { setting ->
            val key = setting.getAttribute("id")
            require(key.startsWith("org.eclipse.jdt.core.formatter.") && key in defaults) {
                "Selected formatter profile contains an unsupported setting"
            }
            require(setting.hasAttribute("value") && result.putIfAbsent(key, setting.getAttribute("value")) == null) {
                "Selected formatter profile contains a missing or duplicate setting"
            }
        }
        return result
    }

    companion object {
        const val ARGUMENT_PATH = "formatterProfilePath"
        const val ARGUMENT_NAME = "formatterProfileName"
        const val ARGUMENT_XML = "formatterProfileXml"
        private const val MAX_XML_BYTES = 1024 * 1024

        fun create(path: String, profileName: String, xml: String): JavaFormatterProfile {
            require(path.isNotBlank() && path.length <= 1024) { "Formatter profile path is invalid" }
            val relative = Path.of(path)
            require(!relative.isAbsolute && relative.nameCount > 0 && relative.none { it.toString() == ".." } &&
                relative.normalize().toString().replace('\\', '/') == path.replace('\\', '/')) {
                "Formatter profile path must be a normalized workspace-relative path"
            }
            require(profileName.isNotBlank() && profileName.length <= 256 && profileName.none(Char::isISOControl)) {
                "Formatter profile name is invalid"
            }
            require(xml.toByteArray(Charsets.UTF_8).size in 1..MAX_XML_BYTES && '\u0000' !in xml) {
                "Formatter profile XML exceeds its input limit or is empty"
            }
            val identity = listOf("java-formatter-profile-v1", path.replace('\\', '/'), profileName, xml).joinToString("\u0000")
            val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return JavaFormatterProfile(path.replace('\\', '/'), profileName, xml, hash)
        }

        fun fromArguments(arguments: Map<String, String>): JavaFormatterProfile? {
            val keys = setOf(ARGUMENT_PATH, ARGUMENT_NAME, ARGUMENT_XML)
            if (keys.none(arguments::containsKey)) return null
            return create(
                arguments[ARGUMENT_PATH] ?: throw IllegalArgumentException("Formatter profile path is missing"),
                arguments[ARGUMENT_NAME] ?: throw IllegalArgumentException("Formatter profile name is missing"),
                arguments[ARGUMENT_XML] ?: throw IllegalArgumentException("Formatter profile XML is missing"),
            )
        }
    }
}

/** Plans deterministic Eclipse-JDT formatting of one Java compilation unit. */
class JavaFormatFilePlanner(
    private val diagnosticsAdapter: JavaLanguageAdapter = JavaLanguageAdapter(),
) {
    fun preview(snapshot: ProjectSnapshot, relativePath: Path, formatterProfileArguments: Map<String, String>): PatchPlan {
        val profile = try {
            JavaFormatterProfile.fromArguments(formatterProfileArguments)
        } catch (failure: Exception) {
            return refused(
                snapshot,
                "Selected Eclipse formatter profile is invalid: ${failure.message ?: "profile could not be parsed"}",
                "java.formatterProfile.invalid",
            )
        }
        return preview(snapshot, relativePath, profile)
    }

    fun preview(snapshot: ProjectSnapshot, relativePath: Path, formatterProfile: JavaFormatterProfile? = null): PatchPlan {
        val normalizedPath = relativePath.normalize()
        val file = snapshot.files.singleOrNull { it.path.normalize() == normalizedPath }
            ?: return refused(snapshot, "Java source file not found in snapshot: $normalizedPath", "format.fileNotFound")
        if (file.languageId != "java" || !normalizedPath.toString().endsWith(".java")) {
            return refused(snapshot, "File is not a Java compilation unit: $normalizedPath", "format.unsupportedLanguage")
        }
        JavaGeneratedSourcePolicy.reason(file)?.let { reason ->
            return refused(snapshot, "Generated source cannot be formatted: $normalizedPath ($reason)", "java.generatedSource")
        }

        val beforeDiagnostics = diagnosticsAdapter.diagnostics(snapshot)
        val syntaxErrors = beforeDiagnostics.filter { diagnostic ->
            diagnostic.severity == Diagnostic.Severity.ERROR &&
                diagnostic.category == org.refactorkit.core.DiagnosticCategory.SYNTAX &&
                diagnostic.location?.path?.normalize() == normalizedPath
        }
        if (syntaxErrors.isNotEmpty()) {
            return PatchPlan(
                operation = "formatFile",
                status = PatchStatus.REFUSED,
                snapshotHash = snapshot.hash,
                confidence = 0.0,
                summary = "Cannot format $normalizedPath: ${syntaxErrors.size} syntax error(s).",
                affectedFiles = emptySet(),
                workspaceEdit = WorkspaceEdit(),
                diagnosticsBefore = syntaxErrors,
                warnings = listOf("Fix Java syntax errors before formatting."),
                riskLevel = RiskLevel.MEDIUM,
                evidence = RefactoringEvidence.STRUCTURAL,
            )
        }

        val style = try {
            formatterOptions(snapshot, normalizedPath, formatterProfile)
        } catch (failure: Exception) {
            return refused(
                snapshot,
                "Selected Eclipse formatter profile is invalid: ${failure.message ?: "profile could not be parsed"}",
                "java.formatterProfile.invalid",
            )
        }
        val hasBom = file.content.startsWith('\uFEFF')
        val source = if (hasBom) file.content.substring(1) else file.content
        val lineSeparator = if (source.contains("\r\n")) "\r\n" else "\n"
        val formatter = ToolFactory.createCodeFormatter(style.options)
        val formatterEdit = formatter.format(
            CodeFormatter.K_COMPILATION_UNIT,
            source,
            0,
            source.length,
            0,
            lineSeparator,
        ) ?: return refused(snapshot, "Eclipse JDT could not format $normalizedPath", "format.unavailable")
        val formattedBody = applyFormatterEdits(source, formatterEdit)
        val formatted = (if (hasBom) "\uFEFF" else "") + formattedBody
        val warnings = listOf(
            "Formatter backend: Eclipse JDT ${JavaCore::class.java.`package`.implementationVersion ?: "3.44.0"}; style=${style.description}" +
                (style.profileSha256?.let { "; profileSha256=$it" } ?: "") + ".",
            "Formatting does not organize imports; run organize-imports as a separate reviewed operation.",
        )
        if (formatted == file.content) {
            return PatchPlan(
                operation = "formatFile",
                status = PatchStatus.PREVIEW,
                snapshotHash = snapshot.hash,
                confidence = 1.0,
                requiresUserApproval = true,
                summary = "$normalizedPath is already formatted.",
                affectedFiles = emptySet(),
                workspaceEdit = WorkspaceEdit(),
                diagnosticsBefore = beforeDiagnostics,
                diagnosticsAfterPreview = beforeDiagnostics,
                warnings = warnings,
                riskLevel = RiskLevel.LOW,
                evidence = RefactoringEvidence.STRUCTURAL,
            )
        }

        val bomOffset = if (hasBom) 1 else 0
        val minimalEdits = formatterLeafEdits(formatterEdit).mapNotNull { formatterLeaf ->
            val replacement = when (formatterLeaf) {
                is ReplaceEdit -> formatterLeaf.text
                is InsertEdit -> formatterLeaf.text
                is DeleteEdit -> ""
                else -> return@mapNotNull null
            }
            TextEdit(
                TextEdits.rangeForOffset(file.content, formatterLeaf.offset + bomOffset, formatterLeaf.length),
                replacement,
            )
        }
        val edit = WorkspaceEdit(listOf(FileEdit.Modify(normalizedPath, minimalEdits)))
        val staged = WorkspaceEditSimulator.apply(snapshot, edit)
        val afterDiagnostics = diagnosticsAdapter.diagnostics(staged)
        return PatchPlan(
            operation = "formatFile",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            requiresUserApproval = true,
            summary = "Format Java compilation unit $normalizedPath.",
            affectedFiles = setOf(normalizedPath),
            workspaceEdit = edit,
            diagnosticsBefore = beforeDiagnostics,
            diagnosticsAfterPreview = afterDiagnostics,
            warnings = warnings,
            riskLevel = RiskLevel.LOW,
            evidence = RefactoringEvidence.STRUCTURAL,
        )
    }

    private fun formatterOptions(snapshot: ProjectSnapshot, path: Path, selectedProfile: JavaFormatterProfile?): FormatterStyle {
        val buildOwner = snapshot.owningBuildSourceRoots(path).singleOrNull()
        val module = if (buildOwner == null) snapshot.modules
            .filter { module -> module.sourceRoots.any { root -> path.startsWith(root.normalize()) } }
            .maxByOrNull { module -> module.sourceRoots.maxOfOrNull { it.nameCount } ?: 0 }
        else null
        val options = DefaultCodeFormatterConstants.getEclipseDefaultSettings().toMutableMap()
        val sourceLevel = buildOwner?.sourceSet?.attributes?.get("java.sourceLevel")
            ?: module?.languageSettings?.get("java.sourceLevel")
            ?: JavaCore.VERSION_1_8
        options[JavaCore.COMPILER_SOURCE] = sourceLevel
        options[JavaCore.COMPILER_COMPLIANCE] = sourceLevel
        options[JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM] = sourceLevel

        if (selectedProfile != null) {
            options.putAll(selectedProfile.options())
            return FormatterStyle(options, "${selectedProfile.profileName} (${selectedProfile.path})", selectedProfile.sha256)
        }

        val moduleRoot = (buildOwner?.module?.root ?: module?.root)?.toAbsolutePath()?.normalize()
        val prefs = moduleRoot?.resolve(".settings/org.eclipse.jdt.core.prefs")
            ?.takeIf { Files.isRegularFile(it) }
        if (prefs != null) {
            val properties = Properties()
            Files.newBufferedReader(prefs, Charsets.UTF_8).use(properties::load)
            properties.stringPropertyNames()
                .filter { it.startsWith("org.eclipse.jdt.core.formatter.") }
                .sorted()
                .forEach { key -> options[key] = properties.getProperty(key) }
            return FormatterStyle(options, snapshot.workspace.root.relativize(prefs).toString().replace('\\', '/'), null)
        }
        return FormatterStyle(options, "RefactorKit versioned Eclipse default", null)
    }

    private fun formatterLeafEdits(root: org.eclipse.text.edits.TextEdit): List<org.eclipse.text.edits.TextEdit> {
        val leaves = mutableListOf<org.eclipse.text.edits.TextEdit>()
        fun collect(edit: org.eclipse.text.edits.TextEdit) {
            if (edit.hasChildren()) edit.children.forEach(::collect) else leaves += edit
        }
        collect(root)
        return leaves
    }

    private fun applyFormatterEdits(source: String, root: org.eclipse.text.edits.TextEdit): String {
        val result = StringBuilder(source)
        formatterLeafEdits(root).sortedByDescending { it.offset }.forEach { edit ->
            when (edit) {
                is ReplaceEdit -> result.replace(edit.offset, edit.offset + edit.length, edit.text)
                is InsertEdit -> result.insert(edit.offset, edit.text)
                is DeleteEdit -> result.delete(edit.offset, edit.offset + edit.length)
            }
        }
        return result.toString()
    }

    private fun refused(snapshot: ProjectSnapshot, message: String, code: String) = PatchPlan(
        operation = "formatFile",
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        summary = message,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        diagnosticsBefore = listOf(Diagnostic(message, Diagnostic.Severity.ERROR, code = code)),
        warnings = listOf(message),
        riskLevel = RiskLevel.MEDIUM,
        evidence = RefactoringEvidence.STRUCTURAL,
    )

    private data class FormatterStyle(
        val options: MutableMap<String, String>,
        val description: String,
        val profileSha256: String?,
    )
}
