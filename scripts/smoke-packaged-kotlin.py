#!/usr/bin/env python3
"""Native packaged acceptance against the explicit real Kotlin 2.0.21 compiler."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def command_for(cli: Path, args: list[str]) -> list[str]:
    if os.name == "nt":
        return [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/s", "/c", str(cli), *args]
    return [str(cli), *args]


def artifact(cache: Path, group: str, name: str, version: str) -> Path:
    root = cache / group / name / version
    matches = sorted(root.glob(f"*/{name}-{version}.jar"))
    if not matches:
        raise AssertionError(f"cached artifact is missing: {group}:{name}:{version} below {root}")
    by_digest: dict[str, list[Path]] = {}
    for match in matches:
        by_digest.setdefault(hashlib.sha256(match.read_bytes()).hexdigest(), []).append(match)
    if len(by_digest) != 1:
        raise AssertionError(
            f"cached artifact has conflicting content: {group}:{name}:{version}; "
            f"digests={sorted(by_digest)}"
        )
    return matches[0].resolve()


def tree_hash(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(item for item in root.rglob("*") if item.is_file()):
        digest.update(path.relative_to(root).as_posix().encode())
        digest.update(path.read_bytes())
    return digest.hexdigest()


def run(
    cli: Path,
    workspace: Path,
    jdk: Path,
    compiler: Path,
    classpath: list[Path],
    request: str,
    operation: str = "diagnostics",
    extra: list[str] | None = None,
) -> dict:
    result = subprocess.run(
        command_for(cli, [
            "kotlin", operation, str(workspace),
            "--jdk-home", str(jdk),
            "--compiler-jar", str(compiler),
            "--compiler-classpath", os.pathsep.join(map(str, classpath)),
            "--request-id", request,
            *(extra or []),
        ]),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=60,
    )
    if result.returncode != 0:
        raise AssertionError(f"Kotlin diagnostics exited {result.returncode}\nstdout={result.stdout}\nstderr={result.stderr}")
    return json.loads(result.stdout)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime", default="modules/refactorkit-cli/build/package/refactorkit")
    parser.add_argument("--jdk-home", default=os.environ.get("JAVA_HOME"))
    options = parser.parse_args()
    repository = Path.cwd()
    runtime = (repository / options.runtime).resolve()
    cli = runtime / "bin" / ("refactorkit.bat" if os.name == "nt" else "refactorkit")
    mcp = runtime / "bin" / ("refactorkit-mcp.bat" if os.name == "nt" else "refactorkit-mcp")
    if not options.jdk_home:
        raise AssertionError("JAVA_HOME or --jdk-home is required")
    jdk = Path(options.jdk_home).resolve()
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches" / "modules-2" / "files-2.1"
    compiler = artifact(cache, "org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.0.21")
    classpath = [
        artifact(cache, "org.jetbrains.kotlin", "kotlin-stdlib", "2.0.21"),
        artifact(cache, "org.jetbrains.kotlin", "kotlin-script-runtime", "2.0.21"),
        artifact(cache, "org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        artifact(cache, "org.jetbrains.kotlin", "kotlin-daemon-embeddable", "2.0.21"),
        artifact(cache, "org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
        artifact(cache, "org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.6.4"),
        artifact(cache, "org.jetbrains", "annotations", "13.0"),
    ]
    for required in [cli, jdk / "release", compiler, *classpath]:
        if not required.exists():
            raise AssertionError(f"required Kotlin qualification input is missing: {required}")

    with tempfile.TemporaryDirectory(prefix="refactorkit-kotlin-qualification-") as temporary:
        workspace = Path(temporary) / "workspace"
        shutil.copytree(repository / "samples" / "kotlin-maven-simple", workspace)
        before = tree_hash(workspace)
        source_before = tree_hash(workspace / "src")
        clean = run(cli, workspace, jdk, compiler, classpath, "native-kotlin-clean")
        if clean.get("status") != "ready" or clean.get("diagnostics") != []:
            raise AssertionError(f"clean Kotlin diagnostics failed: {clean}")
        if clean.get("backend") != "kotlin-compiler-diagnostics-k2-v1" or not clean.get("process"):
            raise AssertionError(f"Kotlin compiler/process attestation is missing: {clean}")
        if tree_hash(workspace) != before:
            raise AssertionError("clean Kotlin diagnostics modified workspace sources")

        symbols = run(
            cli, workspace, jdk, compiler, classpath, "native-kotlin-symbols", "symbols",
            ["--file", "src/main/kotlin/org/refactorkit/samples/Greeting.kt"],
        )
        symbol_rows = symbols.get("symbols", [])
        if symbols.get("status") != "ready" or symbols.get("backend") != "kotlin-compiler-jvm-declarations-k2-v1":
            raise AssertionError(f"Kotlin compiler symbols failed: {symbols}")
        expected_kinds = {
            "Greeting": "class",
            "GreetingConsumer": "class",
            "GreetingPort": "interface",
            "GreetingMode": "enum",
            "GreetingMarker": "annotation",
            "GreetingRegistry": "object",
            "GreetingDataRegistry": "object",
            "GreetingOwner": "class",
            "InternalGreeting": "class",
            "Companion": "object",
            "NestedRegistry": "object",
            "anonymous": "property",
            "name": "parameter",
            "normalizer": "property",
            "value": "parameter",
            "topLevelGreeting": "function",
            "render": "function",
            "greet": "function",
            "lookup": "function",
            "internalGreeting": "function",
        }
        actual_kinds = {item.get("name"): item.get("kind") for item in symbol_rows}
        if actual_kinds != expected_kinds:
            raise AssertionError(f"Kotlin JVM type kinds are incomplete: {symbols}")
        for item in symbol_rows:
            expected_prefix = {
                "function": "kotlin-jvm-callable-v1:", "property": "kotlin-jvm-property-v1:",
                "parameter": "kotlin-jvm-parameter-v1:", "type-parameter": "kotlin-jvm-type-parameter-v1:",
            }.get(item.get("kind"), "kotlin-jvm-type-v1:")
            if not item.get("id", "").startswith(expected_prefix):
                raise AssertionError(f"Kotlin JVM declaration identity is invalid: {item}")
        greeting = next(item for item in symbol_rows if item.get("name") == "Greeting")
        if greeting.get("startLine") != 4 or greeting.get("startCharacter") != 6 or greeting.get("endCharacter") != 14:
            raise AssertionError(f"Kotlin compiler PSI range is not exact UTF-16: {greeting}")
        render = next(item for item in symbol_rows if item.get("name") == "render")
        if not render.get("id", "").startswith("kotlin-jvm-callable-v1:"):
            raise AssertionError(f"Kotlin callable identity is missing: {render}")
        definition = run(
            cli, workspace, jdk, compiler, classpath, "native-kotlin-definition", "definition",
            ["--symbol", render["id"]],
        )
        if definition.get("status") != "ready" or definition.get("symbols") != [render]:
            raise AssertionError(f"Kotlin opaque function definition lookup failed: {definition}")
        cli_usage_definition = run(
            cli, workspace, jdk, compiler, classpath, "native-kotlin-cli-usage-definition", "definition",
            ["--file", "src/main/kotlin/org/refactorkit/samples/Greeting.kt", "--line", "5", "--character", "45"],
        )
        if cli_usage_definition.get("status") != "ready":
            raise AssertionError(f"Kotlin CLI usage definition failed: {cli_usage_definition}")
        cli_usage_references = run(
            cli, workspace, jdk, compiler, classpath, "native-kotlin-cli-usage-references", "references",
            ["--file", "src/main/kotlin/org/refactorkit/samples/Greeting.kt", "--line", "5", "--character", "45"],
        )
        if cli_usage_references.get("status") != "ready" or cli_usage_references.get("total") != 2:
            raise AssertionError(f"Kotlin CLI partial references failed: {cli_usage_references}")
        cli_type_definition = run(
            cli, workspace, jdk, compiler, classpath, "native-kotlin-cli-type-definition", "definition",
            ["--file", "src/main/kotlin/org/refactorkit/samples/Greeting.kt", "--line", "22", "--character", "38"],
        )
        if (cli_type_definition.get("status") != "ready" or
                cli_type_definition.get("locations", [{}])[0].get("range", {}).get("start", {}).get("line") != 4):
            raise AssertionError(f"Kotlin CLI type-usage definition failed: {cli_type_definition}")
        cli_type_references = run(
            cli, workspace, jdk, compiler, classpath, "native-kotlin-cli-type-references", "references",
            ["--file", "src/main/kotlin/org/refactorkit/samples/Greeting.kt", "--line", "22", "--character", "38"],
        )
        if cli_type_references.get("status") != "ready" or cli_type_references.get("total") != 2:
            raise AssertionError(f"Kotlin CLI partial type references failed: {cli_type_references}")

        daemon = runtime / "bin" / ("refactorkit-daemon.bat" if os.name == "nt" else "refactorkit-daemon")
        process = subprocess.Popen(
            command_for(daemon, []), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, encoding="utf-8",
        )
        try:
            def exchange(request_id: int, method: str, params: dict | None = None) -> dict:
                request = {"jsonrpc": "2.0", "id": request_id, "method": method}
                if params is not None:
                    request["params"] = params
                process.stdin.write(json.dumps(request, separators=(",", ":")) + "\n")
                process.stdin.flush()
                response = json.loads(process.stdout.readline())
                if response.get("error") is not None:
                    raise AssertionError(f"daemon {method} failed: {response}")
                return response["result"]

            opened = exchange(1, "project.open", {"root": str(workspace)})
            unconfigured_jvm = exchange(2, "diagnostics", {"languageId": "jvm"})
            if (len(unconfigured_jvm) != 1 or unconfigured_jvm[0].get("languageId") != "kotlin" or
                    unconfigured_jvm[0].get("code") != "kotlin.toolchainNotConfigured"):
                raise AssertionError(f"Mixed JVM diagnostics did not preserve the concise Kotlin root: {unconfigured_jvm}")
            started = exchange(3, "kotlin.semantic.start", {
                "jdkHome": str(jdk), "compilerJar": str(compiler),
                "compilerClasspath": [str(item) for item in classpath],
            })
            authority = {"kind": "saved-snapshot"}
            common = {
                "expectedSnapshotHash": started["snapshotHash"], "languageId": "kotlin",
                "path": "src/main/kotlin/org/refactorkit/samples/Greeting.kt",
                "semanticLease": started["semanticLease"], "sourceAuthority": authority,
                "position": {"line": 5, "character": 45},
            }
            usage_definition = exchange(4, "intelligence.query", {
                **common, "requestId": "native-kotlin-usage-definition", "kind": "definition",
            })
            if usage_definition.get("status") != "ready" or usage_definition.get("locations", [{}])[0].get("range", {}).get("start", {}).get("line") != 2:
                raise AssertionError(f"Kotlin usage definition failed: {usage_definition}")
            usage_references = exchange(5, "intelligence.query", {
                **common, "requestId": "native-kotlin-usage-references", "kind": "references",
                "includeDeclaration": True, "limit": 10,
            })
            if (usage_references.get("status") != "ready" or usage_references.get("total") != 2 or
                    usage_references.get("complete") is not False or usage_references.get("completeness") != "partial"):
                raise AssertionError(f"Kotlin partial function references failed: {usage_references}")
            type_common = {**common, "position": {"line": 22, "character": 38}}
            type_definition = exchange(6, "intelligence.query", {
                **type_common, "requestId": "native-kotlin-type-definition", "kind": "definition",
            })
            if (type_definition.get("status") != "ready" or
                    type_definition.get("locations", [{}])[0].get("range", {}).get("start", {}).get("line") != 4):
                raise AssertionError(f"Kotlin type-usage definition failed: {type_definition}")
            type_references = exchange(7, "intelligence.query", {
                **type_common, "requestId": "native-kotlin-type-references", "kind": "references",
                "includeDeclaration": True, "limit": 10,
            })
            if (type_references.get("status") != "ready" or type_references.get("total") != 2 or
                    type_references.get("completeness") != "partial"):
                raise AssertionError(f"Kotlin partial type references failed: {type_references}")
            index = exchange(8, "index.status")
            internal = next(item for item in symbol_rows if item.get("name") == "InternalGreeting")
            rename_preview = exchange(9, "refactor.preview", {
                "operation": "renameSymbol", "languageId": "kotlin", "symbol": internal["id"],
                "semanticLease": started["semanticLease"], "expectedSnapshotHash": started["snapshotHash"],
                "expectedIndexGeneration": index["generation"], "arguments": {"newName": "RenamedGreeting"},
            })
            if rename_preview.get("status") != "PREVIEW" or tree_hash(workspace) != before:
                raise AssertionError(f"Kotlin private-type rename preview is invalid or wrote files: {rename_preview}")
            applied = exchange(10, "refactor.apply", {
                "planId": rename_preview["planId"], "semanticLease": started["semanticLease"],
                "expectedIndexGeneration": index["generation"],
            })
            if applied.get("status") != "applied" or "RenamedGreeting" not in (
                    workspace / "src/main/kotlin/org/refactorkit/samples/Greeting.kt").read_text(encoding="utf-8"):
                raise AssertionError(f"Kotlin private-type rename apply failed: {applied}")
            rolled_back = exchange(11, "patch.rollback", {"transactionId": applied["transactionId"]})
            if rolled_back.get("status") != "rolledBack" or tree_hash(workspace / "src") != source_before:
                raise AssertionError(f"Kotlin private-type rename rollback failed: {rolled_back}")
        finally:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=20)
        if tree_hash(workspace / "src") != source_before:
            raise AssertionError("Kotlin rollback did not restore source bytes")

        java_caller = workspace / "src/main/java/org/refactorkit/samples/Caller.java"
        java_caller.parent.mkdir(parents=True, exist_ok=True)
        java_caller.write_text(
            "package org.refactorkit.samples; class Caller { Greeting value = new Greeting(); String render() { return value.render(\"x\"); } }\n",
            encoding="utf-8",
        )
        mixed_before = tree_hash(workspace / "src")
        mixed_symbols = run(
            cli, workspace, jdk, compiler, classpath, "native-kotlin-mixed-symbols", "symbols",
            ["--file", "src/main/kotlin/org/refactorkit/samples/Greeting.kt"],
        )
        public_greeting = next(item for item in mixed_symbols.get("symbols", []) if item.get("name") == "Greeting")
        process = subprocess.Popen(
            command_for(daemon, []), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, encoding="utf-8",
        )
        try:
            def mixed_exchange(request_id: int, method: str, params: dict | None = None) -> dict:
                request = {"jsonrpc": "2.0", "id": request_id, "method": method}
                if params is not None:
                    request["params"] = params
                process.stdin.write(json.dumps(request, separators=(",", ":")) + "\n")
                process.stdin.flush()
                response = json.loads(process.stdout.readline())
                if response.get("error") is not None:
                    raise AssertionError(f"mixed daemon {method} failed: {response}")
                return response["result"]

            mixed_exchange(20, "project.open", {"root": str(workspace)})
            mixed_started = mixed_exchange(21, "kotlin.semantic.start", {
                "jdkHome": str(jdk), "compilerJar": str(compiler),
                "compilerClasspath": [str(item) for item in classpath],
            })
            mixed_index = mixed_exchange(22, "index.status")
            mixed_preview = mixed_exchange(23, "refactor.preview", {
                "operation": "renameSymbol", "languageId": "kotlin", "symbol": public_greeting["id"],
                "semanticLease": mixed_started["semanticLease"],
                "expectedSnapshotHash": mixed_started["snapshotHash"],
                "expectedIndexGeneration": mixed_index["generation"],
                "arguments": {"newName": "PublicGreeting", "acceptExternalConsumerRisk": True},
            })
            if mixed_preview.get("status") != "PREVIEW" or tree_hash(workspace / "src") != mixed_before:
                raise AssertionError(f"mixed public-type preview is invalid or wrote files: {mixed_preview}")
            mixed_applied = mixed_exchange(24, "refactor.apply", {
                "planId": mixed_preview["planId"], "semanticLease": mixed_started["semanticLease"],
                "expectedIndexGeneration": mixed_index["generation"],
            })
            kotlin_text = (workspace / "src/main/kotlin/org/refactorkit/samples/Greeting.kt").read_text(encoding="utf-8")
            java_text = java_caller.read_text(encoding="utf-8")
            if mixed_applied.get("status") != "applied" or "PublicGreeting" not in kotlin_text or "PublicGreeting" not in java_text:
                raise AssertionError(f"mixed public-type apply failed: {mixed_applied}")
            mixed_rollback = mixed_exchange(25, "patch.rollback", {"transactionId": mixed_applied["transactionId"]})
            if mixed_rollback.get("status") != "rolledBack" or tree_hash(workspace / "src") != mixed_before:
                raise AssertionError(f"mixed public-type rollback failed: {mixed_rollback}")
        finally:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=20)
        function_before = tree_hash(workspace / "src")
        process = subprocess.Popen(
            command_for(daemon, []), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, encoding="utf-8",
        )
        try:
            def function_exchange(request_id: int, method: str, params: dict | None = None) -> dict:
                request = {"jsonrpc": "2.0", "id": request_id, "method": method}
                if params is not None:
                    request["params"] = params
                process.stdin.write(json.dumps(request, separators=(",", ":")) + "\n")
                process.stdin.flush()
                response = json.loads(process.stdout.readline())
                if response.get("error") is not None:
                    raise AssertionError(f"function daemon {method} failed: {response}")
                return response["result"]

            function_exchange(26, "project.open", {"root": str(workspace)})
            function_started = function_exchange(27, "kotlin.semantic.start", {
                "jdkHome": str(jdk), "compilerJar": str(compiler),
                "compilerClasspath": [str(item) for item in classpath],
            })
            function_index = function_exchange(28, "index.status")
            function_preview = function_exchange(29, "refactor.preview", {
                "operation": "renameSymbol", "languageId": "kotlin", "symbol": render["id"],
                "semanticLease": function_started["semanticLease"],
                "expectedSnapshotHash": function_started["snapshotHash"],
                "expectedIndexGeneration": function_index["generation"],
                "arguments": {"newName": "display", "acceptExternalConsumerRisk": True},
            })
            if function_preview.get("status") != "PREVIEW" or tree_hash(workspace / "src") != function_before:
                raise AssertionError(f"public Kotlin-function preview is invalid or wrote files: {function_preview}")
            function_applied = function_exchange(30, "refactor.apply", {
                "planId": function_preview["planId"], "semanticLease": function_started["semanticLease"],
                "expectedIndexGeneration": function_index["generation"],
            })
            if (function_applied.get("status") != "applied" or
                    "fun display" not in (workspace / "src/main/kotlin/org/refactorkit/samples/Greeting.kt").read_text(encoding="utf-8") or
                    ".display(" not in java_caller.read_text(encoding="utf-8")):
                raise AssertionError(f"public Kotlin-function apply failed: {function_applied}")
            function_rollback = function_exchange(31, "patch.rollback", {
                "transactionId": function_applied["transactionId"],
            })
            if function_rollback.get("status") != "rolledBack" or tree_hash(workspace / "src") != function_before:
                raise AssertionError(f"public Kotlin-function rollback failed: {function_rollback}")
        finally:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=20)

        java_caller.unlink()
        greeting_source = workspace / "src/main/kotlin/org/refactorkit/samples/Greeting.kt"
        greeting_original_bytes = greeting_source.read_bytes()
        greeting_original = greeting_original_bytes.decode("utf-8")
        greeting_source.write_text(
            greeting_original + "\nfun publicAccount(): PublicAccount = PublicAccount()\n" +
            "fun accountLabel(account: PublicAccount): String = account.label(\"x\")\n", encoding="utf-8",
        )
        java_type = workspace / "src/main/java/org/refactorkit/samples/PublicAccount.java"
        java_type.write_text(
            "package org.refactorkit.samples; public class PublicAccount { public String label(String value) { return value; } }\n", encoding="utf-8",
        )
        symmetric_before = tree_hash(workspace / "src")
        process = subprocess.Popen(
            command_for(daemon, []), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, encoding="utf-8",
        )
        try:
            def symmetric_exchange(request_id: int, method: str, params: dict | None = None) -> dict:
                request = {"jsonrpc": "2.0", "id": request_id, "method": method}
                if params is not None:
                    request["params"] = params
                process.stdin.write(json.dumps(request, separators=(",", ":")) + "\n")
                process.stdin.flush()
                response = json.loads(process.stdout.readline())
                if response.get("error") is not None:
                    raise AssertionError(f"symmetric daemon {method} failed: {response}")
                return response["result"]

            symmetric_exchange(30, "project.open", {"root": str(workspace)})
            symmetric_started = symmetric_exchange(31, "kotlin.semantic.start", {
                "jdkHome": str(jdk), "compilerJar": str(compiler),
                "compilerClasspath": [str(item) for item in classpath],
            })
            symmetric_index = symmetric_exchange(32, "index.status")
            symmetric_preview = symmetric_exchange(33, "refactor.preview", {
                "operation": "renameSymbol", "languageId": "kotlin",
                "symbol": "org.refactorkit.samples.PublicAccount",
                "semanticLease": symmetric_started["semanticLease"],
                "expectedSnapshotHash": symmetric_started["snapshotHash"],
                "expectedIndexGeneration": symmetric_index["generation"],
                "arguments": {"newName": "CustomerAccount", "acceptExternalConsumerRisk": True},
            })
            if symmetric_preview.get("status") != "PREVIEW" or tree_hash(workspace / "src") != symmetric_before:
                raise AssertionError(f"symmetric public Java-type preview is invalid or wrote files: {symmetric_preview}")
            symmetric_applied = symmetric_exchange(34, "refactor.apply", {
                "planId": symmetric_preview["planId"], "semanticLease": symmetric_started["semanticLease"],
                "expectedIndexGeneration": symmetric_index["generation"],
            })
            renamed_java = java_type.with_name("CustomerAccount.java")
            if (symmetric_applied.get("status") != "applied" or java_type.exists() or not renamed_java.exists() or
                    "CustomerAccount" not in greeting_source.read_text(encoding="utf-8") or
                    "CustomerAccount" not in renamed_java.read_text(encoding="utf-8")):
                raise AssertionError(f"symmetric public Java-type apply failed: {symmetric_applied}")
            symmetric_rollback = symmetric_exchange(35, "patch.rollback", {
                "transactionId": symmetric_applied["transactionId"],
            })
            if symmetric_rollback.get("status") != "rolledBack" or tree_hash(workspace / "src") != symmetric_before:
                raise AssertionError(f"symmetric public Java-type rollback failed: {symmetric_rollback}")
        finally:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=20)
        java_method_before = tree_hash(workspace / "src")
        process = subprocess.Popen(
            command_for(daemon, []), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, encoding="utf-8",
        )
        try:
            def java_method_exchange(request_id: int, method: str, params: dict | None = None) -> dict:
                request = {"jsonrpc": "2.0", "id": request_id, "method": method}
                if params is not None:
                    request["params"] = params
                process.stdin.write(json.dumps(request, separators=(",", ":")) + "\n")
                process.stdin.flush()
                response = json.loads(process.stdout.readline())
                if response.get("error") is not None:
                    raise AssertionError(f"Java-method daemon {method} failed: {response}")
                return response["result"]

            java_method_exchange(40, "project.open", {"root": str(workspace)})
            java_method_started = java_method_exchange(41, "kotlin.semantic.start", {
                "jdkHome": str(jdk), "compilerJar": str(compiler),
                "compilerClasspath": [str(item) for item in classpath],
            })
            java_method_index = java_method_exchange(42, "index.status")
            java_method_preview = java_method_exchange(43, "refactor.preview", {
                "operation": "renameSymbol", "languageId": "kotlin",
                "symbol": "org.refactorkit.samples.PublicAccount#label(java.lang.String)",
                "semanticLease": java_method_started["semanticLease"],
                "expectedSnapshotHash": java_method_started["snapshotHash"],
                "expectedIndexGeneration": java_method_index["generation"],
                "arguments": {"newName": "describe", "acceptExternalConsumerRisk": True},
            })
            if java_method_preview.get("status") != "PREVIEW" or tree_hash(workspace / "src") != java_method_before:
                raise AssertionError(f"public Java-method preview is invalid or wrote files: {java_method_preview}")
            java_method_applied = java_method_exchange(44, "refactor.apply", {
                "planId": java_method_preview["planId"], "semanticLease": java_method_started["semanticLease"],
                "expectedIndexGeneration": java_method_index["generation"],
            })
            if (java_method_applied.get("status") != "applied" or "String describe(" not in java_type.read_text(encoding="utf-8") or
                    "account.describe(" not in greeting_source.read_text(encoding="utf-8")):
                raise AssertionError(f"public Java-method apply failed: {java_method_applied}")
            java_method_rollback = java_method_exchange(45, "patch.rollback", {
                "transactionId": java_method_applied["transactionId"],
            })
            if java_method_rollback.get("status") != "rolledBack" or tree_hash(workspace / "src") != java_method_before:
                raise AssertionError(f"public Java-method rollback failed: {java_method_rollback}")
        finally:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=20)

        greeting_source.write_bytes(greeting_original_bytes)
        move_type = workspace / "src/main/kotlin/org/refactorkit/move/api/PortableGreeting.kt"
        move_kotlin_consumer = workspace / "src/main/kotlin/org/refactorkit/move/consumer/UsePortableGreeting.kt"
        move_java_consumer = workspace / "src/main/java/org/refactorkit/move/consumer/MoveCaller.java"
        move_type.parent.mkdir(parents=True, exist_ok=True)
        move_kotlin_consumer.parent.mkdir(parents=True, exist_ok=True)
        move_java_consumer.parent.mkdir(parents=True, exist_ok=True)
        move_type.write_text(
            "package org.refactorkit.move.api\n" +
            "private class PortableGreetingState\n" +
            "private val defaultPortableGreetingState: PortableGreetingState = PortableGreetingState()\n" +
            "private fun portableGreetingState(): PortableGreetingState = defaultPortableGreetingState\n" +
            "public interface PortableGreetingPort\n" +
            "public class PortableGreeting : PortableGreetingPort { private val state = portableGreetingState() }\n",
            encoding="utf-8",
        )
        move_kotlin_source = (
            "package org.refactorkit.move.consumer\n" +
            "fun portableGreeting(port: org.refactorkit.move.api.PortableGreetingPort): " +
            "org.refactorkit.move.api.PortableGreeting = org.refactorkit.move.api.PortableGreeting()\n"
        )
        move_java_source = (
            "package org.refactorkit.move.consumer;\n" +
            "class MoveCaller { org.refactorkit.move.api.PortableGreetingPort port; " +
            "org.refactorkit.move.api.PortableGreeting value = " +
            "new org.refactorkit.move.api.PortableGreeting(); }\n"
        )
        move_kotlin_consumer.write_text(move_kotlin_source, encoding="utf-8")
        move_java_consumer.write_text(move_java_source, encoding="utf-8")
        move_symbols = run(
            cli, workspace, jdk, compiler, classpath, "native-kotlin-move-symbols", "symbols",
            ["--file", "src/main/kotlin/org/refactorkit/move/api/PortableGreeting.kt"],
        )
        portable_greeting = next(item for item in move_symbols.get("symbols", []) if item.get("name") == "PortableGreeting")
        move_java_consumer.unlink()
        move_kotlin_consumer.unlink()
        unused_move_before = tree_hash(workspace / "src")
        cli_move_preview = run(
            cli, workspace, jdk, compiler, classpath, "native-kotlin-move-preview", "move-declaration",
            ["--symbol", portable_greeting["id"], "--to-package", "org.refactorkit.move.api.v2",
             "--accept-external-consumer-risk"],
        )
        if cli_move_preview.get("status") != "PREVIEW" or tree_hash(workspace / "src") != unused_move_before:
            raise AssertionError(f"unused public Kotlin CLI move preview is invalid or wrote files: {cli_move_preview}")
        move_java_consumer.write_text(move_java_source, encoding="utf-8")
        move_before = tree_hash(workspace / "src")

        portable_symbol_id = "kotlin-jvm-type-v1:" + hashlib.sha256(
            b"kotlin-jvm-type-v1\0org.refactorkit.move.api.PortableGreeting"
        ).hexdigest()
        process = subprocess.Popen(
            command_for(mcp, []), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
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
                    raise AssertionError(f"move MCP {method} failed: {response}")
                return response["result"]

            def mcp_tool(request_id: int, name: str, arguments: dict) -> str:
                result = mcp_exchange(request_id, "tools/call", {"name": name, "arguments": arguments})
                return result["content"][0]["text"]

            mcp_tool(60, "project_scan", {"root": str(workspace)})
            mcp_started = mcp_tool(61, "kotlin_semantic_start", {
                "jdkHome": str(jdk), "compilerJar": str(compiler),
                "compilerClasspath": [str(item) for item in classpath],
            })
            mcp_lease = mcp_started.split("Semantic lease: ", 1)[1].split(". Snapshot:", 1)[0]
            mcp_snapshot = mcp_started.split(". Snapshot: ", 1)[1].split(".", 1)[0]
            mcp_preview = mcp_tool(62, "preview_refactoring", {
                "operation": "moveDeclaration", "languageId": "kotlin", "symbol": portable_symbol_id,
                "semanticLease": mcp_lease, "expectedSnapshotHash": mcp_snapshot,
                "arguments": {"targetPackage": "org.refactorkit.move.api.v2", "acceptExternalConsumerRisk": True},
            })
            mcp_plan_id = mcp_preview.split("Plan ID  : ", 1)[1].splitlines()[0]
            if "Status   : PREVIEW" not in mcp_preview or tree_hash(workspace / "src") != move_before:
                raise AssertionError(f"public Kotlin MCP move preview is invalid or wrote files: {mcp_preview}")
            mcp_applied = mcp_tool(63, "apply_refactoring", {"planId": mcp_plan_id, "semanticLease": mcp_lease})
            mcp_transaction = mcp_applied.split("Transaction ID: ", 1)[1].splitlines()[0]
            mcp_destination = workspace / "src/main/kotlin/org/refactorkit/move/api/v2/PortableGreeting.kt"
            if ("Applied successfully" not in mcp_applied or not mcp_destination.exists() or move_type.exists() or
                    "private class PortableGreetingState" not in mcp_destination.read_text(encoding="utf-8") or
                    "private val defaultPortableGreetingState" not in mcp_destination.read_text(encoding="utf-8") or
                    "private fun portableGreetingState()" not in mcp_destination.read_text(encoding="utf-8") or
                    "public interface PortableGreetingPort" not in mcp_destination.read_text(encoding="utf-8") or
                    "org.refactorkit.move.api.v2.PortableGreetingPort port" not in
                        move_java_consumer.read_text(encoding="utf-8") or
                    "org.refactorkit.move.api.v2.PortableGreeting value" not in
                        move_java_consumer.read_text(encoding="utf-8") or
                    "new org.refactorkit.move.api.v2.PortableGreeting()" not in
                        move_java_consumer.read_text(encoding="utf-8")):
                raise AssertionError(f"public Kotlin MCP move apply failed: {mcp_applied}")
            mcp_rollback = mcp_tool(64, "rollback_refactoring", {"transactionId": mcp_transaction})
            if "Rolled back" not in mcp_rollback or tree_hash(workspace / "src") != move_before:
                raise AssertionError(f"public Kotlin MCP move rollback failed: {mcp_rollback}")
        finally:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=20)

        move_kotlin_consumer.write_text(move_kotlin_source, encoding="utf-8")
        move_before = tree_hash(workspace / "src")
        process = subprocess.Popen(
            command_for(daemon, []), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, encoding="utf-8",
        )
        try:
            def move_exchange(request_id: int, method: str, params: dict | None = None) -> dict:
                request = {"jsonrpc": "2.0", "id": request_id, "method": method}
                if params is not None:
                    request["params"] = params
                process.stdin.write(json.dumps(request, separators=(",", ":")) + "\n")
                process.stdin.flush()
                response = json.loads(process.stdout.readline())
                if response.get("error") is not None:
                    raise AssertionError(f"move daemon {method} failed: {response}")
                return response["result"]

            move_exchange(50, "project.open", {"root": str(workspace)})
            move_started = move_exchange(51, "kotlin.semantic.start", {
                "jdkHome": str(jdk), "compilerJar": str(compiler),
                "compilerClasspath": [str(item) for item in classpath],
            })
            move_index = move_exchange(52, "index.status")
            move_preview = move_exchange(53, "refactor.preview", {
                "operation": "moveDeclaration", "languageId": "kotlin", "symbol": portable_greeting["id"],
                "semanticLease": move_started["semanticLease"],
                "expectedSnapshotHash": move_started["snapshotHash"],
                "expectedIndexGeneration": move_index["generation"],
                "arguments": {"targetPackage": "org.refactorkit.move.api.v2", "acceptExternalConsumerRisk": True},
            })
            if move_preview.get("status") != "PREVIEW" or tree_hash(workspace / "src") != move_before:
                raise AssertionError(f"public Kotlin move preview is invalid or wrote files: {move_preview}")
            move_applied = move_exchange(54, "refactor.apply", {
                "planId": move_preview["planId"], "semanticLease": move_started["semanticLease"],
                "expectedIndexGeneration": move_index["generation"],
            })
            move_destination = workspace / "src/main/kotlin/org/refactorkit/move/api/v2/PortableGreeting.kt"
            if (move_applied.get("status") != "applied" or not move_destination.exists() or move_type.exists() or
                    "private class PortableGreetingState" not in move_destination.read_text(encoding="utf-8") or
                    "private val defaultPortableGreetingState" not in move_destination.read_text(encoding="utf-8") or
                    "private fun portableGreetingState()" not in move_destination.read_text(encoding="utf-8") or
                    "public interface PortableGreetingPort" not in move_destination.read_text(encoding="utf-8") or
                    "port: org.refactorkit.move.api.v2.PortableGreetingPort" not in
                        move_kotlin_consumer.read_text(encoding="utf-8") or
                    ": org.refactorkit.move.api.v2.PortableGreeting =" not in
                        move_kotlin_consumer.read_text(encoding="utf-8") or
                    "org.refactorkit.move.api.v2.PortableGreeting()" not in
                        move_kotlin_consumer.read_text(encoding="utf-8") or
                    "org.refactorkit.move.api.v2.PortableGreetingPort port" not in
                        move_java_consumer.read_text(encoding="utf-8") or
                    "org.refactorkit.move.api.v2.PortableGreeting value" not in
                        move_java_consumer.read_text(encoding="utf-8") or
                    "new org.refactorkit.move.api.v2.PortableGreeting()" not in
                        move_java_consumer.read_text(encoding="utf-8")):
                raise AssertionError(f"public Kotlin move apply failed: {move_applied}")
            move_rollback = move_exchange(55, "patch.rollback", {"transactionId": move_applied["transactionId"]})
            if move_rollback.get("status") != "rolledBack" or tree_hash(workspace / "src") != move_before:
                raise AssertionError(f"public Kotlin move rollback failed: {move_rollback}")
        finally:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=20)

        move_type.unlink()
        move_kotlin_consumer.unlink()
        move_java_consumer.unlink()
        move_kotlin_consumer.parent.rmdir()
        move_type.parent.rmdir()
        move_type.parent.parent.rmdir()
        move_java_consumer.parent.rmdir()
        move_java_consumer.parent.parent.rmdir()

        java_type.unlink()
        greeting_source.write_bytes(greeting_original_bytes)
        java_type.parent.rmdir()
        (workspace / "src/main/java/org/refactorkit").rmdir()
        (workspace / "src/main/java/org").rmdir()
        (workspace / "src/main/java").rmdir()
        if tree_hash(workspace / "src") != source_before:
            raise AssertionError("bidirectional Java/Kotlin rollback did not restore source bytes")

        source = workspace / "src" / "main" / "kotlin" / "org" / "refactorkit" / "samples" / "Greeting.kt"
        source.write_text("package org.refactorkit.samples\n\nclass Greeting(val missing: MissingType)\n", encoding="utf-8")
        broken_before = tree_hash(workspace)
        broken = run(cli, workspace, jdk, compiler, classpath, "native-kotlin-broken")
        diagnostics = broken.get("diagnostics", [])
        if broken.get("status") != "ready" or not any("unresolved" in item.get("message", "").lower() for item in diagnostics):
            raise AssertionError(f"real Kotlin compiler error was not preserved: {broken}")
        if not all(item.get("locationPrecision") in {"line-only", "none"} for item in diagnostics):
            raise AssertionError(f"Kotlin diagnostics fabricated exact ranges: {diagnostics}")
        if tree_hash(workspace) != broken_before:
            raise AssertionError("broken Kotlin diagnostics modified workspace sources")

    print("Packaged Kotlin acceptance passed: K2 reads, private rename, bidirectional public type/member rename, and public Kotlin package move apply/rollback.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as failure:
        message = str(failure).replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::error title=Packaged Kotlin qualification failed::{message}", flush=True)
        raise
