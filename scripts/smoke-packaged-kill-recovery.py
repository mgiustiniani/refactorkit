#!/usr/bin/env python3
"""Kill the packaged daemon in JournalState.APPLYING and verify startup recovery."""

from __future__ import annotations

import argparse
from collections.abc import Callable
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
APPLY_ABSOLUTE_TIMEOUT_SECONDS = 300
APPLY_STALL_TIMEOUT_SECONDS = 120
APPLY_POLL_SECONDS = 0.001
APPLY_PROGRESS_PROBE_SECONDS = 0.05


def await_first_committed_image(
    daemon: Daemon,
    workspace: Path,
    *,
    absolute_timeout_seconds: float = APPLY_ABSOLUTE_TIMEOUT_SECONDS,
    stall_timeout_seconds: float = APPLY_STALL_TIMEOUT_SECONDS,
    poll_seconds: float = APPLY_POLL_SECONDS,
    progress_probe_seconds: float = APPLY_PROGRESS_PROBE_SECONDS,
    monotonic: Callable[[], float] = time.monotonic,
    sleep: Callable[[float], None] = time.sleep,
) -> Path:
    """Wait for a mixed-image APPLYING state while accepting slow, progressing staging."""
    started = monotonic()
    absolute_deadline = started + absolute_timeout_seconds
    progress_deadline = started + stall_timeout_seconds
    transaction_directory = workspace / ".refactorkit" / "transactions"
    sentinel = workspace / "src" / "consumer-000.ts"
    journal: Path | None = None
    journal_state: str | None = None
    max_staged_files = 0
    next_progress_probe = started

    while monotonic() < absolute_deadline:
        now = monotonic()
        if daemon.process.poll() is not None:
            raise AssertionError(
                "packaged daemon exited before a committed source image "
                f"(state={journal_state}, maxStagedFiles={max_staged_files})"
            )

        try:
            committed = "AccountService" in sentinel.read_text()
        except OSError:
            committed = False

        if journal is None:
            candidates = list(transaction_directory.glob("transaction-*.json"))
            if len(candidates) == 1:
                journal = candidates[0]
                progress_deadline = now + stall_timeout_seconds
        if journal is not None:
            try:
                observed_state = json.loads(journal.read_text()).get("state")
                if observed_state != journal_state:
                    journal_state = observed_state
                    progress_deadline = now + stall_timeout_seconds
            except (OSError, json.JSONDecodeError):
                pass

        if now >= next_progress_probe:
            try:
                staged_files = sum(1 for _ in workspace.rglob(".refactorkit-stage-*.tmp"))
            except OSError:
                staged_files = max_staged_files
            if staged_files > max_staged_files:
                max_staged_files = staged_files
                progress_deadline = now + stall_timeout_seconds
            next_progress_probe = now + progress_probe_seconds

        if committed:
            if journal is None:
                raise AssertionError(
                    "committed source image appeared without a transaction journal "
                    f"(state={journal_state}, maxStagedFiles={max_staged_files})"
                )
            return journal
        if now >= progress_deadline:
            raise AssertionError(
                "packaged apply made no staging progress before exposing a committed source image "
                f"(state={journal_state}, maxStagedFiles={max_staged_files}, "
                f"stallSeconds={stall_timeout_seconds})"
            )
        sleep(poll_seconds)

    raise AssertionError(
        "packaged apply did not expose a committed source image within the absolute bound "
        f"(state={journal_state}, maxStagedFiles={max_staged_files}, "
        f"timeoutSeconds={absolute_timeout_seconds})"
    )


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

            # PatchEngine durably stages every post-image before committing the first
            # one. Native Windows antivirus/filesystem latency is allowed while staged
            # temp-file count advances, but both inactivity and total wait stay bounded.
            applying_journal = await_first_committed_image(daemon, workspace)
            mark_stage("kill daemon after first committed image")
            daemon.kill_tree()
        finally:
            daemon.kill_tree()

        mark_stage("explicit restart recovery")
        recovery = Daemon(daemon_launcher)
        try:
            recovered = recovery.call("patch.recover", {"root": str(workspace)}, timeout=60)
            if recovered.get("recovered") is not True:
                raise AssertionError(f"explicit recovery was not acknowledged: {recovered}")
            recovery.call("project.open", {"root": str(workspace)}, timeout=60)
        finally:
            recovery.close()
        mark_stage("verify exact recovered source and journal")
        if source_hash(workspace) != expected:
            raise AssertionError("startup recovery did not restore the pre-apply TypeScript source image")
        record = json.loads(applying_journal.read_text())
        if record.get("state") != "ROLLED_BACK" or "interrupted applying" not in (record.get("failure") or ""):
            raise AssertionError(f"unexpected recovered journal state: {record.get('state')} {record.get('failure')}")

    print("Packaged kill-during-write recovery passed: explicit restart recovery restored durable APPLYING intent exactly.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"::error::stage={_current_stage}; {type(error).__name__}: {error}", flush=True)
        raise
