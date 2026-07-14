#!/usr/bin/env python3
"""Kill the packaged daemon in JournalState.APPLYING and verify startup recovery."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import shutil
import subprocess
import tempfile
import threading
import time


_current_stage = "startup"


def mark_stage(name: str) -> None:
    global _current_stage
    _current_stage = name
    print(f"Packaged kill recovery stage: {name}", flush=True)


class Daemon:
    def __init__(self, launcher: Path):
        command = (["cmd.exe", "/d", "/s", "/c", str(launcher)] if os.name == "nt" else [str(launcher)])
        self.process = subprocess.Popen(
            command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            text=True, encoding="utf-8",
        )
        self.responses: queue.Queue[str | None] = queue.Queue()
        threading.Thread(target=self._read, daemon=True).start()
        self.next_id = 0

    def _read(self) -> None:
        try:
            for line in self.process.stdout:
                self.responses.put(line)
        finally:
            self.responses.put(None)

    def send(self, method: str, params: dict | None = None) -> int:
        self.next_id += 1
        request = {"jsonrpc": "2.0", "id": self.next_id, "method": method}
        if params is not None:
            request["params"] = params
        self.process.stdin.write(json.dumps(request, separators=(",", ":")) + "\n")
        self.process.stdin.flush()
        return self.next_id

    def receive(self, request_id: int, timeout: float = 120) -> dict:
        try:
            raw = self.responses.get(timeout=timeout)
        except queue.Empty as error:
            raise AssertionError(f"daemon response timeout for request {request_id}") from error
        if raw is None:
            raise AssertionError(f"daemon exited before response {request_id}")
        response = json.loads(raw)
        if response.get("id") != request_id or response.get("error") is not None:
            raise AssertionError(f"daemon request failed: {response}")
        return response["result"]

    def call(self, method: str, params: dict | None = None, timeout: float = 120) -> dict:
        return self.receive(self.send(method, params), timeout)

    def kill_tree(self) -> None:
        if self.process.poll() is not None:
            return
        if os.name == "nt":
            subprocess.run(
                ["taskkill", "/PID", str(self.process.pid), "/T", "/F"], check=True,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
        else:
            self.process.kill()
        self.process.wait(timeout=20)

    def close(self) -> None:
        if self.process.poll() is not None:
            return
        self.process.stdin.close()
        try:
            self.process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            self.kill_tree()


def source_hash(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted((root / "src").rglob("*.ts")):
        digest.update(path.relative_to(root).as_posix().encode())
        digest.update(path.read_bytes())
    return digest.hexdigest()


# Match the bounded external-LSP document-symbol file ceiling so the commit has
# enough durable per-file moves to remain externally observable on Windows.
CONSUMER_COUNT = 255


def create_workspace(root: Path, consumers: int = CONSUMER_COUNT) -> None:
    source = root / "src"
    source.mkdir(parents=True)
    (root / "tsconfig.json").write_text(json.dumps({
        "compilerOptions": {
            "target": "ES2022", "module": "ESNext", "moduleResolution": "Bundler",
            "strict": True, "rootDir": "src",
        },
        "include": ["src/**/*.ts"],
    }))
    (source / "service.ts").write_text("export class Service { value(): number { return 1; } }\n")
    padding = "// bounded acceptance padding " + ("x" * 2048) + "\n"
    for index in range(consumers):
        (source / f"consumer-{index:03d}.ts").write_text(
            'import { Service } from "./service";\n' +
            f"export const value{index:03d} = new Service().value();\n" + padding
        )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime", default="modules/refactorkit-cli/build/package/refactorkit")
    parser.add_argument("--node", default=shutil.which("node"))
    parser.add_argument("--toolchain", default="qualification/typescript-toolchain")
    options = parser.parse_args()
    if not options.node:
        raise AssertionError("node executable is required")

    repository = Path.cwd()
    runtime = (repository / options.runtime).resolve()
    daemon_launcher = runtime / "bin" / ("refactorkit-daemon.bat" if os.name == "nt" else "refactorkit-daemon")
    toolchain = (repository / options.toolchain).resolve()
    server = toolchain / "node_modules" / "typescript-language-server"
    compiler = toolchain / "node_modules" / "typescript"
    node = Path(options.node).resolve()

    with tempfile.TemporaryDirectory(prefix="refactorkit-kill-recovery-") as temporary:
        workspace = Path(temporary) / "workspace"
        create_workspace(workspace)
        expected = source_hash(workspace)
        daemon = Daemon(daemon_launcher)
        try:
            mark_stage("project open")
            daemon.call("project.open", {"root": str(workspace)})
            mark_stage("semantic process start")
            daemon.call("typescript.semantic.start", {
                "languageId": "typescript", "nodeExecutable": str(node),
                "languageServerPackageRoot": str(server), "typeScriptPackageRoot": str(compiler),
            })
            mark_stage("wide rename preview")
            preview = daemon.call("refactor.preview", {
                "operation": "renameSymbol", "languageId": "typescript",
                "arguments": {"newName": "AccountService", "file": "src/service.ts", "line": 0, "character": 13},
            })
            if (
                str(preview.get("status", "")).lower() != "preview"
                or len(preview.get("affectedFiles", [])) != CONSUMER_COUNT + 1
            ):
                raise AssertionError(f"unexpected wide rename preview: {preview}")
            mark_stage("wait for first committed image after durable APPLYING intent")
            daemon.send("refactor.apply", {"planId": preview["planId"]})

            deadline = time.monotonic() + 30
            sentinel = workspace / "src" / "consumer-000.ts"
            while time.monotonic() < deadline and daemon.process.poll() is None:
                try:
                    if "AccountService" in sentinel.read_text():
                        break
                except OSError:
                    pass
                time.sleep(0.001)
            else:
                raise AssertionError("packaged apply never exposed a committed source image")
            mark_stage("kill daemon after first committed image")
            daemon.kill_tree()
            journals = list((workspace / ".refactorkit" / "transactions").glob("transaction-*.json"))
            if len(journals) != 1:
                raise AssertionError(f"expected one interrupted transaction journal, found {len(journals)}")
            applying_journal = journals[0]
        finally:
            daemon.kill_tree()

        mark_stage("startup recovery")
        recovery = Daemon(daemon_launcher)
        try:
            recovery.call("project.open", {"root": str(workspace)}, timeout=60)
        finally:
            recovery.close()
        mark_stage("verify exact recovered source and journal")
        if source_hash(workspace) != expected:
            raise AssertionError("startup recovery did not restore the pre-apply TypeScript source image")
        record = json.loads(applying_journal.read_text())
        if record.get("state") != "ROLLED_BACK" or "interrupted applying" not in (record.get("failure") or ""):
            raise AssertionError(f"unexpected recovered journal state: {record.get('state')} {record.get('failure')}")

    print("Packaged kill-during-write recovery passed: durable APPLYING intent restored exactly on restart.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"::error::stage={_current_stage}; {type(error).__name__}: {error}", flush=True)
        raise
