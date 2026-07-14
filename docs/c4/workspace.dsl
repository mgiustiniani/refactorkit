workspace "RefactorKit" "Deterministic refactoring and code-intelligence engine for safe, previewable, rollbackable code transformations." {

    model {
        developer = person "Developer / Maintainer" "Runs refactorings, reviews previews, applies or rolls back patch plans, and maintains Java projects."
        editorUser = person "Editor / IDE User" "Uses editor-native code intelligence and refactoring actions exposed through LSP."
        automation = person "Automation / CI User" "Runs scan, diagnostics, recipes, and smoke-test commands in scripts or CI jobs."

        ide = softwareSystem "Editor / IDE" "External editor or IDE that speaks Language Server Protocol." {
            tags "External System"
        }
        localAgent = softwareSystem "Local LLM Agent / MCP Client" "External local AI agent or MCP client that asks RefactorKit for focused context and deterministic tool execution." {
            tags "External System"
        }
        hostJvmApp = softwareSystem "Embedding JVM Application" "External JVM application that can consume RefactorKit as an in-process library." {
            tags "External System"
        }
        targetWorkspace = softwareSystem "Target Source Workspace" "User-owned Java or multi-language source tree, build metadata, recipes, and .refactorkit transaction logs." {
            tags "External System"
        }
        buildToolchain = softwareSystem "Java Build Toolchain" "External Maven, Gradle, JDK, jlink, and test tooling used for project discovery, diagnostics, tests, and runtime packaging." {
            tags "External System"
        }
        externalCodeSources = softwareSystem "External Code Sources" "Clipboard, local files, URLs, snippets, or LLM responses used by the external Java class importer." {
            tags "External System"
        }
        externalLanguageServers = softwareSystem "External Language Servers" "Optional language servers such as TypeScript, Pyright, rust-analyzer, gopls, Roslyn, or Kotlin language server." {
            tags "External System"
        }

        refactorKit = softwareSystem "RefactorKit" "Deterministic refactoring and code-intelligence engine. It produces patch previews, applies safe workspace edits, records transactions, and exposes CLI, JSON-RPC daemon, LSP, MCP, and library integration modes." {
            cli = container "CLI Application" "Command-line executable for users, scripts, CI, Java commands, recipes, patch apply/rollback, outline/search/local-rename, golden tests, and self-contained runtime packaging." "Kotlin/JVM application (modules/refactorkit-cli)"
            daemon = container "JSON-RPC Daemon" "Long-running local stdio session exposing project/symbol/refactor/transaction APIs plus additive IDE diagnostics.v2 with snapshot, lease, source authority, compiler attestation, and exact range evidence." "Kotlin/JVM application (modules/refactorkit-daemon)"
            lsp = container "LSP Server" "Language Server Protocol process over stdio that exposes definition, references, prepareRename, rename, code actions, document symbols, semantic tokens, diagnostics, and executeCommand hooks." "Kotlin/JVM application (modules/refactorkit-lsp)"
            mcp = container "MCP Server" "Local Model Context Protocol server over newline-delimited JSON-RPC that exposes deterministic project, symbol, diagnostics, preview/apply/rollback, import, and context-bundle tools." "Kotlin/JVM application (modules/refactorkit-mcp)"
            embeddedRuntime = container "Embedded Library Runtime" "In-process runtime for external JVM consumers. This is a library integration mode rather than a standalone process." "Kotlin/JVM library artifacts"

            core = container "Core Patch and Model Engine" "Language-independent models, project snapshots, source files, text/file/workspace edits, patch plans, previews, apply/rollback engine, transaction log, diagnostics, JSON-RPC primitives, symbols, references, and LanguageAdapter contract." "Kotlin/JVM library (modules/refactorkit-core)" {
                tags "Runtime Building Block"
            }
            javaAdapter = container "Java Language Adapter" "Java project scanner and lexical Java intelligence for source roots, packages, classes, methods, fields, imports, symbols, references, diagnostics, rename class/member, move class, organize imports, safe delete, extract method, change signature, recipes, and framework-aware risk detection." "Kotlin/JVM library (modules/refactorkit-java)" {
                tags "Runtime Building Block"
            }
            kotlinAdapter = container "Kotlin Language Adapter" "Kotlin module boundary with refused capabilities, explicit JDK/compiler discovery, and non-executing hash-bound Kotlin/JVM source-set projection over Maven/Gradle build evidence; semantic analysis is not yet enabled." "Kotlin/JVM library (modules/refactorkit-kotlin)" {
                tags "Runtime Building Block"
            }
            webImporter = container "External Java Class Importer" "Preview-only external source assimilation for Java classes: strips Markdown fences, detects license/provenance, validates package names, detects/splits public types, rewrites packages, organizes imports, refuses conflicts, and emits PatchPlans." "Kotlin/JVM library (modules/refactorkit-web-importer)" {
                tags "Runtime Building Block"
            }
            treeSitterFoundation = container "Multi-language Structural Foundation" "Regex-backed structural scanner, outline/search/local-rename planner, comment/literal filtering, TreeSitterAdapter facade, native binding extension point, and ExternalLspAdapter stub for future language-server-backed integration." "Kotlin/JVM library (modules/refactorkit-tree-sitter)" {
                tags "Runtime Building Block"
            }
            testkit = container "Test Harness" "Golden test loader/runner and agent simulation support for preview/apply/rollback and refusal scenarios across Java and importer workflows." "Kotlin/JVM library (modules/refactorkit-testkit)" {
                tags "Test Building Block"
            }
            transactionLog = container "Transaction Log Store" "Rollback metadata written under .refactorkit/transactions for applied patch plans and recipes." "Local file storage" {
                tags "Data Store"
            }
        }

        developer -> cli "Runs refactoring, scan, diagnostics, recipe, patch, import, packaging, and test commands" "Shell"
        developer -> hostJvmApp "Can embed RefactorKit as a library" "JVM API"
        editorUser -> ide "Requests editor-native navigation, diagnostics, and refactorings" "Editor UI"
        automation -> cli "Runs deterministic scans, diagnostics, golden tests, recipes, and smoke tests" "Shell / CI job"
        ide -> lsp "Uses editor integration" "LSP over stdio"
        localAgent -> mcp "Uses deterministic tools, resources, and prompts" "MCP / JSON-RPC over stdio"
        hostJvmApp -> embeddedRuntime "Calls in-process APIs" "Kotlin/JVM API"

        cli -> core "Creates snapshots, renders previews, applies/rolls back PatchPlans, and writes transactions" "In-process calls"
        cli -> javaAdapter "Runs Java scan, symbols, references, diagnostics, and Java refactoring commands" "In-process calls"
        cli -> kotlinAdapter "Reports Kotlin capability/refusal metadata" "In-process calls"
        cli -> webImporter "Runs external Java class import preview workflows" "In-process calls"
        cli -> treeSitterFoundation "Runs outline, structural search, and local-rename commands" "In-process calls"
        cli -> testkit "Runs golden tests" "In-process calls"
        cli -> buildToolchain "Builds self-contained CLI runtime and may run build/test smoke checks" "Gradle / jlink / shell"

        daemon -> core "Manages project session state, previews, applies, and rolls back plans" "In-process calls"
        daemon -> javaAdapter "Delegates Java scans, symbol queries, diagnostics, and refactoring previews" "In-process calls"
        daemon -> kotlinAdapter "Reports Kotlin capability/refusal metadata" "In-process calls"

        lsp -> core "Maps LSP workspace edits, commands, and diagnostics to patch-oriented models" "In-process calls"
        lsp -> javaAdapter "Delegates Java navigation, references, rename, code actions, symbols, semantic tokens, and diagnostics" "In-process calls"
        lsp -> kotlinAdapter "Reports Kotlin capability/refusal metadata without claiming LSP ownership" "In-process calls"

        mcp -> core "Generates project resources, focused context, previews, applies, and rollbacks" "In-process calls"
        mcp -> javaAdapter "Delegates project scan, symbols, definitions, references, diagnostics, and Java refactorings" "In-process calls"
        mcp -> kotlinAdapter "Reports Kotlin capability/refusal metadata" "In-process calls"
        mcp -> webImporter "Delegates import_external_java_class preview workflows" "In-process calls"

        embeddedRuntime -> core "Uses language-independent patch, model, diagnostics, and transaction APIs" "In-process calls"
        embeddedRuntime -> javaAdapter "Uses Java intelligence and refactoring planners when Java support is enabled" "In-process calls"
        embeddedRuntime -> kotlinAdapter "Can inspect Kotlin capability/refusal metadata" "In-process calls"
        embeddedRuntime -> webImporter "Can preview external Java class imports" "In-process calls"
        embeddedRuntime -> treeSitterFoundation "Can use structural multi-language outline/search/local rename" "In-process calls"

        javaAdapter -> core "Builds SymbolIndex, diagnostics, and PatchPlan objects through core contracts" "Kotlin/JVM API"
        kotlinAdapter -> core "Returns typed diagnostics and refused PatchPlans through core contracts" "Kotlin/JVM API"
        webImporter -> core "Creates preview PatchPlans and file creation edits" "Kotlin/JVM API"
        webImporter -> javaAdapter "Reuses Java package/import parsing and source-root knowledge" "Kotlin/JVM API"
        treeSitterFoundation -> core "Produces ProjectSnapshot, outline symbols, and local-rename PatchPlans" "Kotlin/JVM API"
        treeSitterFoundation -> externalLanguageServers "Can launch optional language servers for future LSP-backed language support" "LSP over stdio"
        testkit -> core "Applies, rolls back, and compares patch plans in tests" "Kotlin/JVM API"
        testkit -> javaAdapter "Runs Java refactoring golden and simulation scenarios" "Kotlin/JVM API"
        testkit -> webImporter "Runs external import simulation scenarios" "Kotlin/JVM API"

        core -> targetWorkspace "Scans files, computes snapshot hashes, previews edits, applies workspace edits, and restores rollback metadata" "Local filesystem"
        core -> transactionLog "Writes and reads transaction metadata" "Local filesystem"
        javaAdapter -> targetWorkspace "Reads Java source, package declarations, imports, build files, recipes, and framework annotations" "Local filesystem"
        webImporter -> targetWorkspace "Checks target source roots, existing files, and naming conflicts before creating import previews" "Local filesystem"
        treeSitterFoundation -> targetWorkspace "Scans supported language files and plans local renames" "Local filesystem"
        transactionLog -> targetWorkspace "Stores rollback metadata inside the workspace" "Local filesystem"
        javaAdapter -> buildToolchain "Discovers Maven/Gradle project structure and supports diagnostics/build-aware workflows" "Build metadata / shell"
        cli -> externalCodeSources "Accepts file, URL, clipboard, snippet, or LLM-provided Java source for import preview" "User-provided source"
        mcp -> externalCodeSources "Accepts agent-provided Java source for import preview" "MCP tool input"
    }

    views {
        systemContext refactorKit "SystemContext" {
            include developer editorUser automation ide localAgent hostJvmApp targetWorkspace buildToolchain externalCodeSources externalLanguageServers refactorKit
            autolayout lr
        }

        container refactorKit "ContainerAndBuildingBlockView" {
            include *
            autolayout lr
        }

        styles {
            element "Person" {
                shape Person
                background #08427b
                color #ffffff
            }
            element "Software System" {
                background #1168bd
                color #ffffff
            }
            element "External System" {
                background #999999
                color #ffffff
            }
            element "Container" {
                background #438dd5
                color #ffffff
            }
            element "Runtime Building Block" {
                background #85bbf0
                color #000000
            }
            element "Test Building Block" {
                background #f4b183
                color #000000
            }
            element "Data Store" {
                shape Cylinder
                background #f5da81
                color #000000
            }
        }
    }

    configuration {
        scope softwaresystem
    }
}
