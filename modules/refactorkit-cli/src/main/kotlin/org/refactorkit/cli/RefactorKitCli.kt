package org.refactorkit.cli

import org.refactorkit.core.ApplyResult
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPreviewRenderer
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactorKitVersion
import org.refactorkit.core.TransactionId
import org.refactorkit.core.TransactionLog
import org.refactorkit.core.TransactionLogException
import org.refactorkit.java.JavaChangeSignaturePlanner
import org.refactorkit.java.JavaExtractMethodPlanner
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaMoveClassPlanner
import org.refactorkit.java.JavaOrganizeImportsPlanner
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JavaRenameClassPlanner
import org.refactorkit.java.JavaRenameMemberPlanner
import org.refactorkit.java.JavaSafeDeletePlanner
import org.refactorkit.java.recipe.RecipeEngine
import org.refactorkit.java.recipe.RecipeLoader
import org.refactorkit.java.recipe.RecipeResult
import org.refactorkit.testkit.GoldenTestLoader
import org.refactorkit.testkit.GoldenTestRunner
import org.refactorkit.treesitter.GenericLocalRenamePlanner
import org.refactorkit.treesitter.GenericProjectScanner
import org.refactorkit.treesitter.GenericStructuralSearch
import org.refactorkit.treesitter.TreeSitterAdapter
import org.refactorkit.webimporter.ExternalJavaClassImporter
import org.refactorkit.webimporter.ImportRequest
import org.refactorkit.webimporter.LicensePolicy
import org.refactorkit.webimporter.SourceKind
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText

fun main(args: Array<String>) {
    val code = RefactorKitCli().run(args.toList())
    if (code != 0) kotlin.system.exitProcess(code)
}

class RefactorKitCli(
    private val scanner: JavaProjectScanner = JavaProjectScanner(),
    private val javaAdapter: JavaLanguageAdapter = JavaLanguageAdapter(),
) {
    fun run(args: List<String>): Int {
        if (args.isEmpty() || args.first() in listOf("--help", "-h", "help")) {
            printHelp(); return 0
        }
        if (args.first() in listOf("--version", "-v", "version")) {
            printVersion(); return 0
        }
        return when (args.first()) {
            "scan"            -> cmdScan(args.drop(1))
            "index"           -> cmdScan(args.drop(1))
            "symbols"         -> cmdSymbols(args.drop(1))
            "diagnostics"     -> cmdDiagnostics(args.drop(1))
            "references"      -> cmdReferences(args.drop(1))
            "definition"      -> cmdDefinition(args.drop(1))
            "rename"          -> cmdRename(args.drop(1))
            "rename-member"   -> cmdRenameMember(args.drop(1))
            "extract-method"  -> cmdExtractMethod(args.drop(1))
            "change-signature"-> cmdChangeSignature(args.drop(1))
            "move-class"      -> cmdMoveClass(args.drop(1))
            "organize-imports"-> cmdOrganizeImports(args.drop(1))
            "safe-delete"     -> cmdSafeDelete(args.drop(1))
            "patch"           -> cmdPatch(args.drop(1))
            "java"            -> cmdJava(args.drop(1))
            "recipe"          -> cmdRecipe(args.drop(1))
            "outline"         -> cmdOutline(args.drop(1))
            "search"          -> cmdSearch(args.drop(1))
            "local-rename"    -> cmdLocalRename(args.drop(1))
            "test-golden"     -> cmdTestGolden(args.drop(1))
            else -> { System.err.println("Unknown command: ${args.first()}"); printHelp(); 2 }
        }
    }

    // ── scan ─────────────────────────────────────────────────────────────────

    private fun cmdScan(args: List<String>): Int {
        val root = positional(args, 0) ?: "."
        val path = Paths.get(root)
        if (!path.exists()) { System.err.println("Path does not exist: $path"); return 1 }
        val snap = scanner.scan(path)
        println("Project : ${snap.workspace.root}")
        println("Files   : ${snap.files.size}")
        println("Modules : ${snap.modules.size}")
        println("Snapshot: ${snap.hash}")
        return 0
    }

    // ── symbols ───────────────────────────────────────────────────────────────

    private fun cmdSymbols(args: List<String>): Int {
        val snap = scanFrom(positional(args, 0) ?: ".") ?: return 1
        val index = javaAdapter.buildSymbols(snap)
        if (index.symbols.isEmpty()) { println("No symbols found."); return 0 }
        index.symbols.forEach { sym ->
            println("${sym.kind}\t${sym.id.value}\t${sym.location.path}:${sym.location.range.start.line + 1}")
        }
        return 0
    }

    // ── diagnostics ───────────────────────────────────────────────────────────

    private fun cmdDiagnostics(args: List<String>): Int {
        val snap = scanFrom(positional(args, 0) ?: ".") ?: return 1
        val diags = javaAdapter.diagnostics(snap)
        if (diags.isEmpty()) { println("No diagnostics."); return 0 }
        diags.forEach { println("${it.severity}: ${it.message}") }
        return if (diags.any { it.severity.name == "ERROR" }) 1 else 0
    }

    // ── references ────────────────────────────────────────────────────────────

    private fun cmdReferences(args: List<String>): Int {
        val parsed = parseOptions(args)
        val symbol = parsed.options["symbol"] ?: run { System.err.println("--symbol required"); return 2 }
        val snap = scanFrom(parsed.positionals.firstOrNull() ?: ".") ?: return 1
        val refs = javaAdapter.findReferences(snap, org.refactorkit.core.SymbolId(symbol))
        if (refs.isEmpty()) { println("No references found for $symbol."); return 0 }
        refs.forEach { ref -> println("${ref.location.path}:${ref.location.range.start.line + 1}") }
        return 0
    }

    // ── definition ────────────────────────────────────────────────────────────

    private fun cmdDefinition(args: List<String>): Int {
        val parsed = parseOptions(args)
        val symbol = parsed.options["symbol"] ?: run { System.err.println("--symbol required"); return 2 }
        val snap = scanFrom(parsed.positionals.firstOrNull() ?: ".") ?: return 1
        val sym = javaAdapter.findSymbol(snap, org.refactorkit.core.SymbolId(symbol))
        if (sym == null) { println("Symbol not found: $symbol"); return 1 }
        println("${sym.location.path}:${sym.location.range.start.line + 1}")
        return 0
    }

    // ── rename ────────────────────────────────────────────────────────────────

    private fun cmdRename(args: List<String>): Int {
        val parsed = parseOptions(args)
        val symbol = parsed.options["symbol"] ?: run { System.err.println("rename requires --symbol"); return 2 }
        val newName = parsed.options["to"] ?: run { System.err.println("rename requires --to"); return 2 }
        val root = parsed.positionals.firstOrNull() ?: "."
        val snap = scanFrom(root) ?: return 1
        val plan = JavaRenameClassPlanner(javaAdapter).preview(snap, symbol, newName)
        val renderer = PatchPreviewRenderer(snap.workspace.root)
        println(renderer.render(plan))
        if (plan.status == PatchStatus.REFUSED) return 1
        if ("apply" in parsed.flags) return applyPlanAndLog(plan, snap, root)
        println("Use --apply to apply this change.")
        return 0
    }

    // ── rename-member ─────────────────────────────────────────────────────────

    private fun cmdRenameMember(args: List<String>): Int {
        val parsed = parseOptions(args)
        val symbol = parsed.options["symbol"] ?: run { System.err.println("rename-member requires --symbol <FQN#member>"); return 2 }
        val newName = parsed.options["to"] ?: run { System.err.println("rename-member requires --to <newName>"); return 2 }
        val root = parsed.positionals.firstOrNull() ?: "."
        val snap = scanFrom(root) ?: return 1
        val plan = JavaRenameMemberPlanner(javaAdapter).preview(snap, symbol, newName)
        println(PatchPreviewRenderer(snap.workspace.root).render(plan))
        if (plan.status == PatchStatus.REFUSED) return 1
        if ("apply" in parsed.flags) return applyPlanAndLog(plan, snap, root)
        println("Use --apply to apply this change.")
        return 0
    }

    // ── extract-method ───────────────────────────────────────────────────────

    private fun cmdExtractMethod(args: List<String>): Int {
        val parsed = parseOptions(args)
        val file = parsed.options["file"] ?: parsed.positionals.firstOrNull()
            ?: run { System.err.println("extract-method requires --file <path>"); return 2 }
        val startLine = parsed.options["start-line"]?.toIntOrNull()
            ?: run { System.err.println("extract-method requires --start-line <1-based line>"); return 2 }
        val endLine = parsed.options["end-line"]?.toIntOrNull()
            ?: run { System.err.println("extract-method requires --end-line <1-based line>"); return 2 }
        val methodName = parsed.options["method-name"]
            ?: parsed.options["name"]
            ?: run { System.err.println("extract-method requires --method-name <name>"); return 2 }
        val root = parsed.options["root"] ?: "."
        val snap = scanFrom(root) ?: return 1
        val rootPath = snap.workspace.root
        val filePath = Paths.get(file)
        val relativeFile = if (filePath.isAbsolute) {
            try { rootPath.relativize(filePath.toAbsolutePath().normalize()) } catch (_: Exception) { filePath }
        } else filePath
        val plan = JavaExtractMethodPlanner().preview(snap, relativeFile, startLine, endLine, methodName)
        println(PatchPreviewRenderer(rootPath).render(plan))
        if (plan.status == PatchStatus.REFUSED) return 1
        if ("apply" in parsed.flags) return applyPlanAndLog(plan, snap, root)
        println("Use --apply to apply this change.")
        return 0
    }

    // ── change-signature ─────────────────────────────────────────────────────

    private fun cmdChangeSignature(args: List<String>): Int {
        val parsed = parseOptions(args)
        val operation = parsed.options["operation"] ?: "rename-parameter"
        val symbol = parsed.options["symbol"]
            ?: run { System.err.println("change-signature requires --symbol <FQN#method>"); return 2 }
        val root = parsed.positionals.firstOrNull() ?: parsed.options["root"] ?: "."
        val snap = scanFrom(root) ?: return 1
        val planner = JavaChangeSignaturePlanner(javaAdapter)
        val plan = when (operation) {
            "rename-parameter", "renameParameter", "changeSignature.renameParameter" -> {
                val oldName = parsed.options["old-name"]
                    ?: parsed.options["old-param"]
                    ?: run { System.err.println("change-signature rename-parameter requires --old-name <param>"); return 2 }
                val newName = parsed.options["new-name"]
                    ?: parsed.options["new-param"]
                    ?: parsed.options["to"]
                    ?: run { System.err.println("change-signature rename-parameter requires --new-name <param>"); return 2 }
                planner.previewRenameParameter(snap, symbol, oldName, newName)
            }
            "add-parameter", "addParameter", "changeSignature.addParameter" -> {
                val type = parsed.options["type"]
                    ?: parsed.options["parameter-type"]
                    ?: run { System.err.println("change-signature add-parameter requires --type <javaType>"); return 2 }
                val name = parsed.options["name"]
                    ?: parsed.options["param-name"]
                    ?: parsed.options["parameter-name"]
                    ?: run { System.err.println("change-signature add-parameter requires --name <param>"); return 2 }
                val defaultExpression = parsed.options["default"]
                    ?: parsed.options["default-expression"]
                    ?: run { System.err.println("change-signature add-parameter requires --default <expression>"); return 2 }
                planner.previewAddParameter(snap, symbol, type, name, defaultExpression)
            }
            "reorder-parameters", "reorderParameters", "changeSignature.reorderParameters" -> {
                val order = parsed.options["order"]
                    ?: parsed.options["new-order"]
                    ?: run { System.err.println("change-signature reorder-parameters requires --order <param1,param2,...>"); return 2 }
                planner.previewReorderParameters(snap, symbol, order.split(','))
            }
            "remove-parameter", "removeParameter", "changeSignature.removeParameter" -> {
                val name = parsed.options["name"]
                    ?: parsed.options["param-name"]
                    ?: parsed.options["parameter-name"]
                    ?: run { System.err.println("change-signature remove-parameter requires --name <param>"); return 2 }
                planner.previewRemoveParameter(snap, symbol, name)
            }
            else -> {
                System.err.println("change-signature supports --operation rename-parameter, add-parameter, reorder-parameters, or remove-parameter")
                return 2
            }
        }
        println(PatchPreviewRenderer(snap.workspace.root).render(plan))
        if (plan.status == PatchStatus.REFUSED) return 1
        if ("apply" in parsed.flags) return applyPlanAndLog(plan, snap, root)
        println("Use --apply to apply this change.")
        return 0
    }

    // ── move-class ────────────────────────────────────────────────────────────

    private fun cmdMoveClass(args: List<String>): Int {
        val parsed = parseOptions(args)
        val symbol = parsed.options["symbol"] ?: run { System.err.println("move-class requires --symbol"); return 2 }
        val toPkg = parsed.options["to-package"] ?: run { System.err.println("move-class requires --to-package"); return 2 }
        val root = parsed.positionals.firstOrNull() ?: "."
        val snap = scanFrom(root) ?: return 1
        val plan = JavaMoveClassPlanner(javaAdapter).preview(snap, symbol, toPkg)
        println(PatchPreviewRenderer(snap.workspace.root).render(plan))
        if (plan.status == PatchStatus.REFUSED) return 1
        if ("apply" in parsed.flags) return applyPlanAndLog(plan, snap, root)
        println("Use --apply to apply this change.")
        return 0
    }

    // ── organize-imports ──────────────────────────────────────────────────────

    private fun cmdOrganizeImports(args: List<String>): Int {
        val parsed = parseOptions(args)
        val filePaths = parsed.positionals.map(Paths::get)
        if (filePaths.isEmpty()) { System.err.println("organize-imports requires one or more file paths"); return 2 }
        val root = parsed.options["root"] ?: "."
        val snap = scanFrom(root) ?: return 1
        val plan = JavaOrganizeImportsPlanner().preview(snap, filePaths)
        println(PatchPreviewRenderer(snap.workspace.root).render(plan))
        if (plan.status == PatchStatus.REFUSED) return 1
        if ("apply" in parsed.flags) return applyPlanAndLog(plan, snap, root)
        println("Use --apply to apply this change.")
        return 0
    }

    // ── safe-delete ───────────────────────────────────────────────────────────

    private fun cmdSafeDelete(args: List<String>): Int {
        val parsed = parseOptions(args)
        val symbol = parsed.options["symbol"] ?: run { System.err.println("safe-delete requires --symbol"); return 2 }
        val force = "force" in parsed.flags
        val root = parsed.positionals.firstOrNull() ?: "."
        val snap = scanFrom(root) ?: return 1
        val plan = JavaSafeDeletePlanner(javaAdapter).preview(snap, symbol, force)
        println(PatchPreviewRenderer(snap.workspace.root).render(plan))
        if (plan.status == PatchStatus.REFUSED) return 1
        if ("apply" in parsed.flags) return applyPlanAndLog(plan, snap, root)
        println("Use --apply to apply this change.")
        return 0
    }

    // ── patch ─────────────────────────────────────────────────────────────────

    private fun cmdPatch(args: List<String>): Int {
        if (args.isEmpty()) { System.err.println("patch requires a subcommand: rollback"); return 2 }
        return when (args.first()) {
            "rollback" -> cmdRollback(args.drop(1))
            else -> { System.err.println("Unknown patch subcommand: ${args.first()}"); 2 }
        }
    }

    private fun cmdRollback(args: List<String>): Int {
        val parsed = parseOptions(args)
        val txId = parsed.positionals.firstOrNull()
            ?: parsed.options["transaction"]
            ?: run { System.err.println("patch rollback requires a transaction ID"); return 2 }
        val root = parsed.options["root"] ?: "."
        val workspaceRoot = Paths.get(root).toAbsolutePath().normalize()
        val transactionId = TransactionId.parseOrNull(txId)
            ?: run { System.err.println("Invalid transaction ID: $txId"); return 2 }
        val log = TransactionLog(workspaceRoot.resolve(".refactorkit/transactions"))
        val tx = try {
            log.load(transactionId)
        } catch (error: TransactionLogException) {
            System.err.println("Transaction log error [${error.code}]: ${error.message}")
            return 1
        } ?: run { System.err.println("Transaction not found: $txId"); return 1 }
        return when (val result = PatchEngine(workspaceRoot).rollback(tx)) {
            is ApplyResult.Applied -> {
                log.delete(transactionId)
                println("Rolled back transaction $txId.")
                0
            }
            is ApplyResult.Refused -> {
                System.err.println("Rollback refused:")
                result.diagnostics.forEach { System.err.println("  ${it.severity}: ${it.message}") }
                1
            }
        }
    }

    // ── shared helpers ────────────────────────────────────────────────────────

    private fun applyPlanAndLog(plan: org.refactorkit.core.PatchPlan, snap: ProjectSnapshot, root: String): Int {
        val workspaceRoot = snap.workspace.root
        val engine = PatchEngine(workspaceRoot)
        return when (val result = engine.apply(plan, snap)) {
            is ApplyResult.Applied -> {
                val log = TransactionLog(workspaceRoot.resolve(".refactorkit/transactions"))
                log.save(result.transaction)
                println("Applied. Transaction: ${result.transaction.id.value}")
                println("To rollback: refactorkit patch rollback ${result.transaction.id.value}")
                0
            }
            is ApplyResult.Refused -> {
                System.err.println("Apply refused:")
                result.diagnostics.forEach { System.err.println("  ${it.severity}: ${it.message}") }
                1
            }
        }
    }

    private fun scanFrom(root: String): ProjectSnapshot? {
        val path = Paths.get(root)
        if (!path.exists()) { System.err.println("Path does not exist: $path"); return null }
        return scanner.scan(path)
    }

    private fun positional(args: List<String>, index: Int): String? =
        args.getOrNull(index)?.takeIf { !it.startsWith("-") }

    private fun parseOptions(args: List<String>): ParsedArgs {
        val options = linkedMapOf<String, String>()
        val flags = linkedSetOf<String>()
        val positionals = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            if (arg.startsWith("--")) {
                val key = arg.removePrefix("--")
                val next = args.getOrNull(i + 1)
                if (next == null || next.startsWith("--")) { flags += key; i++ }
                else { options[key] = next; i += 2 }
            } else {
                positionals += arg; i++
            }
        }
        return ParsedArgs(options, flags, positionals)
    }

    private data class ParsedArgs(
        val options: Map<String, String>,
        val flags: Set<String>,
        val positionals: List<String>,
    )

    private fun cmdJava(args: List<String>): Int {
        if (args.isEmpty()) {
            System.err.println("java requires a subcommand: scan, symbols, diagnostics, references, definition, or import-class")
            return 2
        }
        return when (args.first()) {
            "scan"        -> cmdScan(args.drop(1))
            "index"       -> cmdScan(args.drop(1))
            "symbols"     -> cmdSymbols(args.drop(1))
            "diagnostics" -> cmdDiagnostics(args.drop(1))
            "references"  -> cmdReferences(args.drop(1))
            "definition"  -> cmdDefinition(args.drop(1))
            "import-class" -> cmdJavaImportClass(args.drop(1))
            else -> { System.err.println("Unknown java subcommand: ${args.first()}"); 2 }
        }
    }

    private fun cmdJavaImportClass(args: List<String>): Int {
        val parsed = parseOptions(args)
        val targetPkg = parsed.options["target-package"] ?: run { System.err.println("--target-package required"); return 2 }
        val sourceUrl = parsed.options["source-url"]
        val licensePolicy = when (parsed.options["license-policy"]) {
            "block-unknown" -> LicensePolicy.BLOCK_UNKNOWN
            "allow" -> LicensePolicy.ALLOW
            else -> LicensePolicy.WARN
        }
        val code: String = when {
            "stdin" in parsed.flags -> generateSequence(::readLine).joinToString("\n")
            parsed.options.containsKey("file") -> Paths.get(parsed.options["file"]!!).readText()
            else -> { System.err.println("Provide --stdin or --file <path>"); return 2 }
        }
        val root = parsed.positionals.firstOrNull() ?: "."
        val snap = scanFrom(root)
        val plan = ExternalJavaClassImporter().preview(ImportRequest(
            code = code, targetPackage = targetPkg, sourceUrl = sourceUrl,
            sourceKind = if ("stdin" in parsed.flags) SourceKind.CLIPBOARD else SourceKind.FILE,
            licensePolicy = licensePolicy, snapshot = snap,
        ))
        println(PatchPreviewRenderer(Paths.get(root).toAbsolutePath().normalize()).render(plan))
        if (plan.status.name == "REFUSED") return 1
        if ("apply" in parsed.flags && snap != null) return applyPlanAndLog(plan, snap, root)
        println("Use --apply to apply this change.")
        return 0
    }

    private fun cmdRecipe(args: List<String>): Int {
        if (args.isEmpty()) { System.err.println("recipe requires a subcommand: run"); return 2 }
        return when (args.first()) {
            "run" -> cmdRecipeRun(args.drop(1))
            else -> { System.err.println("Unknown recipe subcommand: ${args.first()}"); 2 }
        }
    }

    private fun cmdRecipeRun(args: List<String>): Int {
        val parsed = parseOptions(args)
        val recipePath = parsed.positionals.firstOrNull() ?: run { System.err.println("recipe run requires <recipe.yml>"); return 2 }
        val root = parsed.options["root"] ?: "."
        val dryRun = "apply" !in parsed.flags
        val params = parsed.options.filterKeys { it.startsWith("param.") }.mapKeys { it.key.removePrefix("param.") }
        val recipe = try { RecipeLoader.load(Paths.get(recipePath)) } catch (e: Exception) {
            System.err.println("Failed to load recipe: ${e.message}"); return 1
        }
        println("Recipe: ${recipe.name} (${recipe.id})\nMode  : ${if (dryRun) "preview" else "apply"}\n")
        val result = RecipeEngine().run(recipe, params, Paths.get(root), dryRun)
        result.stepPlans.forEach { step ->
            println("Step [${step.stepType}]: ${step.message.ifBlank { step.plan?.summary ?: "ok" }}")
            step.plan?.warnings?.forEach { println("  Warning: $it") }
        }
        return when (result) {
            is RecipeResult.Preview -> { println("\n${result.summary}\nUse --apply to apply."); 0 }
            is RecipeResult.Applied -> { println("\n${result.summary}"); 0 }
            is RecipeResult.Failed  -> { System.err.println("Recipe failed: ${result.reason}"); 1 }
        }
    }

    // ── outline ───────────────────────────────────────────────────────────────

    private fun cmdOutline(args: List<String>): Int {
        val parsed = parseOptions(args)
        val filePath = parsed.positionals.firstOrNull()
            ?: run { System.err.println("outline requires <file>"); return 2 }
        val path = Paths.get(filePath)
        if (!path.toFile().exists()) { System.err.println("File not found: $filePath"); return 1 }
        val language = parsed.options["language"] ?: path.fileName.toString().substringAfterLast('.', "")
        val content = path.readText()
        val items = TreeSitterAdapter().outline(content, language)
        if (items.isEmpty()) { println("No outline items found."); return 0 }
        items.forEach { item ->
            println("${item.kind.name.padEnd(12)} ${item.name.padEnd(40)} line ${item.line + 1}")
        }
        return 0
    }

    // ── search ────────────────────────────────────────────────────────────────

    private fun cmdSearch(args: List<String>): Int {
        val parsed = parseOptions(args)
        val pattern = parsed.options["pattern"] ?: run { System.err.println("search requires --pattern"); return 2 }
        val filePath = parsed.positionals.firstOrNull()
            ?: run { System.err.println("search requires <file>"); return 2 }
        val path = Paths.get(filePath)
        if (!path.toFile().exists()) { System.err.println("File not found: $filePath"); return 1 }
        val language = parsed.options["language"] ?: path.fileName.toString().substringAfterLast('.', "")
        val wholeWord = "whole-word" in parsed.flags
        val caseSensitive = "case-insensitive" !in parsed.flags
        val content = path.readText()
        val matches = GenericStructuralSearch.search(content, pattern, language, wholeWord, caseSensitive)
        if (matches.isEmpty()) { println("No matches found."); return 0 }
        matches.forEach { m ->
            println("${filePath}:${m.line + 1}:${m.character + 1}  ${m.text}")
        }
        return 0
    }

    // ── local-rename ──────────────────────────────────────────────────────────

    private fun cmdLocalRename(args: List<String>): Int {
        val parsed = parseOptions(args)
        val filePath = parsed.positionals.firstOrNull()
            ?: run { System.err.println("local-rename requires <file>"); return 2 }
        val from = parsed.options["from"] ?: run { System.err.println("local-rename requires --from"); return 2 }
        val to = parsed.options["to"] ?: run { System.err.println("local-rename requires --to"); return 2 }
        val root = parsed.options["root"] ?: "."
        val path = Paths.get(filePath)
        if (!path.toFile().exists()) { System.err.println("File not found: $filePath"); return 1 }

        // Build a minimal snapshot containing just the target file
        val rootPath = Paths.get(root).toAbsolutePath().normalize()
        val relPath = try { rootPath.relativize(path.toAbsolutePath().normalize()) } catch (_: Exception) { path }
        val language = path.fileName.toString().substringAfterLast('.', "")
        val snap = GenericProjectScanner().scan(rootPath).let { fullSnap ->
            if (fullSnap.files.any { it.path == relPath }) fullSnap
            else {
                val sf = org.refactorkit.core.SourceFile(relPath, path.readText(), language)
                org.refactorkit.core.ProjectSnapshot(fullSnap.workspace, fullSnap.modules, fullSnap.files + sf)
            }
        }
        val plan = GenericLocalRenamePlanner().preview(snap, relPath, from, to)
        println(PatchPreviewRenderer(rootPath).render(plan))
        if (plan.status == PatchStatus.REFUSED) return 1
        if ("apply" in parsed.flags) return applyPlanAndLog(plan, snap, root)
        println("Use --apply to apply this change.")
        return 0
    }

    // ── test-golden ───────────────────────────────────────────────────────────

    private fun cmdTestGolden(args: List<String>): Int {
        val parsed = parseOptions(args)
        val goldenDir = Paths.get(parsed.options["golden-dir"] ?: "testdata/golden")
        val filterName = parsed.positionals.firstOrNull()

        val cases = if (filterName != null) {
            val case = runCatching { GoldenTestLoader.loadNamed(filterName, goldenDir) }.getOrElse {
                System.err.println("Golden case not found: $filterName in $goldenDir"); return 1
            }
            listOf(case)
        } else {
            GoldenTestLoader.discover(goldenDir)
        }

        if (cases.isEmpty()) { println("No golden test cases found in $goldenDir"); return 0 }

        val runner = GoldenTestRunner()
        var passed = 0; var failed = 0
        cases.forEach { tc ->
            print("  ${tc.name.padEnd(50)} ")
            val result = runner.run(tc)
            if (result.passed) {
                println("PASS")
                passed++
            } else {
                println("FAIL")
                result.errors.forEach { System.err.println("    ✗ $it") }
                failed++
            }
        }
        println("\n${cases.size} case(s): $passed passed, $failed failed")
        return if (failed > 0) 1 else 0
    }

    private fun printVersion() = println("${RefactorKitVersion.NAME} ${RefactorKitVersion.VERSION} (API ${RefactorKitVersion.API_VERSION})")

    private fun printHelp() = println("""
        RefactorKit ${RefactorKitVersion.VERSION}  deterministic refactoring engine

        Usage:
          refactorkit --help
          refactorkit --version
          refactorkit scan              <path>
          refactorkit symbols           <path>
          refactorkit diagnostics       <path>
          refactorkit references        --symbol <fqcn>  [<path>]
          refactorkit definition        --symbol <fqcn>  [<path>]
          refactorkit rename            --symbol <fqcn> --to <name>               [--apply] [<path>]
          refactorkit rename-member     --symbol <FQN#member> --to <newName>      [--apply] [<path>]
          refactorkit extract-method    --file <path> --start-line <n> --end-line <n> --method-name <name> [--apply] [--root <path>]
          refactorkit change-signature  --symbol <FQN#method> --old-name <param> --new-name <param> [--apply] [<path>]
          refactorkit change-signature  --operation add-parameter --symbol <FQN#method> --type <javaType> --name <param> --default <expr> [--apply] [<path>]
          refactorkit change-signature  --operation reorder-parameters --symbol <FQN#method> --order <param1,param2,...> [--apply] [<path>]
          refactorkit change-signature  --operation remove-parameter --symbol <FQN#method> --name <param> [--apply] [<path>]
          refactorkit move-class        --symbol <fqcn> --to-package <pkg>        [--apply] [<path>]
          refactorkit organize-imports  <file...>                               [--apply] [--root <path>]
          refactorkit safe-delete       --symbol <fqcn>                        [--apply] [--force] [<path>]
          refactorkit patch rollback    <transaction-id>                        [--root <path>]
          refactorkit java symbols      <path>                                  (alias for symbols)
          refactorkit java diagnostics  <path>                                  (alias for diagnostics)
          refactorkit java import-class --target-package <pkg> (--stdin|--file <path>) [--apply] [<root>]
          refactorkit recipe run        <recipe.yml> [--param.<name> <value>]   [--apply] [--root <path>]
          refactorkit outline           <file>                                  [--language <lang>]
          refactorkit search            <file> --pattern <pattern>              [--language <lang>] [--whole-word] [--case-insensitive]
          refactorkit local-rename      <file> --from <name> --to <name>       [--apply] [--root <path>]
          refactorkit test-golden       [<case-name>]                          [--golden-dir <path>]
    """.trimIndent())
}
