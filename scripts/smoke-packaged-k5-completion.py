#!/usr/bin/env python3
"""Packaged acceptance for the bounded non-move Kotlin K5 completion families."""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


MARKER = (
    "Packaged K5 completion acceptance passed: callable imports, parameter rename, "
    "bounded extract/inline, advanced-shape inventory, and refusal matrices."
)


def load_shared(repository: Path):
    path = repository / "scripts" / "smoke-packaged-kotlin.py"
    spec = importlib.util.spec_from_file_location("refactorkit_packaged_kotlin_smoke", path)
    if spec is None or spec.loader is None:
        raise AssertionError(f"cannot load packaged Kotlin smoke helpers from {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime", default="modules/refactorkit-cli/build/package/refactorkit")
    parser.add_argument("--jdk-home", default=os.environ.get("JAVA_HOME"))
    options = parser.parse_args()
    repository = Path.cwd().resolve()
    shared = load_shared(repository)
    runtime = (repository / options.runtime).resolve()
    suffix = ".bat" if os.name == "nt" else ""
    cli = runtime / "bin" / f"refactorkit{suffix}"
    daemon = runtime / "bin" / f"refactorkit-daemon{suffix}"
    mcp = runtime / "bin" / f"refactorkit-mcp{suffix}"
    if not options.jdk_home:
        raise AssertionError("JAVA_HOME or --jdk-home is required")
    jdk = Path(options.jdk_home).resolve()
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
    compiler = shared.artifact(cache, "org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.0.21")
    classpath = [
        shared.artifact(cache, "org.jetbrains.kotlin", "kotlin-stdlib", "2.0.21"),
        shared.artifact(cache, "org.jetbrains.kotlin", "kotlin-script-runtime", "2.0.21"),
        shared.artifact(cache, "org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        shared.artifact(cache, "org.jetbrains.kotlin", "kotlin-daemon-embeddable", "2.0.21"),
        shared.artifact(cache, "org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
        shared.artifact(cache, "org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.6.4"),
        shared.artifact(cache, "org.jetbrains", "annotations", "13.0"),
    ]
    for required in [cli, daemon, mcp, jdk / "release", compiler, *classpath]:
        if not required.exists():
            raise AssertionError(f"required K5 completion input is missing: {required}")

    def symbols(workspace: Path, relative: str, request: str) -> list[dict]:
        response = shared.run(
            cli, workspace, jdk, compiler, classpath, request, "symbols", ["--file", relative],
        )
        if response.get("status") != "ready":
            raise AssertionError(f"Kotlin symbols are unavailable: {response}")
        return response.get("symbols", [])

    def expect_cli_refusal(
        workspace: Path,
        operation: str,
        arguments: list[str],
        request: str,
        refusal_code: str,
    ) -> None:
        before = {
            path.relative_to(workspace).as_posix(): path.read_bytes()
            for path in workspace.rglob("*") if path.is_file() and path.suffix in {".kt", ".java"}
        }
        transaction_root = workspace / ".refactorkit/transactions"
        transactions_before = sorted(
            path.relative_to(workspace).as_posix()
            for path in transaction_root.rglob("*") if path.is_file()
        ) if transaction_root.exists() else []
        result = subprocess.run(
            shared.command_for(cli, [
                "kotlin", operation, str(workspace),
                "--jdk-home", str(jdk), "--compiler-jar", str(compiler),
                "--compiler-classpath", os.pathsep.join(map(str, classpath)),
                "--request-id", request, *arguments,
            ]),
            text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=shared.COMMAND_TIMEOUT_SECONDS,
        )
        body = json.loads(result.stdout) if result.stdout.strip() else {}
        expected_message = {
            "kotlin.changeSignatureParameterConflict":
                "New Kotlin parameter name already occurs in an affected source and could capture a binding",
            "kotlin.extractCallBindingChanged":
                "The inserted extraction call does not resolve exactly once to the new private helper",
            "kotlin.extractSourceOwnershipUnavailable":
                "Kotlin extract requires one authoritative non-generated source root",
            "kotlin.inlineSourceOwnershipUnavailable":
                "Kotlin inline requires one authoritative non-generated source root",
            "kotlin.usageExternalFieldUnsupported":
                "Kotlin external Java field usage lacks exact modeled JVM field identity",
            "kotlin.usageExternalEnumEntryUnsupported":
                "Kotlin enum-entry usage lacks exact modeled JVM field identity",
            "kotlin.usagePropertyAliasUnsupported":
                "Kotlin property alias usage lacks exact modeled property identity",
            "kotlin.usageTypeAliasUnsupported":
                "Kotlin typealias usage lacks exact modeled alias and expanded-type identity",
            "kotlin.compilerPluginsUnsupported":
                "Kotlin compiler-plugin execution remains outside the bounded semantic model",
        }.get(refusal_code)
        structured = body.get("status") == "REFUSED" and body.get("refusalCode") == refusal_code
        surfaced = not body and expected_message is not None and expected_message in result.stderr
        if result.returncode != 1 or not (structured or surfaced):
            raise AssertionError(
                f"packaged refusal differs for {operation}: rc={result.returncode}, "
                f"body={body}, stderr={result.stderr}"
            )
        after = {
            path.relative_to(workspace).as_posix(): path.read_bytes()
            for path in workspace.rglob("*") if path.is_file() and path.suffix in {".kt", ".java"}
        }
        transactions_after = sorted(
            path.relative_to(workspace).as_posix()
            for path in transaction_root.rglob("*") if path.is_file()
        ) if transaction_root.exists() else []
        if after != before or transactions_after != transactions_before:
            raise AssertionError(f"packaged refusal mutated code or transactions for {operation}")

    with tempfile.TemporaryDirectory(prefix="refactorkit-k5-completion-") as temporary:
        workspace = Path(temporary) / "workspace"
        shutil.copytree(repository / "samples/kotlin-maven-simple", workspace)
        api = workspace / "src/main/kotlin/org/refactorkit/k5/api/Helpers.kt"
        imports = workspace / "src/main/kotlin/org/refactorkit/k5/consumer/Imports.kt"
        signature = workspace / "src/main/kotlin/org/refactorkit/k5/signature/ChangeSignature.kt"
        java_caller = workspace / "src/main/java/org/refactorkit/k5/signature/Caller.java"
        expressions = workspace / "src/main/kotlin/org/refactorkit/k5/expression/Expressions.kt"
        shapes = workspace / "src/main/kotlin/org/refactorkit/k5/shapes/Shapes.kt"
        for path in [api, imports, signature, java_caller, expressions, shapes]:
            path.parent.mkdir(parents=True, exist_ok=True)
        (workspace / ".editorconfig").write_text(
            "root = true\n\n[*.kt]\n"
            "ij_kotlin_imports_layout=org.refactorkit.k5.api.**,kotlin.**,*,^\n",
            encoding="utf-8",
        )
        api.write_text(
            "package org.refactorkit.k5.api\nfun sourceHelper(): Int = 1\n",
            encoding="utf-8",
        )
        imports.write_text(
            "package org.refactorkit.k5.consumer\n"
            "import java.util.UUID\n"
            "import kotlin.math.abs\n"
            "import org.refactorkit.k5.api.sourceHelper as aliasedHelper\n"
            "import kotlin.collections.listOf\n"
            "fun importedValue(): Int = aliasedHelper() + listOf(1).size\n",
            encoding="utf-8",
        )
        signature.write_text(
            "package org.refactorkit.k5.signature\n"
            "fun render(value: String = \"default\"): String = value\n"
            "fun kotlinCaller(): String = render(value = \"kotlin\")\n",
            encoding="utf-8",
        )
        java_caller.write_text(
            "package org.refactorkit.k5.signature;\n"
            "final class Caller { String call() { return ChangeSignatureKt.render(\"java\"); } }\n",
            encoding="utf-8",
        )
        expressions.write_text(
            "package org.refactorkit.k5.expression\n"
            "fun answer(): Int = 40 + 2\n"
            "fun answerUse(): Int = answer()\n"
            "private fun tiny(): Int = 20 + 22\n"
            "fun tinyUse(): Int = tiny()\n",
            encoding="utf-8",
        )
        shapes.write_text(
            "package org.refactorkit.k5.shapes\n"
            "import kotlin.jvm.JvmInline\n"
            "import kotlin.jvm.JvmName\n"
            "data class DataShape(val value: Int)\n"
            "sealed class SealedShape\n"
            "@JvmInline value class ValueShape(val value: Int)\n"
            "suspend fun suspendedShape(): Int = 1\n"
            "fun String.extensionShape(): Int = 2\n"
            "@JvmName(\"binaryNamedShape\") fun sourceNamedShape(): Int = 3\n",
            encoding="utf-8",
        )

        clean = shared.run(cli, workspace, jdk, compiler, classpath, "k5-completion-clean")
        if clean.get("status") != "ready" or clean.get("diagnostics") != []:
            raise AssertionError(f"K5 completion fixture is not compiler-clean: {clean}")
        shape_rows = symbols(
            workspace, "src/main/kotlin/org/refactorkit/k5/shapes/Shapes.kt", "k5-completion-shapes",
        )
        for required_name in [
            "DataShape", "SealedShape", "ValueShape", "suspendedShape", "extensionShape", "sourceNamedShape",
        ]:
            if not any(row.get("name") == required_name for row in shape_rows):
                raise AssertionError(f"advanced Kotlin shape is absent from packaged catalogue: {required_name}")

        render = next(
            row for row in symbols(
                workspace, "src/main/kotlin/org/refactorkit/k5/signature/ChangeSignature.kt", "k5-render-symbol",
            ) if row.get("name") == "render" and row.get("kind") == "function"
        )
        tiny = next(
            row for row in symbols(
                workspace, "src/main/kotlin/org/refactorkit/k5/expression/Expressions.kt", "k5-tiny-symbol",
            ) if row.get("name") == "tiny" and row.get("kind") == "function"
        )
        source_before = shared.tree_hash(workspace / "src")
        cli_previews = [
            shared.run(
                cli, workspace, jdk, compiler, classpath, "k5-cli-imports", "organize-imports",
                ["--file", "src/main/kotlin/org/refactorkit/k5/consumer/Imports.kt"],
            ),
            shared.run(
                cli, workspace, jdk, compiler, classpath, "k5-cli-signature", "change-signature",
                ["--symbol", render["id"], "--old-name", "value", "--new-name", "text",
                 "--accept-external-consumer-risk"],
            ),
            shared.run(
                cli, workspace, jdk, compiler, classpath, "k5-cli-extract", "extract-method",
                ["--file", "src/main/kotlin/org/refactorkit/k5/expression/Expressions.kt",
                 "--start-line", "2", "--end-line", "2", "--method-name", "fortyTwo"],
            ),
            shared.run(
                cli, workspace, jdk, compiler, classpath, "k5-cli-inline", "inline-method",
                ["--symbol", tiny["id"]],
            ),
        ]
        if any(row.get("status") != "PREVIEW" for row in cli_previews):
            raise AssertionError(f"packaged CLI K5 preview failed: {cli_previews}")
        if shared.tree_hash(workspace / "src") != source_before:
            raise AssertionError("packaged CLI K5 preview mutated sources")

        def daemon_cycle(operation: str, arguments: dict, symbol: str | None, verify) -> None:
            baseline = shared.tree_hash(workspace / "src")
            process = subprocess.Popen(
                shared.command_for(daemon, []), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                stderr=subprocess.PIPE, text=True, encoding="utf-8",
            )
            try:
                def exchange(request_id: int, method: str, params: dict | None = None) -> dict:
                    request = {"jsonrpc": "2.0", "id": request_id, "method": method}
                    if params is not None:
                        request["params"] = params
                    process.stdin.write(json.dumps(request, separators=(",", ":")) + "\n")
                    process.stdin.flush()
                    line = process.stdout.readline()
                    if not line:
                        raise AssertionError(f"daemon ended during {method}: {process.stderr.read()}")
                    return json.loads(line)

                opened = exchange(1, "project.open", {"root": str(workspace)})
                started_response = exchange(2, "kotlin.semantic.start", {
                    "jdkHome": str(jdk), "compilerJar": str(compiler),
                    "compilerClasspath": [str(item) for item in classpath],
                })
                started = started_response.get("result")
                index = exchange(3, "index.status").get("result")
                if opened.get("error") is not None or not isinstance(started, dict) or not isinstance(index, dict):
                    raise AssertionError(f"daemon K5 start failed: {opened}, {started_response}, {index}")
                request = {
                    "operation": operation, "languageId": "kotlin", "arguments": arguments,
                    "semanticLease": started["semanticLease"],
                    "expectedSnapshotHash": started["snapshotHash"],
                    "expectedIndexGeneration": index["generation"],
                }
                if symbol is not None:
                    request["symbol"] = symbol
                preview_response = exchange(4, "refactor.preview", request)
                preview = preview_response.get("result")
                if not isinstance(preview, dict) or preview.get("status") != "PREVIEW":
                    raise AssertionError(f"daemon K5 preview failed: {preview_response}")
                applied_response = exchange(5, "refactor.apply", {
                    "planId": preview["planId"], "semanticLease": started["semanticLease"],
                    "expectedIndexGeneration": index["generation"],
                })
                applied = applied_response.get("result")
                if not isinstance(applied, dict) or applied.get("status") != "applied":
                    raise AssertionError(f"daemon K5 apply failed: {applied_response}")
                verify()
                rollback_response = exchange(6, "patch.rollback", {"transactionId": applied["transactionId"]})
                rolled_back = rollback_response.get("result")
                if not isinstance(rolled_back, dict) or rolled_back.get("status") != "rolledBack":
                    raise AssertionError(f"daemon K5 rollback failed: {rollback_response}")
                if shared.tree_hash(workspace / "src") != baseline:
                    raise AssertionError(f"daemon rollback did not restore {operation} bytes")
            finally:
                process.terminate()
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=20)

        daemon_cycle(
            "organizeImports",
            {"file": "src/main/kotlin/org/refactorkit/k5/consumer/Imports.kt"},
            None,
            lambda: (
                None if imports.read_text(encoding="utf-8") ==
                "package org.refactorkit.k5.consumer\n"
                "import org.refactorkit.k5.api.sourceHelper as aliasedHelper\n"
                "fun importedValue(): Int = aliasedHelper() + listOf(1).size\n"
                else (_ for _ in ()).throw(AssertionError(f"unexpected organized imports: {imports.read_text()}"))
            ),
        )
        daemon_cycle(
            "changeSignature.renameParameter",
            {"oldName": "value", "newName": "text", "acceptExternalConsumerRisk": True},
            render["id"],
            lambda: (
                None if "render(text = \"kotlin\")" in signature.read_text(encoding="utf-8") and
                "ChangeSignatureKt.render(\"java\")" in java_caller.read_text(encoding="utf-8")
                else (_ for _ in ()).throw(AssertionError("daemon parameter rename did not preserve Kotlin/Java callers"))
            ),
        )
        daemon_cycle(
            "extractMethod",
            {"file": "src/main/kotlin/org/refactorkit/k5/expression/Expressions.kt",
             "startLine": "2", "endLine": "2", "methodName": "fortyTwo"},
            None,
            lambda: (
                None if "fun answer(): Int = fortyTwo()" in expressions.read_text(encoding="utf-8") and
                "private fun fortyTwo() = 40 + 2" in expressions.read_text(encoding="utf-8")
                else (_ for _ in ()).throw(AssertionError("daemon bounded extract post-image is wrong"))
            ),
        )

        baseline = shared.tree_hash(workspace / "src")
        process = subprocess.Popen(
            shared.command_for(mcp, []), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, encoding="utf-8",
        )
        try:
            def mcp_exchange(request_id: int, method: str, params: dict | None = None) -> dict:
                request = {"jsonrpc": "2.0", "id": request_id, "method": method}
                if params is not None:
                    request["params"] = params
                process.stdin.write(json.dumps(request, separators=(",", ":")) + "\n")
                process.stdin.flush()
                line = process.stdout.readline()
                if not line:
                    raise AssertionError(f"MCP ended during {method}: {process.stderr.read()}")
                response = json.loads(line)
                if response.get("error") is not None:
                    raise AssertionError(f"MCP {method} failed: {response}")
                return response["result"]

            def tool(request_id: int, name: str, arguments: dict) -> dict:
                return mcp_exchange(request_id, "tools/call", {"name": name, "arguments": arguments})

            def text(result: dict) -> str:
                return result["content"][0]["text"]

            tool(20, "project_scan", {"root": str(workspace)})
            started_text = text(tool(21, "kotlin_semantic_start", {
                "jdkHome": str(jdk), "compilerJar": str(compiler),
                "compilerClasspath": [str(item) for item in classpath],
            }))
            lease = started_text.split("Semantic lease: ", 1)[1].split(". Snapshot:", 1)[0]
            snapshot_hash = started_text.split(". Snapshot: ", 1)[1].split(".", 1)[0]
            preview = tool(22, "preview_refactoring", {
                "operation": "inlineMethod", "languageId": "kotlin", "symbol": tiny["id"],
                "semanticLease": lease, "expectedSnapshotHash": snapshot_hash, "arguments": {},
            })
            preview_text = text(preview)
            if preview.get("isError") is True or "Status   : PREVIEW" not in preview_text:
                raise AssertionError(f"MCP inline preview failed: {preview}")
            plan_id = preview_text.split("Plan ID  : ", 1)[1].splitlines()[0]
            applied = tool(23, "apply_refactoring", {"planId": plan_id, "semanticLease": lease})
            applied_text = text(applied)
            if applied.get("isError") is True or "Applied successfully" not in applied_text:
                raise AssertionError(f"MCP inline apply failed: {applied}")
            if "private fun tiny" in expressions.read_text(encoding="utf-8") or \
                    "fun tinyUse(): Int = (20 + 22)" not in expressions.read_text(encoding="utf-8"):
                raise AssertionError("MCP bounded inline post-image is wrong")
            transaction_id = applied_text.split("Transaction ID: ", 1)[1].splitlines()[0]
            rolled_back = tool(24, "rollback_refactoring", {"transactionId": transaction_id})
            if rolled_back.get("isError") is True or "Rolled back" not in text(rolled_back):
                raise AssertionError(f"MCP inline rollback failed: {rolled_back}")
            if shared.tree_hash(workspace / "src") != baseline:
                raise AssertionError("MCP inline rollback did not restore exact source bytes")
        finally:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=20)

        adversarial = Path(temporary) / "adversarial-workspace"
        shutil.copytree(repository / "samples/kotlin-maven-simple", adversarial)
        adversarial_pom = adversarial / "pom.xml"
        adversarial_pom.write_text(adversarial_pom.read_text(encoding="utf-8").replace(
            "</plugins>",
            "<plugin><groupId>org.codehaus.mojo</groupId><artifactId>build-helper-maven-plugin</artifactId>"
            "<version>3.5.0</version><executions><execution><id>custom-generated</id>"
            "<goals><goal>add-source</goal></goals><configuration><sources>"
            "<source>${project.build.directory}/custom-generated</source>"
            "</sources></configuration></execution></executions></plugin></plugins>",
        ), encoding="utf-8")
        capture = adversarial / "src/main/kotlin/org/refactorkit/k5/adversarial/Capture.kt"
        collision = adversarial / "src/main/kotlin/org/refactorkit/k5/adversarial/Collision.kt"
        generated = adversarial / "target/custom-generated/org/refactorkit/k5/Generated.kt"
        for path in [capture, collision, generated]:
            path.parent.mkdir(parents=True, exist_ok=True)
        capture.write_text(
            "package org.refactorkit.k5.adversarial\n"
            "private val MAX_VALUE: Long = 100L\n"
            "private fun captured(old: Int): Long {\n"
            "    val first = run { val MAX_VALUE = 7; old.toLong() }\n"
            "    return first + MAX_VALUE\n"
            "}\n",
            encoding="utf-8",
        )
        collision.write_text(
            "package org.refactorkit.k5.adversarial\n"
            "import java.util.concurrent.ForkJoinPool.getCommonPoolParallelism\n"
            "fun collisionAnswer(): Int = 40 + 2\n"
            "fun importedAnswer(): Int = getCommonPoolParallelism()\n",
            encoding="utf-8",
        )
        generated.write_text(
            "package org.refactorkit.k5\n"
            "fun generatedAnswer(): Int = 40 + 2\n"
            "private fun generatedTiny(): Int = 20 + 22\n"
            "fun generatedUse(): Int = generatedTiny()\n",
            encoding="utf-8",
        )
        capture_symbol = next(
            row for row in symbols(
                adversarial, "src/main/kotlin/org/refactorkit/k5/adversarial/Capture.kt", "k5-capture-symbol",
            ) if row.get("name") == "captured" and row.get("kind") == "function"
        )
        generated_tiny = next(
            row for row in symbols(
                adversarial, "target/custom-generated/org/refactorkit/k5/Generated.kt",
                "k5-generated-symbol",
            ) if row.get("name") == "generatedTiny" and row.get("kind") == "function"
        )
        expect_cli_refusal(
            adversarial, "change-signature",
            ["--symbol", capture_symbol["id"], "--old-name", "old", "--new-name", "MAX_VALUE"],
            "k5-capture-refusal", "kotlin.changeSignatureParameterConflict",
        )
        expect_cli_refusal(
            adversarial, "extract-method",
            ["--file", "src/main/kotlin/org/refactorkit/k5/adversarial/Collision.kt",
             "--start-line", "3", "--end-line", "3", "--method-name", "getCommonPoolParallelism"],
            "k5-extract-binding-refusal", "kotlin.extractCallBindingChanged",
        )
        expect_cli_refusal(
            adversarial, "extract-method",
            ["--file", "target/custom-generated/org/refactorkit/k5/Generated.kt",
             "--start-line", "2", "--end-line", "2", "--method-name", "generatedFortyTwo"],
            "k5-generated-extract-refusal", "kotlin.extractSourceOwnershipUnavailable",
        )
        expect_cli_refusal(
            adversarial, "inline-method", ["--symbol", generated_tiny["id"]],
            "k5-generated-inline-refusal", "kotlin.inlineSourceOwnershipUnavailable",
        )

        external_field = Path(temporary) / "external-field-workspace"
        shutil.copytree(repository / "samples/kotlin-maven-simple", external_field)
        field_source = external_field / "src/main/kotlin/org/refactorkit/k5/ExternalField.kt"
        field_source.parent.mkdir(parents=True, exist_ok=True)
        field_source.write_text(
            "package org.refactorkit.k5\n"
            "import java.lang.Integer.MAX_VALUE\n"
            "import java.lang.Long.*\n"
            "fun externalField(): String = \"${MAX_VALUE::class.qualifiedName}:$MAX_VALUE\"\n",
            encoding="utf-8",
        )
        expect_cli_refusal(
            external_field, "organize-imports",
            ["--file", "src/main/kotlin/org/refactorkit/k5/ExternalField.kt"],
            "k5-external-field-refusal", "kotlin.usageExternalFieldUnsupported",
        )

        enum_workspace = Path(temporary) / "enum-workspace"
        shutil.copytree(repository / "samples/kotlin-maven-simple", enum_workspace)
        enum_source = enum_workspace / "src/main/kotlin/org/refactorkit/k5/Enum.kt"
        enum_source.parent.mkdir(parents=True, exist_ok=True)
        enum_source.write_text(
            "package org.refactorkit.k5\n"
            "import java.util.concurrent.TimeUnit.SECONDS\n"
            "import java.time.temporal.ChronoUnit.*\n"
            "fun enumValue(): Any = SECONDS\n",
            encoding="utf-8",
        )
        expect_cli_refusal(
            enum_workspace, "organize-imports",
            ["--file", "src/main/kotlin/org/refactorkit/k5/Enum.kt"],
            "k5-enum-refusal", "kotlin.usageExternalEnumEntryUnsupported",
        )

        alias_workspace = Path(temporary) / "alias-property-workspace"
        shutil.copytree(repository / "samples/kotlin-maven-simple", alias_workspace)
        alias_library = alias_workspace / "src/main/kotlin/org/refactorkit/k5/library/Library.kt"
        alias_source = alias_workspace / "src/main/kotlin/org/refactorkit/k5/Alias.kt"
        alias_library.parent.mkdir(parents=True, exist_ok=True)
        alias_source.parent.mkdir(parents=True, exist_ok=True)
        alias_library.write_text(
            "package org.refactorkit.k5.library\nval sourceSeconds: String = \"source\"\n", encoding="utf-8",
        )
        alias_source.write_text(
            "package org.refactorkit.k5\n"
            "import org.refactorkit.k5.library.sourceSeconds as SECONDS\n"
            "import java.time.temporal.ChronoUnit.*\n"
            "fun aliasValue(): Any = SECONDS\n",
            encoding="utf-8",
        )
        expect_cli_refusal(
            alias_workspace, "organize-imports",
            ["--file", "src/main/kotlin/org/refactorkit/k5/Alias.kt"],
            "k5-alias-refusal", "kotlin.usagePropertyAliasUnsupported",
        )

        typealias_workspace = Path(temporary) / "typealias-workspace"
        shutil.copytree(repository / "samples/kotlin-maven-simple", typealias_workspace)
        typealias_library = typealias_workspace / "src/main/kotlin/org/refactorkit/k5/library/Alias.kt"
        typealias_shadow = typealias_workspace / "src/main/kotlin/org/refactorkit/k5/shadow/Alias.kt"
        typealias_source = typealias_workspace / "src/main/kotlin/org/refactorkit/k5/TypeAlias.kt"
        typealias_library.parent.mkdir(parents=True, exist_ok=True)
        typealias_shadow.parent.mkdir(parents=True, exist_ok=True)
        typealias_source.parent.mkdir(parents=True, exist_ok=True)
        typealias_library.write_text(
            "package org.refactorkit.k5.library\n"
            "typealias Chosen = java.util.concurrent.TimeUnit\n", encoding="utf-8",
        )
        typealias_shadow.write_text(
            "package org.refactorkit.k5.shadow\n"
            "typealias Chosen = java.time.temporal.ChronoUnit\n", encoding="utf-8",
        )
        typealias_source.write_text(
            "package org.refactorkit.k5\n"
            "import org.refactorkit.k5.library.Chosen\n"
            "import org.refactorkit.k5.shadow.*\n"
            "fun aliasType(): String = Chosen::class.qualifiedName!!\n",
            encoding="utf-8",
        )
        expect_cli_refusal(
            typealias_workspace, "organize-imports",
            ["--file", "src/main/kotlin/org/refactorkit/k5/TypeAlias.kt"],
            "k5-typealias-refusal", "kotlin.usageTypeAliasUnsupported",
        )

        plugin_workspace = Path(temporary) / "maven-plugin-workspace"
        shutil.copytree(repository / "samples/kotlin-maven-simple", plugin_workspace)
        plugin_pom = plugin_workspace / "pom.xml"
        plugin_pom.write_text(plugin_pom.read_text(encoding="utf-8").replace(
            "<configuration>",
            "<configuration><compilerPlugins><plugin>all-open</plugin></compilerPlugins>"
            "<pluginOptions><option>all-open:annotation=org.refactorkit.k5.Open</option></pluginOptions>",
            1,
        ).replace(
            "</plugin>",
            "<dependencies><dependency><groupId>org.jetbrains.kotlin</groupId>"
            "<artifactId>kotlin-maven-allopen</artifactId><version>2.0.21</version>"
            "</dependency></dependencies></plugin>",
            1,
        ), encoding="utf-8")
        expect_cli_refusal(
            plugin_workspace, "extract-method",
            ["--file", "src/main/kotlin/org/refactorkit/samples/Greeting.kt",
             "--start-line", "3", "--end-line", "3", "--method-name", "pluginHelper"],
            "k5-plugin-refusal", "kotlin.compilerPluginsUnsupported",
        )

        xplugin_workspace = Path(temporary) / "maven-xplugin-workspace"
        shutil.copytree(repository / "samples/kotlin-maven-simple", xplugin_workspace)
        xplugin_pom = xplugin_workspace / "pom.xml"
        xplugin_pom.write_text(xplugin_pom.read_text(encoding="utf-8").replace(
            "<jvmTarget>21</jvmTarget>",
            "<jvmTarget>21</jvmTarget><args>"
            "<arg>-Xplugin=${project.basedir}/custom-compiler-plugin.jar</arg></args>",
        ), encoding="utf-8")
        expect_cli_refusal(
            xplugin_workspace, "extract-method",
            ["--file", "src/main/kotlin/org/refactorkit/samples/Greeting.kt",
             "--start-line", "3", "--end-line", "3", "--method-name", "xpluginHelper"],
            "k5-xplugin-refusal", "kotlin.compilerPluginsUnsupported",
        )

    print(MARKER)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
