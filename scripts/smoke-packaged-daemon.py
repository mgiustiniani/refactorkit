#!/usr/bin/env python3
"""Bounded end-to-end smoke for the self-contained daemon launcher."""
import json
import os
from pathlib import Path
import queue
import shutil
import subprocess
import sys
import tempfile
import threading

STARTUP_TIMEOUT_SECONDS = float(os.environ.get("RK_DAEMON_STARTUP_TIMEOUT", "20"))
RESPONSE_TIMEOUT_SECONDS = float(os.environ.get("RK_DAEMON_RESPONSE_TIMEOUT", "30"))
SHUTDOWN_TIMEOUT_SECONDS = float(os.environ.get("RK_DAEMON_SHUTDOWN_TIMEOUT", "20"))
MAX_STDERR_BYTES = 65_536
SOURCE_MARKER = "public class ImportedSmoke"
DISCARDED_SOURCE_MARKER = "public class PrivateDiscardedSource"

provided_launcher = Path(sys.argv[1]).resolve()
process = None
stderr_bytes = bytearray()
stderr_lock = threading.Lock()
stderr_source_leaked = False
stdout_queue = queue.Queue()


def bounded_stderr_text():
    with stderr_lock:
        return bytes(stderr_bytes).decode("utf-8", errors="replace")


def capture_stdout(stream):
    try:
        while True:
            line = stream.readline()
            if line == "":
                stdout_queue.put(None)
                return
            stdout_queue.put(line)
    except Exception:
        stdout_queue.put(None)


def capture_stderr(stream):
    global stderr_source_leaked
    try:
        for line in iter(stream.readline, ""):
            if SOURCE_MARKER in line or DISCARDED_SOURCE_MARKER in line:
                stderr_source_leaked = True
            encoded = line.encode("utf-8", errors="replace")
            with stderr_lock:
                available = MAX_STDERR_BYTES - len(stderr_bytes)
                if available > 0:
                    stderr_bytes.extend(encoded[:available])
    except Exception:
        return


try:
    with tempfile.TemporaryDirectory(prefix="refactorkit daemon smoke ") as temporary:
        temporary_path = Path(temporary)
        package_copy = temporary_path / "installed package with spaces"
        shutil.copytree(provided_launcher.parent.parent, package_copy)
        launcher = package_copy / "bin" / provided_launcher.name

        root = temporary_path / "workspace with spaces"
        target_dir = root / "module" / "src" / "main" / "java" / "com" / "example"
        target_dir.mkdir(parents=True)
        original = b"package com.example;\npublic class App {}\n"
        app = target_dir / "App.java"
        app.write_bytes(original)
        imported = target_dir / "ImportedSmoke.java"

        command = [str(launcher)] if os.name != "nt" else ["cmd.exe", "/d", "/c", "call", str(launcher)]
        process = subprocess.Popen(
            command,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
        )
        threading.Thread(target=capture_stdout, args=(process.stdout,), daemon=True).start()
        threading.Thread(target=capture_stderr, args=(process.stderr,), daemon=True).start()

        def exchange(request_id, method, params=None, timeout=RESPONSE_TIMEOUT_SECONDS):
            request = {"jsonrpc": "2.0", "id": request_id, "method": method}
            if params is not None:
                request["params"] = params
            process.stdin.write(json.dumps(request, separators=(",", ":")) + "\n")
            process.stdin.flush()
            try:
                line = stdout_queue.get(timeout=timeout)
            except queue.Empty as error:
                raise AssertionError(f"daemon response timeout for {method}") from error
            if line is None:
                raise AssertionError(f"daemon exited before response to {method}")
            try:
                response = json.loads(line)
            except json.JSONDecodeError as error:
                raise AssertionError(f"daemon emitted non-JSON stdout for {method}") from error
            if not isinstance(response, dict) or response.get("jsonrpc") != "2.0" or response.get("id") != request_id:
                raise AssertionError(f"daemon emitted an invalid JSON-RPC envelope for {method}")
            return response

        def call(request_id, method, params=None, timeout=RESPONSE_TIMEOUT_SECONDS):
            response = exchange(request_id, method, params, timeout)
            if response.get("error") is not None:
                raise AssertionError(f"daemon RPC failed for {method}")
            return response["result"]

        def call_error(request_id, method, params=None):
            response = exchange(request_id, method, params)
            if response.get("error") is None:
                raise AssertionError(f"daemon RPC unexpectedly succeeded for {method}")
            return response["error"]

        capabilities = call(1, "server.capabilities", timeout=STARTUP_TIMEOUT_SECONDS)
        importer = next(item for item in capabilities["methods"] if item["name"] == "java.importExternalClass")
        assert all(importer["features"][feature] for feature in (
            "targetDirectory", "preview", "renderedDiff", "structuredDiff",
            "previewDiagnostics", "apply", "discard", "rollback",
        ))
        call(2, "project.open", {"root": str(root)})
        preview = call(3, "java.importExternalClass", {
            "sourceKind": "clipboard",
            "code": "package old.pkg;\n" + SOURCE_MARKER + " {}",
            "targetDirectory": "module/src/main/java/com/example",
            "licensePolicy": "allow",
        })
        assert preview["status"] == "preview"
        assert preview["placement"]["packageName"] == "com.example"
        assert preview["placement"]["sourceSet"] == "main"
        assert preview["primaryFile"] == "module/src/main/java/com/example/ImportedSmoke.java"
        assert preview["renderedDiff"].startswith("--- /dev/null")
        assert preview["structuredDiff"][0]["hunks"]
        assert not imported.exists(), "preview wrote the target file"

        applied = call(4, "refactor.apply", {"planId": preview["planId"]})
        assert imported.read_text(encoding="utf-8").startswith("package com.example;")
        assert applied["primaryFile"].endswith("ImportedSmoke.java")
        assert applied["changedFiles"][0]["change"] == "create"
        transaction_id = applied["transactionId"]
        rolled_back = call(5, "patch.rollback", {"transactionId": transaction_id})
        assert rolled_back["rolledBack"] is True
        assert rolled_back["changedFiles"][0]["change"] == "delete"
        assert not imported.exists(), "rollback retained created target"
        assert app.read_bytes() == original, "rollback changed pre-existing bytes"

        private_preview = call(6, "java.importExternalClass", {
            "sourceKind": "clipboard",
            "code": DISCARDED_SOURCE_MARKER + " {}",
            "targetDirectory": "module/src/main/java/com/example",
            "licensePolicy": "allow",
        })
        discarded = call(7, "refactor.discard", {"planId": private_preview["planId"]})
        assert discarded["discarded"] is True
        error = call_error(8, "refactor.apply", {"planId": private_preview["planId"]})
        assert DISCARDED_SOURCE_MARKER not in json.dumps(error), "discarded source leaked in RPC error"

        process.stdin.close()
        try:
            process.wait(timeout=SHUTDOWN_TIMEOUT_SECONDS)
        except subprocess.TimeoutExpired as error:
            raise AssertionError("daemon did not terminate after stdin EOF") from error
        assert process.returncode == 0, "daemon returned a non-zero exit status"
        assert not stderr_source_leaked, "raw clipboard source leaked to daemon stderr"
finally:
    if process is not None and process.poll() is None:
        process.kill()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            pass
    if stderr_source_leaked:
        raise AssertionError("raw clipboard source leaked to daemon stderr")

print("Packaged daemon smoke passed: bounded RPC, spaced launcher, preview, exact apply, WAL rollback, EOF shutdown.")
