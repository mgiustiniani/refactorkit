#!/usr/bin/env python3
"""Packaged K5 qualification for one top-level-function move and companion refusal."""

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
    "Packaged K5 move-next acceptance passed: exact-import top-level function move "
    "preview/apply/rollback and standalone companion refusal."
)


def load_shared(repository: Path):
    path = repository / "scripts" / "smoke-packaged-kotlin.py"
    spec = importlib.util.spec_from_file_location("refactorkit_packaged_kotlin_smoke", path)
    if spec is None or spec.loader is None:
        raise AssertionError(f"cannot load packaged Kotlin smoke helpers from {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def transaction_files(workspace: Path) -> set[str]:
    root = workspace / ".refactorkit" / "transactions"
    if not root.exists():
        return set()
    return {path.relative_to(workspace).as_posix() for path in root.rglob("*") if path.is_file()}


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
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches" / "modules-2" / "files-2.1"
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
            raise AssertionError(f"required K5 qualification input is missing: {required}")

    with tempfile.TemporaryDirectory(prefix="refactorkit-k5-move-next-") as temporary:
        workspace = Path(temporary) / "workspace"
        shutil.copytree(repository / "samples" / "kotlin-maven-simple", workspace)
        function_source = workspace / "src/main/kotlin/org/refactorkit/k5/api/move.kt"
        function_consumer = workspace / "src/main/kotlin/org/refactorkit/k5/consumer/UseFunction.kt"
        companion_source = workspace / "src/main/kotlin/org/refactorkit/k5/api/CompanionMove.kt"
        function_source.parent.mkdir(parents=True, exist_ok=True)
        function_consumer.parent.mkdir(parents=True, exist_ok=True)
        function_source.write_text(
            "package org.refactorkit.k5.api\n"
            "private val prefix: String = \"[\"\n"
            "private fun decorate(value: String): String = prefix + value + \"]\"\n"
            "fun portableFunction(value: String): String = decorate(value)\n",
            encoding="utf-8",
        )
        function_consumer.write_text(
            "package org.refactorkit.k5.consumer\n"
            "import org.refactorkit.k5.api.portableFunction\n"
            "fun useFunction(): String = portableFunction(\"hello\")\n",
            encoding="utf-8",
        )
        companion_source.write_text(
            "package org.refactorkit.k5.api\n"
            "public class CompanionMove {\n"
            "    public companion object { public fun create(): CompanionMove = CompanionMove() }\n"
            "}\n",
            encoding="utf-8",
        )
        clean = shared.run(cli, workspace, jdk, compiler, classpath, "k5-move-next-clean")
        if clean.get("status") != "ready" or clean.get("diagnostics") != []:
            raise AssertionError(f"K5 fixture is not compiler-clean: {clean}")
        function_symbols = shared.run(
            cli, workspace, jdk, compiler, classpath, "k5-move-function-symbols", "symbols",
            ["--file", "src/main/kotlin/org/refactorkit/k5/api/move.kt"],
        )
        function_symbol = next(
            row for row in function_symbols.get("symbols", []) if row.get("name") == "portableFunction"
        )
        companion_symbols = shared.run(
            cli, workspace, jdk, compiler, classpath, "k5-move-companion-symbols", "symbols",
            ["--file", "src/main/kotlin/org/refactorkit/k5/api/CompanionMove.kt"],
        )
        companion_symbol = next(
            row for row in companion_symbols.get("symbols", []) if row.get("name") == "Companion"
        )
        source_before = shared.tree_hash(workspace / "src")
        cli_preview = shared.run(
            cli, workspace, jdk, compiler, classpath, "k5-move-function-cli-preview", "move-declaration",
            ["--symbol", function_symbol["id"], "--to-package", "org.refactorkit.k5.api.v2",
             "--accept-external-consumer-risk"],
        )
        if cli_preview.get("status") != "PREVIEW" or shared.tree_hash(workspace / "src") != source_before:
            raise AssertionError(f"packaged CLI function preview failed or wrote sources: {cli_preview}")

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
            if opened.get("error") is not None:
                raise AssertionError(f"daemon project.open failed: {opened}")
            started_response = exchange(2, "kotlin.semantic.start", {
                "jdkHome": str(jdk), "compilerJar": str(compiler),
                "compilerClasspath": [str(item) for item in classpath],
            })
            started = started_response.get("result")
            index = exchange(3, "index.status").get("result")
            if not isinstance(started, dict) or not isinstance(index, dict):
                raise AssertionError(f"daemon Kotlin start/index failed: {started_response}, {index}")
            common = {
                "semanticLease": started["semanticLease"],
                "expectedSnapshotHash": started["snapshotHash"],
                "expectedIndexGeneration": index["generation"],
            }
            refusal_transactions = transaction_files(workspace)
            refusal_before = shared.tree_hash(workspace / "src")
            refused = exchange(4, "refactor.preview", {
                **common, "operation": "moveDeclaration", "languageId": "kotlin",
                "symbol": companion_symbol["id"],
                "arguments": {"targetPackage": "org.refactorkit.k5.api.v2", "acceptExternalConsumerRisk": True},
            })
            error = refused.get("error") or {}
            if ((error.get("data") or {}).get("refusalCode") != "kotlin.moveCompanionStandaloneUnsupported"):
                raise AssertionError(f"daemon companion refusal is not stable: {refused}")
            if (shared.tree_hash(workspace / "src") != refusal_before or
                    transaction_files(workspace) != refusal_transactions):
                raise AssertionError("companion refusal wrote a source or transaction file")
            preview_response = exchange(5, "refactor.preview", {
                **common, "operation": "moveDeclaration", "languageId": "kotlin",
                "symbol": function_symbol["id"],
                "arguments": {"targetPackage": "org.refactorkit.k5.api.v2", "acceptExternalConsumerRisk": True},
            })
            preview = preview_response.get("result")
            if not isinstance(preview, dict) or preview.get("status") != "PREVIEW":
                raise AssertionError(f"daemon function preview failed: {preview_response}")
            applied_response = exchange(6, "refactor.apply", {
                "planId": preview["planId"], "semanticLease": started["semanticLease"],
                "expectedIndexGeneration": index["generation"],
            })
            applied = applied_response.get("result")
            destination = workspace / "src/main/kotlin/org/refactorkit/k5/api/v2/move.kt"
            if (not isinstance(applied, dict) or applied.get("status") != "applied" or
                    not destination.exists() or function_source.exists() or
                    "package org.refactorkit.k5.api.v2" not in destination.read_text(encoding="utf-8") or
                    "private val prefix" not in destination.read_text(encoding="utf-8") or
                    "private fun decorate" not in destination.read_text(encoding="utf-8") or
                    "import org.refactorkit.k5.api.v2.portableFunction" not in
                    function_consumer.read_text(encoding="utf-8")):
                raise AssertionError(f"daemon function apply failed: {applied_response}")
            rolled_back = exchange(7, "patch.rollback", {"transactionId": applied["transactionId"]}).get("result")
            if not isinstance(rolled_back, dict) or rolled_back.get("status") != "rolledBack":
                raise AssertionError(f"daemon function rollback failed: {rolled_back}")
            if shared.tree_hash(workspace / "src") != source_before:
                raise AssertionError("daemon rollback did not restore exact K5 source tree")
        finally:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=20)

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
                response = json.loads(process.stdout.readline())
                if response.get("error") is not None:
                    raise AssertionError(f"MCP {method} failed: {response}")
                return response["result"]

            def mcp_tool(request_id: int, name: str, arguments: dict) -> dict:
                return mcp_exchange(request_id, "tools/call", {"name": name, "arguments": arguments})

            def text(result: dict) -> str:
                return result["content"][0]["text"]

            mcp_tool(20, "project_scan", {"root": str(workspace)})
            started_text = text(mcp_tool(21, "kotlin_semantic_start", {
                "jdkHome": str(jdk), "compilerJar": str(compiler),
                "compilerClasspath": [str(item) for item in classpath],
            }))
            lease = started_text.split("Semantic lease: ", 1)[1].split(". Snapshot:", 1)[0]
            snapshot_hash = started_text.split(". Snapshot: ", 1)[1].split(".", 1)[0]
            refusal_before = shared.tree_hash(workspace / "src")
            refusal_transactions = transaction_files(workspace)
            refused = mcp_tool(22, "preview_refactoring", {
                "operation": "moveDeclaration", "languageId": "kotlin", "symbol": companion_symbol["id"],
                "semanticLease": lease, "expectedSnapshotHash": snapshot_hash,
                "arguments": {"targetPackage": "org.refactorkit.k5.api.v2", "acceptExternalConsumerRisk": True},
            })
            if (refused.get("isError") is True or
                    "Refusal  : kotlin.moveCompanionStandaloneUnsupported" not in text(refused)):
                raise AssertionError(f"MCP companion refusal failed: {refused}")
            if (shared.tree_hash(workspace / "src") != refusal_before or
                    transaction_files(workspace) != refusal_transactions):
                raise AssertionError("MCP companion refusal wrote a source or transaction file")
            preview = mcp_tool(23, "preview_refactoring", {
                "operation": "moveDeclaration", "languageId": "kotlin", "symbol": function_symbol["id"],
                "semanticLease": lease, "expectedSnapshotHash": snapshot_hash,
                "arguments": {"targetPackage": "org.refactorkit.k5.api.v2", "acceptExternalConsumerRisk": True},
            })
            preview_text = text(preview)
            if preview.get("isError") is True or "Status   : PREVIEW" not in preview_text:
                raise AssertionError(f"MCP function preview failed: {preview}")
            plan_id = preview_text.split("Plan ID  : ", 1)[1].splitlines()[0]
            applied = mcp_tool(24, "apply_refactoring", {"planId": plan_id, "semanticLease": lease})
            applied_text = text(applied)
            if applied.get("isError") is True or "Applied successfully" not in applied_text:
                raise AssertionError(f"MCP function apply failed: {applied}")
            transaction_id = applied_text.split("Transaction ID: ", 1)[1].splitlines()[0]
            rolled_back = mcp_tool(25, "rollback_refactoring", {"transactionId": transaction_id})
            if rolled_back.get("isError") is True or "Rolled back" not in text(rolled_back):
                raise AssertionError(f"MCP function rollback failed: {rolled_back}")
            if shared.tree_hash(workspace / "src") != source_before:
                raise AssertionError("MCP rollback did not restore exact K5 source tree")
        finally:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=20)

        callable_workspace = Path(temporary) / "callable-reference-workspace"
        shutil.copytree(repository / "samples" / "kotlin-maven-simple", callable_workspace)
        callable_source = callable_workspace / "src/main/kotlin/probe/Dispatch.kt"
        callable_consumer = callable_workspace / "src/main/kotlin/probe/Use.kt"
        callable_source.parent.mkdir(parents=True, exist_ok=True)
        callable_source.write_text(
            "package javax.swing.SwingUtilities\nfun isEventDispatchThread(): Boolean = true\n",
            encoding="utf-8",
        )
        callable_consumer.write_text(
            "package probe\nimport javax.swing.SwingUtilities.isEventDispatchThread\n"
            "val dispatchReference: () -> Boolean = ::isEventDispatchThread\n"
            "fun useDispatch(): Boolean = dispatchReference()\n",
            encoding="utf-8",
        )
        callable_symbols = shared.run(
            cli, callable_workspace, jdk, compiler, classpath, "k5-callable-consumer-symbol",
            "symbols", ["--file", "src/main/kotlin/probe/Dispatch.kt"],
        )
        callable_target = next(
            row for row in callable_symbols.get("symbols", [])
            if row.get("name") == "isEventDispatchThread" and row.get("kind") == "function"
        )
        callable_before = shared.tree_hash(callable_workspace / "src")
        callable_transactions = transaction_files(callable_workspace)
        callable_result = subprocess.run(
            shared.command_for(cli, [
                "kotlin", "move-declaration", str(callable_workspace),
                "--jdk-home", str(jdk), "--compiler-jar", str(compiler),
                "--compiler-classpath", os.pathsep.join(map(str, classpath)),
                "--request-id", "k5-callable-consumer-refusal",
                "--symbol", callable_target["id"], "--to-package", "moved",
                "--accept-external-consumer-risk",
            ]),
            text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=shared.COMMAND_TIMEOUT_SECONDS,
        )
        if (callable_result.returncode != 1 or
                "Top-level function move refuses Kotlin callable-reference consumers" not in callable_result.stderr):
            raise AssertionError(
                f"packaged callable-reference consumer refusal differs: "
                f"rc={callable_result.returncode}, out={callable_result.stdout}, err={callable_result.stderr}"
            )
        if (shared.tree_hash(callable_workspace / "src") != callable_before or
                transaction_files(callable_workspace) != callable_transactions):
            raise AssertionError("packaged callable-reference consumer refusal wrote source or transaction files")

        typealias_workspace = Path(temporary) / "typealias-workspace"
        shutil.copytree(repository / "samples" / "kotlin-maven-simple", typealias_workspace)
        typealias_source = typealias_workspace / "src/main/kotlin/source/api/Function.kt"
        typealias_source.parent.mkdir(parents=True, exist_ok=True)
        typealias_source.write_text(
            "package source.api\nfun selected(): String = \"baseline\"\n", encoding="utf-8",
        )
        typealias_symbols = shared.run(
            cli, typealias_workspace, jdk, compiler, classpath, "k5-typealias-symbol",
            "symbols", ["--file", "src/main/kotlin/source/api/Function.kt"],
        )
        typealias_target = next(
            row for row in typealias_symbols.get("symbols", [])
            if row.get("name") == "selected" and row.get("kind") == "function"
        )
        source_alias = typealias_workspace / "src/main/kotlin/source/api/Alias.kt"
        target_alias = typealias_workspace / "src/main/kotlin/target/api/Alias.kt"
        source_alias.parent.mkdir(parents=True, exist_ok=True)
        target_alias.parent.mkdir(parents=True, exist_ok=True)
        source_alias.write_text(
            "package source.api\ntypealias Chosen = java.util.concurrent.TimeUnit\n", encoding="utf-8",
        )
        target_alias.write_text(
            "package target.api\ntypealias Chosen = java.time.temporal.ChronoUnit\n", encoding="utf-8",
        )
        typealias_source.write_text(
            "package source.api\nfun selected(): String = Chosen::class.qualifiedName!!\n", encoding="utf-8",
        )
        typealias_before = shared.tree_hash(typealias_workspace / "src")
        typealias_transactions = transaction_files(typealias_workspace)
        typealias_result = subprocess.run(
            shared.command_for(cli, [
                "kotlin", "move-declaration", str(typealias_workspace),
                "--jdk-home", str(jdk), "--compiler-jar", str(compiler),
                "--compiler-classpath", os.pathsep.join(map(str, classpath)),
                "--request-id", "k5-typealias-refusal", "--symbol", typealias_target["id"],
                "--to-package", "target.api", "--accept-external-consumer-risk",
            ]),
            text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=shared.COMMAND_TIMEOUT_SECONDS,
        )
        if (typealias_result.returncode != 1 or
                "Kotlin typealias usage lacks exact modeled alias" not in typealias_result.stderr):
            raise AssertionError(
                f"packaged typealias move refusal differs: rc={typealias_result.returncode}, "
                f"out={typealias_result.stdout}, err={typealias_result.stderr}"
            )
        if (shared.tree_hash(typealias_workspace / "src") != typealias_before or
                transaction_files(typealias_workspace) != typealias_transactions):
            raise AssertionError("packaged typealias move refusal wrote source or transaction files")

        xplugin_workspace = Path(temporary) / "maven-xplugin-workspace"
        shutil.copytree(repository / "samples" / "kotlin-maven-simple", xplugin_workspace)
        xplugin_source = xplugin_workspace / "src/main/kotlin/source/api/Function.kt"
        xplugin_source.parent.mkdir(parents=True, exist_ok=True)
        xplugin_source.write_text("package source.api\nfun selected(): Int = 42\n", encoding="utf-8")
        xplugin_symbols = shared.run(
            cli, xplugin_workspace, jdk, compiler, classpath, "k5-xplugin-symbol",
            "symbols", ["--file", "src/main/kotlin/source/api/Function.kt"],
        )
        xplugin_target = next(
            row for row in xplugin_symbols.get("symbols", [])
            if row.get("name") == "selected" and row.get("kind") == "function"
        )
        xplugin_pom = xplugin_workspace / "pom.xml"
        xplugin_pom.write_text(xplugin_pom.read_text(encoding="utf-8").replace(
            "<jvmTarget>21</jvmTarget>",
            "<jvmTarget>21</jvmTarget><args>"
            "<arg>-Xplugin=${project.basedir}/custom-compiler-plugin.jar</arg></args>",
        ), encoding="utf-8")
        xplugin_before = shared.tree_hash(xplugin_workspace / "src")
        xplugin_transactions = transaction_files(xplugin_workspace)
        xplugin_result = subprocess.run(
            shared.command_for(cli, [
                "kotlin", "move-declaration", str(xplugin_workspace),
                "--jdk-home", str(jdk), "--compiler-jar", str(compiler),
                "--compiler-classpath", os.pathsep.join(map(str, classpath)),
                "--request-id", "k5-xplugin-refusal", "--symbol", xplugin_target["id"],
                "--to-package", "target.api", "--accept-external-consumer-risk",
            ]),
            text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=shared.COMMAND_TIMEOUT_SECONDS,
        )
        if (xplugin_result.returncode != 1 or
                "Kotlin compiler-plugin execution remains outside" not in xplugin_result.stderr):
            raise AssertionError(
                f"packaged Maven -Xplugin move refusal differs: rc={xplugin_result.returncode}, "
                f"out={xplugin_result.stdout}, err={xplugin_result.stderr}"
            )
        if (shared.tree_hash(xplugin_workspace / "src") != xplugin_before or
                transaction_files(xplugin_workspace) != xplugin_transactions):
            raise AssertionError("packaged Maven -Xplugin move refusal wrote source or transaction files")

    print(MARKER)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
