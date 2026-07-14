#!/usr/bin/env python3
"""Native packaged acceptance against pinned real TypeScript language tooling."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time


_current_stage = "startup"


def mark_stage(name: str) -> None:
    global _current_stage
    _current_stage = name
    print(f"Packaged TypeScript qualification stage: {name}", flush=True)


def command_for(cli: Path, args: list[str]) -> list[str]:
    if os.name == "nt":
        return [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/s", "/c", str(cli), *args]
    return [str(cli), *args]


def run(cli: Path, common: list[str], operation: list[str], expected: int = 0) -> subprocess.CompletedProcess[str]:
    environment = os.environ.copy()
    environment.pop("JAVA_HOME", None)
    result = subprocess.run(
        command_for(cli, ["typescript", *operation, *common]),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=45,
        env=environment,
    )
    if result.returncode != expected:
        raise AssertionError(
            f"command exited {result.returncode}, expected {expected}\nstdout={result.stdout}\nstderr={result.stderr}"
        )
    return result


def qualify_crash_restart(runtime: Path, workspace: Path, node: Path, server: Path, compiler: Path) -> None:
    daemon = runtime / "bin" / ("refactorkit-daemon.bat" if os.name == "nt" else "refactorkit-daemon")
    command = command_for(daemon, [])
    process = subprocess.Popen(
        command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        text=True, encoding="utf-8",
    )
    responses: queue.Queue[str | None] = queue.Queue()

    def read_stdout() -> None:
        try:
            for line in process.stdout:
                responses.put(line)
        finally:
            responses.put(None)

    threading.Thread(target=read_stdout, daemon=True).start()
    request_id = 100

    def exchange(method: str, params: dict | None = None) -> dict:
        nonlocal request_id
        request_id += 1
        request = {"jsonrpc": "2.0", "id": request_id, "method": method}
        if params is not None:
            request["params"] = params
        process.stdin.write(json.dumps(request, separators=(",", ":")) + "\n")
        process.stdin.flush()
        try:
            raw = responses.get(timeout=45)
        except queue.Empty as error:
            raise AssertionError(f"daemon timeout for {method}") from error
        if raw is None:
            raise AssertionError(f"daemon exited during {method}")
        response = json.loads(raw)
        if response.get("id") != request_id:
            raise AssertionError(f"invalid daemon response for {method}: {response}")
        return response

    try:
        mark_stage("daemon project open")
        opened = exchange("project.open", {"root": str(workspace)})
        if opened.get("error"):
            raise AssertionError(f"daemon project open failed: {opened}")
        parameters = {
            "languageId": "typescript", "nodeExecutable": str(node),
            "languageServerPackageRoot": str(server), "typeScriptPackageRoot": str(compiler),
        }
        mark_stage("semantic process start")
        started_response = exchange("typescript.semantic.start", parameters)
        if started_response.get("error"):
            raise AssertionError(f"semantic start failed: {started_response}")
        started = started_response["result"]
        old_pid = int(started["processId"])
        mark_stage("semantic process kill")
        if os.name == "nt":
            subprocess.run(["taskkill", "/PID", str(old_pid), "/T", "/F"], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        else:
            os.kill(old_pid, signal.SIGKILL)

        mark_stage("semantic process restart")
        deadline = time.monotonic() + 10
        restarted_response = None
        while time.monotonic() < deadline:
            restarted_response = exchange("typescript.semantic.restart", {"languageId": "typescript"})
            if restarted_response.get("error") is None:
                break
            time.sleep(0.1)
        if restarted_response is None or restarted_response.get("error") is not None:
            raise AssertionError(f"bounded semantic restart failed: {restarted_response}")
        restarted = restarted_response["result"]
        if int(restarted["processId"]) == old_pid:
            raise AssertionError("semantic restart reused the killed process ID")
        for field in ("serverVersion", "capabilitiesSha256", "executableSha256", "argumentsSha256"):
            if restarted.get(field) != started.get(field):
                raise AssertionError(f"semantic restart changed provenance field {field}")

        mark_stage("central TypeScript workspace index")
        if restarted.get("index", {}).get("status") != "ready":
            raise AssertionError(f"TypeScript index refresh failed: {restarted}")
        index_status = exchange("index.status")
        providers = index_status.get("result", {}).get("providers", [])
        provider = next((item for item in providers if item.get("languageId") == "typescript"), None)
        if provider is None or provider.get("evidence") != "language-server" or provider.get("truncated"):
            raise AssertionError(f"TypeScript index provider evidence is invalid: {index_status}")
        if len(provider.get("provenanceHash", "")) != 64:
            raise AssertionError(f"TypeScript index provenance is missing: {provider}")
        intelligence = exchange("intelligence.query", {
            "requestId": "native-daemon-index", "expectedSnapshotHash": opened["result"]["snapshotHash"],
            "expectedIndexGeneration": restarted["index"]["generation"], "kind": "workspaceSymbols",
            "query": "UserService", "languageId": "typescript", "limit": 50,
        })
        intelligence_result = intelligence.get("result", {})
        indexed_types = [
            item for item in intelligence_result.get("items", [])
            if item.get("name") == "UserService" and item.get("symbolKind") == "class"
        ]
        if intelligence.get("error") or intelligence_result.get("status") != "ready" or len(indexed_types) != 1:
            raise AssertionError(f"central TypeScript index query failed: {intelligence}")

        mark_stage("immutable overlay document symbols")
        saved_service = (workspace / "src" / "core" / "UserService.ts").read_text()
        overlay_service = saved_service.replace("UserService", "UnsavedService")
        overlay_symbols = exchange("intelligence.query", {
            "requestId": "native-daemon-overlay-symbols",
            "expectedSnapshotHash": opened["result"]["snapshotHash"],
            "expectedIndexGeneration": restarted["index"]["generation"],
            "kind": "documentSymbols", "languageId": "typescript",
            "path": "src/core/UserService.ts", "semanticLease": restarted["semanticLease"],
            "sourceAuthority": {
                "kind": "immutable-editor-overlay",
                "documents": [{"path": "src/core/UserService.ts", "version": 11, "content": overlay_service}],
            },
        })
        overlay_items = overlay_symbols.get("result", {}).get("items", [])
        if overlay_symbols.get("error") or not any(item.get("name") == "UnsavedService" for item in overlay_items):
            raise AssertionError(f"overlay document symbols failed: {overlay_symbols}")
        authority = overlay_symbols["result"].get("sourceAuthority", {})
        if authority.get("kind") != "immutable-editor-overlay" or "content" in authority.get("documents", [{}])[0]:
            raise AssertionError(f"overlay authority leaked content or is incomplete: {authority}")
        if (workspace / "src" / "core" / "UserService.ts").read_text() != saved_service:
            raise AssertionError("overlay document-symbol query modified saved source")

        mark_stage("immutable overlay hover")
        hover = exchange("intelligence.query", {
            "requestId": "native-daemon-overlay-hover",
            "expectedSnapshotHash": opened["result"]["snapshotHash"],
            "expectedIndexGeneration": restarted["index"]["generation"],
            "kind": "hover", "languageId": "typescript", "path": "src/core/UserService.ts",
            "semanticLease": restarted["semanticLease"], "position": {"line": 0, "character": 16},
            "sourceAuthority": {
                "kind": "immutable-editor-overlay",
                "documents": [{"path": "src/core/UserService.ts", "version": 11, "content": overlay_service}],
            },
        })
        hover_result = hover.get("result", {})
        if hover.get("error") or hover_result.get("status") != "ready" or not hover_result.get("contents"):
            raise AssertionError(f"overlay hover failed: {hover}")
        if any("content" in document for document in hover_result.get("sourceAuthority", {}).get("documents", [])):
            raise AssertionError(f"overlay hover leaked source content: {hover_result}")

        mark_stage("restarted semantic diagnostics")
        diagnosed = exchange("diagnostics", {"languageId": "typescript"})
        if diagnosed.get("error") or diagnosed.get("result") != []:
            raise AssertionError(f"restarted semantic diagnostics failed: {diagnosed}")

        mark_stage("IDE diagnostics capability truth")
        capabilities = exchange("server.capabilities")["result"]["languageKernel"]["adapters"]
        typescript = next(item for item in capabilities if item["languageId"] == "typescript")
        diagnostics_capability = next(item for item in typescript["capabilities"] if item["operation"] == "diagnostics")
        if diagnostics_capability["backend"] != "typescript-compiler-exact-v1":
            raise AssertionError(f"wrong diagnostics backend: {diagnostics_capability}")
        limits = diagnostics_capability["runtime"]["limits"]
        if limits["requestTimeoutMillis"] != 30000 or limits["maxOutputBytes"] != 8388608 or limits["maxProcesses"] != 1:
            raise AssertionError(f"wrong diagnostics runtime limits: {limits}")
        if diagnostics_capability.get("diagnosticSnapshotModes") != ["immutable-editor-overlay", "saved-disk"]:
            raise AssertionError(f"wrong diagnostics snapshot modes: {diagnostics_capability}")

        diagnostics_request = {
            "requestId": "native-daemon-saved", "languageId": "typescript",
            "expectedSnapshotHash": opened["result"]["snapshotHash"],
            "semanticLease": restarted["semanticLease"],
            "sourceAuthority": {"kind": "saved-disk"},
        }
        mark_stage("IDE saved-disk diagnostics envelope")
        exact_saved = exchange("diagnostics.v2", diagnostics_request)
        if exact_saved.get("error") or exact_saved["result"]["status"] != "ready" or exact_saved["result"]["diagnostics"] != []:
            raise AssertionError(f"saved-disk diagnostics.v2 failed: {exact_saved}")
        saved_result = exact_saved["result"]
        if saved_result["projectSnapshotHash"] != saved_result["providerSnapshotHash"]:
            raise AssertionError(f"saved authority hashes differ: {saved_result}")
        if saved_result["compilerAttestation"]["process"] is None:
            raise AssertionError(f"compiler process attestation missing: {saved_result}")
        if saved_result["responseBytes"] != len(json.dumps(saved_result, separators=(",", ":"), ensure_ascii=False).encode("utf-8")):
            raise AssertionError("diagnostics response byte attestation is not exact")

        mark_stage("IDE immutable editor-overlay diagnostics")
        source_path = workspace / "src" / "core" / "UserService.ts"
        disk_source = source_path.read_text()
        unsaved = disk_source + "\nconst broken: MissingType = unknownValue;\n"
        overlay_request = dict(diagnostics_request)
        overlay_request.update({
            "requestId": "native-daemon-overlay",
            "sourceAuthority": {
                "kind": "immutable-editor-overlay",
                "documents": [{"path": "src/core/UserService.ts", "version": 9, "content": unsaved}],
            },
        })
        exact_overlay = exchange("diagnostics.v2", overlay_request)
        if exact_overlay.get("error") or exact_overlay["result"]["status"] != "ready":
            raise AssertionError(f"overlay diagnostics.v2 failed: {exact_overlay}")
        overlay_result = exact_overlay["result"]
        if overlay_result["providerSnapshotHash"] == overlay_result["projectSnapshotHash"]:
            raise AssertionError(f"overlay authority was reported as saved disk: {overlay_result}")
        ranged = [item for item in overlay_result["diagnostics"] if item["location"]["kind"] == "range"]
        if not ranged or not all(item["location"].get("encoding") == "utf-16" for item in ranged):
            raise AssertionError(f"exact UTF-16 compiler ranges missing: {overlay_result}")
        if any("\\" in item["location"].get("path", "") for item in ranged):
            raise AssertionError(f"diagnostic paths are not normalized: {ranged}")
        if source_path.read_text() != disk_source:
            raise AssertionError("immutable editor overlay modified the saved source")

        mark_stage("daemon symbol search and definition")
        searched = exchange("symbol.search", {"languageId": "typescript", "query": "UserService"})
        if searched.get("error"):
            raise AssertionError(f"daemon symbol search failed: {searched}")
        daemon_symbols = [
            item for item in searched["result"]
            if item["name"] == "UserService" and item["kind"] == "CLASS"
        ]
        if len(daemon_symbols) != 1:
            raise AssertionError(f"daemon symbol search was ambiguous: {searched}")
        defined = exchange("symbol.definition", {
            "languageId": "typescript", "symbol": daemon_symbols[0]["id"],
        })
        if defined.get("error") or defined["result"].get("file") != "src/core/UserService.ts":
            raise AssertionError(f"daemon symbol definition failed: {defined}")

        mark_stage("stale diagnostics lease refusal")
        stale_request = dict(diagnostics_request)
        stale_request["requestId"] = "native-daemon-stale"
        stale_request["semanticLease"] = "semantic-00000000-0000-4000-8000-000000000000"
        stale = exchange("diagnostics.v2", stale_request)
        if stale.get("error") or stale["result"]["status"] != "refused" or stale["result"]["failure"]["code"] != "diagnostics.semanticLeaseStale":
            raise AssertionError(f"stale diagnostics lease was not refused: {stale}")
        exchange("typescript.semantic.stop", {"languageId": "typescript"})
        stopped_index = exchange("index.status")
        if any(item.get("languageId") == "typescript" for item in stopped_index["result"]["providers"]):
            raise AssertionError(f"stopped TypeScript provider remained indexed: {stopped_index}")
    finally:
        if process.poll() is None:
            process.stdin.close()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)


def tree_hash(root: Path, include_metadata: bool = False) -> str:
    digest = hashlib.sha256()
    for path in sorted(
        p for p in root.rglob("*")
        if p.is_file() and (include_metadata or ".refactorkit" not in p.parts)
    ):
        digest.update(path.relative_to(root).as_posix().encode())
        digest.update(path.read_bytes())
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime", default="modules/refactorkit-cli/build/package/refactorkit")
    parser.add_argument("--node", default=shutil.which("node"))
    parser.add_argument("--toolchain", default="qualification/typescript-toolchain")
    options = parser.parse_args()

    repository = Path.cwd()
    runtime = (repository / options.runtime).resolve()
    cli = runtime / "bin" / ("refactorkit.bat" if os.name == "nt" else "refactorkit")
    if not options.node:
        raise AssertionError("node executable was not supplied and is not on PATH")
    toolchain = (repository / options.toolchain).resolve()
    server = toolchain / "node_modules" / "typescript-language-server"
    compiler = toolchain / "node_modules" / "typescript"
    for required in (cli, Path(options.node), server / "package.json", compiler / "package.json"):
        if not required.exists():
            raise AssertionError(f"required qualification input is missing: {required}")

    with tempfile.TemporaryDirectory(prefix="refactorkit-ts-qualification-") as temporary:
        workspace = Path(temporary) / "workspace"
        shutil.copytree(repository / "samples" / "typescript-semantic", workspace)
        before = tree_hash(workspace)
        before_read_only = tree_hash(workspace, include_metadata=True)
        node = Path(options.node).resolve()
        qualify_crash_restart(runtime, workspace, node, server, compiler)
        common = [
            str(workspace), "--node", str(node),
            "--language-server-package", str(server), "--typescript-package", str(compiler),
        ]

        mark_stage("CLI exact diagnostics v2")
        cli_diagnostics = json.loads(run(cli, common, ["diagnostics-v2", "--request-id", "native-cli-saved"]).stdout)
        if cli_diagnostics["status"] != "ready" or cli_diagnostics["sourceAuthority"]["kind"] != "saved-disk":
            raise AssertionError(f"CLI diagnostics-v2 failed: {cli_diagnostics}")

        mark_stage("stable semantic search")
        first = json.loads(run(cli, common, ["search", "--query", "UserService"]).stdout)
        second = json.loads(run(cli, common, ["search", "--query", "UserService"]).stdout)
        classes = [item for item in first if item["name"] == "UserService" and item["kind"] == "CLASS"]
        if len(classes) != 1 or not classes[0]["id"].startswith("lsp-symbol-v1:"):
            raise AssertionError(f"real server did not return one stable class identity: {first}")
        symbol_id = classes[0]["id"]
        if symbol_id not in {item["id"] for item in second}:
            raise AssertionError("semantic symbol identity changed across fresh real-server sessions")

        mark_stage("definition and references")
        definition = json.loads(run(cli, common, ["definition", "--symbol", symbol_id]).stdout)
        if definition["file"] != "src/core/UserService.ts" or definition["character"] != 13:
            raise AssertionError(f"unexpected real-server definition: {definition}")
        references = json.loads(run(cli, common, ["references", "--symbol", symbol_id]).stdout)
        if not any(item["file"] == "src/core/UserService.ts" for item in references):
            raise AssertionError(f"real-server references omitted the declaration: {references}")

        mark_stage("exact compiler diagnostics")
        diagnostics = json.loads(run(cli, common, ["diagnostics"]).stdout)
        if diagnostics:
            raise AssertionError(f"exact compiler diagnostics unexpectedly failed: {diagnostics}")
        if tree_hash(workspace, include_metadata=True) != before_read_only:
            raise AssertionError("read-only semantic lifecycle changed workspace metadata or sources")
        mark_stage("managed rename apply")
        applied = json.loads(run(
            cli, common,
            ["rename", "--file", "src/core/UserService.ts", "--line", "1", "--character", "13", "--to", "AccountService", "--apply"],
        ).stdout)
        if applied.get("status") != "applied" or len(applied.get("changedFilePaths", [])) != 3:
            raise AssertionError(f"managed real-toolchain rename was incomplete: {applied}")
        expected_fragments = {
            "src/app.ts": ("import { AccountService }", "new AccountService()"),
            "src/index.ts": ("export { AccountService as UserService }",),
            "src/core/UserService.ts": ("class AccountService",),
        }
        for relative, fragments in expected_fragments.items():
            content = (workspace / relative).read_text()
            if not all(fragment in content for fragment in fragments):
                raise AssertionError(f"managed rename did not update {relative}: {content}")
        transaction_id = applied.get("transactionId")
        journal = workspace / ".refactorkit" / "transactions" / f"{transaction_id}.json"
        if not transaction_id or not journal.is_file():
            raise AssertionError("managed rename did not create its WAL transaction journal")
        mark_stage("transaction rollback")
        rollback = subprocess.run(
            command_for(cli, ["patch", "rollback", transaction_id, "--root", str(workspace)]),
            text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=45,
            env={key: value for key, value in os.environ.items() if key != "JAVA_HOME"},
        )
        if rollback.returncode != 0:
            raise AssertionError(f"rollback failed: {rollback.stdout}\n{rollback.stderr}")
        if tree_hash(workspace) != before:
            raise AssertionError("real-toolchain rollback did not restore the exact source image")

    print("Packaged TypeScript acceptance passed: central index, real reads, exact compiler diagnostics, apply/WAL and rollback.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        message = f"stage={_current_stage}; {type(error).__name__}: {error}"[:4000]
        message = message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::error title=Packaged TypeScript qualification::{message}", flush=True)
        raise
