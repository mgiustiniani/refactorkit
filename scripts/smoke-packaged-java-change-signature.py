#!/usr/bin/env python3
"""Packaged daemon/MCP acceptance for JDT-bound Java parameter rename."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


def launcher(package: Path, name: str) -> Path:
    suffix = ".bat" if os.name == "nt" else ""
    return package / "bin" / f"{name}{suffix}"


def command(path: Path) -> list[str]:
    if os.name == "nt":
        return ["cmd.exe", "/d", "/c", "call", str(path)]
    return [str(path)]


def source_hash(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(root.rglob("*.java")):
        digest.update(path.relative_to(root).as_posix().encode())
        digest.update(b"\0")
        digest.update(path.read_bytes())
    return digest.hexdigest()


def start(path: Path) -> subprocess.Popen:
    return subprocess.Popen(
        command(path), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, text=True, encoding="utf-8",
    )


def exchange(process: subprocess.Popen, request_id: int, method: str, params: dict | None = None) -> dict:
    request = {"jsonrpc": "2.0", "id": request_id, "method": method}
    if params is not None:
        request["params"] = params
    process.stdin.write(json.dumps(request, separators=(",", ":")) + "\n")
    process.stdin.flush()
    line = process.stdout.readline()
    if not line:
        raise AssertionError(f"{method} produced no response: {process.stderr.read()}")
    response = json.loads(line)
    if response.get("error") is not None:
        raise AssertionError(f"{method} failed: {response}")
    return response["result"]


def stop(process: subprocess.Popen) -> None:
    if process.poll() is not None:
        return
    process.stdin.close()
    try:
        process.wait(timeout=20)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=20)
    if process.returncode != 0:
        raise AssertionError(f"packaged process exited {process.returncode}: {process.stderr.read()}")


def assert_renamed(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if "find(String lookupKey) { return lookupKey.trim() + this.key; }" not in text:
        raise AssertionError(f"selected JDT parameter was not renamed: {text}")
    if "find(int key) { return String.valueOf(key); }" not in text or "private String key" not in text:
        raise AssertionError(f"same-name field/overload was changed: {text}")


def main() -> int:
    package = Path(sys.argv[1]).resolve()
    daemon = launcher(package, "refactorkit-daemon")
    mcp = launcher(package, "refactorkit-mcp")
    if not daemon.is_file() or not mcp.is_file():
        raise AssertionError(f"packaged daemon/MCP launchers missing under {package}")

    with tempfile.TemporaryDirectory(prefix="refactorkit-java-signature-") as temporary:
        root = Path(temporary)
        source = root / "src/main/java/com/acme/Service.java"
        source.parent.mkdir(parents=True)
        original = (
            b"package com.acme;\npublic class Service {\n"
            b"    private String key = \"field\";\n"
            b"    String find(String key) { return key.trim() + this.key; }\n"
            b"    String find(int key) { return String.valueOf(key); }\n}\n"
        )
        source.write_bytes(original)
        before = source_hash(root)
        symbol = "com.acme.Service#find(java.lang.String)"

        process = start(mcp)
        try:
            exchange(process, 1, "tools/call", {"name": "project_scan", "arguments": {"root": str(root)}})
            preview = exchange(process, 2, "tools/call", {
                "name": "preview_refactoring",
                "arguments": {
                    "operation": "changeSignature.renameParameter", "symbol": symbol,
                    "arguments": {"oldName": "key", "newName": "lookupKey"},
                },
            })
            preview_text = preview["content"][0]["text"]
            if "JDT-proven parameter" not in preview_text or source_hash(root) != before:
                raise AssertionError(f"MCP preview failed or wrote sources: {preview_text}")
            plan_id = preview_text.split("Plan ID  : ", 1)[1].splitlines()[0]
            applied = exchange(process, 3, "tools/call", {
                "name": "apply_refactoring", "arguments": {"planId": plan_id},
            })["content"][0]["text"]
            transaction_id = applied.split("Transaction ID: ", 1)[1].splitlines()[0]
            assert_renamed(source)
            rolled_back = exchange(process, 4, "tools/call", {
                "name": "rollback_refactoring", "arguments": {"transactionId": transaction_id},
            })["content"][0]["text"]
            if "Rolled back" not in rolled_back or source.read_bytes() != original:
                raise AssertionError(f"MCP rollback failed: {rolled_back}")
        finally:
            stop(process)

        process = start(daemon)
        try:
            exchange(process, 10, "project.open", {"root": str(root)})
            preview = exchange(process, 11, "refactor.preview", {
                "operation": "changeSignature.renameParameter", "symbol": symbol,
                "arguments": {"oldName": "key", "newName": "lookupKey"},
            })
            if preview.get("status") != "PREVIEW" or source_hash(root) != before:
                raise AssertionError(f"daemon preview failed or wrote sources: {preview}")
            applied = exchange(process, 12, "refactor.apply", {"planId": preview["planId"]})
            if applied.get("status") != "applied":
                raise AssertionError(f"daemon apply failed: {applied}")
            assert_renamed(source)
            rolled_back = exchange(process, 13, "patch.rollback", {
                "transactionId": applied["transactionId"],
            })
            if rolled_back.get("status") != "rolledBack" or source.read_bytes() != original:
                raise AssertionError(f"daemon rollback failed: {rolled_back}")
        finally:
            stop(process)

    print("Packaged Java JDT parameter rename passed: MCP and daemon preview/apply/rollback restored exact bytes.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
