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

    print(MARKER)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
