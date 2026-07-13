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
        opened = exchange("project.open", {"root": str(workspace)})
        if opened.get("error"):
            raise AssertionError(f"daemon project open failed: {opened}")
        parameters = {
            "languageId": "typescript", "nodeExecutable": str(node),
            "languageServerPackageRoot": str(server), "typeScriptPackageRoot": str(compiler),
        }
        started_response = exchange("typescript.semantic.start", parameters)
        if started_response.get("error"):
            raise AssertionError(f"semantic start failed: {started_response}")
        started = started_response["result"]
        old_pid = int(started["processId"])
        if os.name == "nt":
            subprocess.run(["taskkill", "/PID", str(old_pid), "/T", "/F"], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        else:
            os.kill(old_pid, signal.SIGKILL)

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
        diagnosed = exchange("diagnostics", {"languageId": "typescript"})
        if diagnosed.get("error") or diagnosed.get("result") != []:
            raise AssertionError(f"restarted semantic diagnostics failed: {diagnosed}")
        exchange("typescript.semantic.stop", {"languageId": "typescript"})
    finally:
        if process.poll() is None:
            process.stdin.close()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)


def tree_hash(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(p for p in root.rglob("*") if p.is_file() and ".refactorkit" not in p.parts):
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
        node = Path(options.node).resolve()
        qualify_crash_restart(runtime, workspace, node, server, compiler)
        common = [
            str(workspace), "--node", str(node),
            "--language-server-package", str(server), "--typescript-package", str(compiler),
        ]

        first = json.loads(run(cli, common, ["search", "--query", "UserService"]).stdout)
        second = json.loads(run(cli, common, ["search", "--query", "UserService"]).stdout)
        classes = [item for item in first if item["name"] == "UserService" and item["kind"] == "CLASS"]
        if len(classes) != 1 or not classes[0]["id"].startswith("lsp-symbol-v1:"):
            raise AssertionError(f"real server did not return one stable class identity: {first}")
        symbol_id = classes[0]["id"]
        if symbol_id not in {item["id"] for item in second}:
            raise AssertionError("semantic symbol identity changed across fresh real-server sessions")

        definition = json.loads(run(cli, common, ["definition", "--symbol", symbol_id]).stdout)
        if definition["file"] != "src/core/UserService.ts" or definition["character"] != 13:
            raise AssertionError(f"unexpected real-server definition: {definition}")
        references = json.loads(run(cli, common, ["references", "--symbol", symbol_id]).stdout)
        if not any(item["file"] == "src/core/UserService.ts" for item in references):
            raise AssertionError(f"real-server references omitted the declaration: {references}")

        diagnostics = json.loads(run(cli, common, ["diagnostics"]).stdout)
        if diagnostics:
            raise AssertionError(f"exact compiler diagnostics unexpectedly failed: {diagnostics}")
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
        rollback = subprocess.run(
            command_for(cli, ["patch", "rollback", transaction_id, "--root", str(workspace)]),
            text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=45,
            env={key: value for key, value in os.environ.items() if key != "JAVA_HOME"},
        )
        if rollback.returncode != 0:
            raise AssertionError(f"rollback failed: {rollback.stdout}\n{rollback.stderr}")
        if tree_hash(workspace) != before:
            raise AssertionError("real-toolchain rollback did not restore the exact source image")

    print("Packaged TypeScript acceptance passed: real reads, exact compiler diagnostics, apply/WAL and rollback.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
