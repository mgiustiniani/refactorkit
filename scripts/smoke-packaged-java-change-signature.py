#!/usr/bin/env python3
"""Smoke-test packaged managed Java parameter rename through daemon and MCP."""
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


def start(path: Path) -> subprocess.Popen:
    return subprocess.Popen(
        command(path), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, text=True, encoding="utf-8",
    )


def exchange(process: subprocess.Popen, request_id: int, method: str, params: dict) -> dict:
    request = {"jsonrpc": "2.0", "id": request_id, "method": method, "params": params}
    process.stdin.write(json.dumps(request, separators=(",", ":")) + "\n")
    process.stdin.flush()
    line = process.stdout.readline()
    if not line:
        raise AssertionError(f"{method} produced no response: {process.stderr.read()}")
    response = json.loads(line)
    if response.get("id") != request_id:
        raise AssertionError(f"{method} returned the wrong request ID: {response}")
    return response


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


def transaction_files(root: Path) -> tuple[str, ...]:
    directory = root / ".refactorkit" / "transactions"
    return tuple(sorted(path.relative_to(root).as_posix() for path in directory.rglob("*") if path.is_file())) if directory.exists() else ()


def require_preview_read_only(root: Path, source: Path, original: bytes, previous: tuple[str, ...]) -> None:
    if source.read_bytes() != original or transaction_files(root) != previous:
        raise AssertionError("Parameter rename preview changed the source or transaction files")


def require_transaction(root: Path, previous: tuple[str, ...], transaction_id: str, state: str) -> None:
    journal = f".refactorkit/transactions/{transaction_id}.json"
    if set(transaction_files(root)) != set(previous) | {journal} or journal in previous:
        raise AssertionError(f"Expected one new transaction journal for {transaction_id}")
    if json.loads((root / journal).read_text(encoding="utf-8"))["state"] != state:
        raise AssertionError(f"Transaction {transaction_id} did not reach {state}")


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
        source.write_text(
            "package com.acme; public class Service { "
            "public String execute(String value) { return value.trim(); } }\n",
            encoding="utf-8",
        )
        descriptor = root / "pom.xml"
        descriptor.write_text(
            "<project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId>"
            "<artifactId>app</artifactId><version>1.0</version>"
            "<properties><maven.compiler.release>8</maven.compiler.release></properties></project>\n",
            encoding="utf-8",
        )
        original = source.read_bytes()
        original_descriptor = descriptor.read_bytes()
        expected = original.replace(b"String value", b"String lookupKey").replace(b"return value.", b"return lookupKey.")

        process = start(daemon)
        try:
            opened = exchange(process, 1, "project.open", {"root": str(root)})
            snapshot_hash = opened["result"]["snapshotHash"]
            before_transactions = transaction_files(root)
            preview = exchange(process, 2, "refactor.preview", {
                "operation": "changeSignature.renameParameter",
                "languageId": "java",
                "symbol": "com.acme.Service#execute",
                "arguments": {"oldName": "value", "newName": "lookupKey"},
                "expectedSnapshotHash": snapshot_hash,
            })["result"]
            if preview.get("status") != "PREVIEW":
                raise AssertionError(f"daemon did not return a parameter-rename preview: {preview}")
            require_preview_read_only(root, source, original, before_transactions)
            applied = exchange(process, 3, "refactor.apply", {"planId": preview["planId"]}).get("result", {})
            if applied.get("status") != "applied" or source.read_bytes() != expected:
                raise AssertionError(f"daemon did not apply the exact parameter rename: {applied}")
            transaction_id = applied["transactionId"]
            require_transaction(root, before_transactions, transaction_id, "APPLIED")
            rolled = exchange(process, 4, "patch.rollback", {"transactionId": transaction_id}).get("result", {})
            if (
                rolled.get("status") != "rolledBack"
                or rolled.get("transactionId") != transaction_id
                or source.read_bytes() != original
            ):
                raise AssertionError(f"daemon did not restore the original bytes: {rolled}")
            require_transaction(root, before_transactions, transaction_id, "ROLLED_BACK")
            if descriptor.read_bytes() != original_descriptor:
                raise AssertionError("daemon parameter rename changed the Maven descriptor")
        finally:
            stop(process)

        process = start(mcp)
        try:
            scanned = exchange(process, 10, "tools/call", {
                "name": "project_scan", "arguments": {"root": str(root)},
            })["result"]
            if scanned.get("isError") is True:
                raise AssertionError(f"MCP project_scan failed: {scanned}")
            before_transactions = transaction_files(root)
            preview = exchange(process, 11, "tools/call", {
                "name": "preview_refactoring",
                "arguments": {
                    "operation": "changeSignature.renameParameter",
                    "symbol": "com.acme.Service#execute",
                    "arguments": {"oldName": "value", "newName": "lookupKey"},
                },
            })["result"]
            if preview.get("isError") is True:
                raise AssertionError(f"MCP parameter-rename preview failed: {preview}")
            preview_text = preview["content"][0]["text"]
            if "JDT-proven parameter" not in preview_text:
                raise AssertionError(f"MCP did not return the JDT-bound preview: {preview_text}")
            plan_id = next(line.split(":", 1)[1].strip() for line in preview_text.splitlines()
                           if line.strip().startswith("Plan ID"))
            require_preview_read_only(root, source, original, before_transactions)
            applied = exchange(process, 12, "tools/call", {
                "name": "apply_refactoring", "arguments": {"planId": plan_id},
            })["result"]
            applied_text = applied["content"][0]["text"]
            if (
                applied.get("isError") is True
                or not applied_text.startswith("Applied successfully.")
                or source.read_bytes() != expected
            ):
                raise AssertionError(f"MCP did not apply the exact parameter rename: {applied_text}")
            transaction_id = next(line.partition(":")[2].strip() for line in applied_text.splitlines()
                                  if line.strip().startswith("Transaction ID:"))
            require_transaction(root, before_transactions, transaction_id, "APPLIED")
            rolled = exchange(process, 13, "tools/call", {
                "name": "rollback_refactoring", "arguments": {"transactionId": transaction_id},
            })["result"]
            rolled_text = rolled["content"][0]["text"]
            if (
                rolled.get("isError") is True
                or "Rolled back" not in rolled_text
                or transaction_id not in rolled_text
                or source.read_bytes() != original
            ):
                raise AssertionError(f"MCP did not restore the original bytes: {rolled_text}")
            require_transaction(root, before_transactions, transaction_id, "ROLLED_BACK")
            if descriptor.read_bytes() != original_descriptor:
                raise AssertionError("MCP parameter rename changed the Maven descriptor")
        finally:
            stop(process)

    print("Packaged Java parameter rename passed through daemon and MCP: read-only preview, exact apply, WAL rollback.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as failure:
        print(f"Packaged Java change-signature smoke failed: {failure}", file=sys.stderr)
        raise
