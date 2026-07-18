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


def assert_contains(path: Path, required: list[str]) -> None:
    text = path.read_text(encoding="utf-8")
    missing = [item for item in required if item not in text]
    if missing:
        raise AssertionError(f"expected Java post-image fragments missing {missing}: {text}")
    if path.name == "Service.java" and (
            "find(int key) { return String.valueOf(key); }" not in text or "private String key" not in text):
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
            b"    String find(String key, boolean unused) { return key.trim() + this.key; }\n"
            b"    String find(int key) { return String.valueOf(key); }\n"
            b"    String run() { return find(\"x\", true); }\n"
            b"    int size(CharSequence text) { return text.length(); }\n"
            b"    int sizeRun() { return size(\"abc\"); }\n}\n"
        )
        source.write_bytes(original)
        lookup = root / "src/main/java/com/acme/Lookup.java"
        implementation = root / "src/main/java/com/acme/DefaultLookup.java"
        hierarchy_caller = root / "src/main/java/com/acme/HierarchyCaller.java"
        lookup.write_bytes(b"package com.acme; public interface Lookup { String find(String key, boolean unused); }\n")
        implementation.write_bytes(
            b"package com.acme; public class DefaultLookup implements Lookup { "
            b"@Override public String find(String value, boolean ignored) { return value.toString(); } }\n"
        )
        hierarchy_caller.write_bytes(
            b"package com.acme; class HierarchyCaller { "
            b"String api(Lookup lookup) { return lookup.find(\"a\", true); } "
            b"String impl(DefaultLookup lookup) { return lookup.find(\"b\", false); } }\n"
        )
        before = source_hash(root)
        symbol = "com.acme.Service#find(java.lang.String,boolean)"

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
            assert_contains(source, [
                "find(String lookupKey, boolean unused) { return lookupKey.trim() + this.key; }",
            ])
            rolled_back = exchange(process, 4, "tools/call", {
                "name": "rollback_refactoring", "arguments": {"transactionId": transaction_id},
            })["content"][0]["text"]
            if "Rolled back" not in rolled_back or source.read_bytes() != original:
                raise AssertionError(f"MCP rollback failed: {rolled_back}")

            cases = [
                ("changeSignature.changeParameterType", {"name": "text", "type": "String"},
                 ["size(String text)", "size(\"abc\")"], "com.acme.Service#size(java.lang.CharSequence)"),
                ("changeSignature.addParameter", {"type": "int", "name": "limit", "default": "10"},
                 ["find(String key, boolean unused, int limit)", "find(\"x\", true, 10)"], symbol),
                ("changeSignature.reorderParameters", {"order": "unused,key"},
                 ["find(boolean unused, String key)", "find(true, \"x\")"], symbol),
                ("changeSignature.removeParameter", {"name": "unused"},
                 ["find(String key)", "find(\"x\")"], symbol),
            ]
            request_id = 20
            for operation, arguments, required, case_symbol in cases:
                preview_result = exchange(process, request_id, "tools/call", {
                    "name": "preview_refactoring", "arguments": {
                        "operation": operation, "symbol": case_symbol, "arguments": arguments,
                    },
                })["content"][0]["text"]
                if "Status   : PREVIEW" not in preview_result:
                    raise AssertionError(f"MCP {operation} preview refused: {preview_result}")
                plan = preview_result.split("Plan ID  : ", 1)[1].splitlines()[0]
                apply_text = exchange(process, request_id + 1, "tools/call", {
                    "name": "apply_refactoring", "arguments": {"planId": plan},
                })["content"][0]["text"]
                if "Transaction ID: " not in apply_text:
                    raise AssertionError(f"MCP {operation} apply did not return a transaction: {apply_text}")
                transaction = apply_text.split("Transaction ID: ", 1)[1].splitlines()[0]
                assert_contains(source, required)
                exchange(process, request_id + 2, "tools/call", {
                    "name": "rollback_refactoring", "arguments": {"transactionId": transaction},
                })
                if source.read_bytes() != original:
                    raise AssertionError(f"MCP {operation} rollback did not restore exact bytes")
                request_id += 3

            hierarchy_preview = exchange(process, 50, "tools/call", {
                "name": "preview_refactoring", "arguments": {
                    "operation": "changeSignature.addParameter",
                    "symbol": "com.acme.Lookup#find(java.lang.String,boolean)",
                    "arguments": {
                        "type": "int", "name": "limit", "default": "10",
                        "includeHierarchy": True, "acceptExternalConsumerRisk": True,
                    },
                },
            })["content"][0]["text"]
            if "Status   : PREVIEW" not in hierarchy_preview or "2 JDT-connected" not in hierarchy_preview:
                raise AssertionError(f"MCP hierarchy preview failed: {hierarchy_preview}")
            hierarchy_plan = hierarchy_preview.split("Plan ID  : ", 1)[1].splitlines()[0]
            hierarchy_apply = exchange(process, 51, "tools/call", {
                "name": "apply_refactoring", "arguments": {"planId": hierarchy_plan},
            })["content"][0]["text"]
            hierarchy_transaction = hierarchy_apply.split("Transaction ID: ", 1)[1].splitlines()[0]
            assert_contains(lookup, ["find(String key, boolean unused, int limit)"])
            assert_contains(implementation, ["find(String value, boolean ignored, int limit)"])
            assert_contains(hierarchy_caller, ["find(\"a\", true, 10)", "find(\"b\", false, 10)"])
            exchange(process, 52, "tools/call", {
                "name": "rollback_refactoring", "arguments": {"transactionId": hierarchy_transaction},
            })
            if source_hash(root) != before:
                raise AssertionError("MCP hierarchy rollback did not restore exact source bytes")
            remove_preview = exchange(process, 53, "tools/call", {
                "name": "preview_refactoring", "arguments": {
                    "operation": "changeSignature.removeParameter",
                    "symbol": "com.acme.Lookup#find(java.lang.String,boolean)",
                    "arguments": {"name": "unused", "includeHierarchy": True, "acceptExternalConsumerRisk": True},
                },
            })["content"][0]["text"]
            remove_plan = remove_preview.split("Plan ID  : ", 1)[1].splitlines()[0]
            remove_apply = exchange(process, 54, "tools/call", {
                "name": "apply_refactoring", "arguments": {"planId": remove_plan},
            })["content"][0]["text"]
            remove_transaction = remove_apply.split("Transaction ID: ", 1)[1].splitlines()[0]
            assert_contains(hierarchy_caller, ["find(\"a\")", "find(\"b\")"])
            exchange(process, 55, "tools/call", {
                "name": "rollback_refactoring", "arguments": {"transactionId": remove_transaction},
            })
            if source_hash(root) != before:
                raise AssertionError("MCP hierarchy remove rollback did not restore exact bytes")
            reorder_preview = exchange(process, 56, "tools/call", {
                "name": "preview_refactoring", "arguments": {
                    "operation": "changeSignature.reorderParameters",
                    "symbol": "com.acme.Lookup#find(java.lang.String,boolean)",
                    "arguments": {"order": "unused,key", "includeHierarchy": True, "acceptExternalConsumerRisk": True},
                },
            })["content"][0]["text"]
            reorder_plan = reorder_preview.split("Plan ID  : ", 1)[1].splitlines()[0]
            reorder_apply = exchange(process, 57, "tools/call", {
                "name": "apply_refactoring", "arguments": {"planId": reorder_plan},
            })["content"][0]["text"]
            reorder_transaction = reorder_apply.split("Transaction ID: ", 1)[1].splitlines()[0]
            assert_contains(implementation, ["find(boolean ignored, String value)"])
            assert_contains(hierarchy_caller, ["find(true, \"a\")", "find(false, \"b\")"])
            exchange(process, 58, "tools/call", {
                "name": "rollback_refactoring", "arguments": {"transactionId": reorder_transaction},
            })
            if source_hash(root) != before:
                raise AssertionError("MCP hierarchy reorder rollback did not restore exact bytes")
            type_preview = exchange(process, 59, "tools/call", {
                "name": "preview_refactoring", "arguments": {
                    "operation": "changeSignature.changeParameterType",
                    "symbol": "com.acme.Lookup#find(java.lang.String,boolean)",
                    "arguments": {"name": "key", "type": "CharSequence", "includeHierarchy": True, "acceptExternalConsumerRisk": True},
                },
            })["content"][0]["text"]
            type_plan = type_preview.split("Plan ID  : ", 1)[1].splitlines()[0]
            type_apply = exchange(process, 60, "tools/call", {
                "name": "apply_refactoring", "arguments": {"planId": type_plan},
            })["content"][0]["text"]
            type_transaction = type_apply.split("Transaction ID: ", 1)[1].splitlines()[0]
            assert_contains(lookup, ["find(CharSequence key, boolean unused)"])
            assert_contains(implementation, ["find(CharSequence value, boolean ignored)"])
            exchange(process, 61, "tools/call", {
                "name": "rollback_refactoring", "arguments": {"transactionId": type_transaction},
            })
            if source_hash(root) != before:
                raise AssertionError("MCP hierarchy type rollback did not restore exact bytes")
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
            assert_contains(source, [
                "find(String lookupKey, boolean unused) { return lookupKey.trim() + this.key; }",
            ])
            rolled_back = exchange(process, 13, "patch.rollback", {
                "transactionId": applied["transactionId"],
            })
            if rolled_back.get("status") != "rolledBack" or source.read_bytes() != original:
                raise AssertionError(f"daemon rollback failed: {rolled_back}")

            cases = [
                ("changeSignature.changeParameterType", {"name": "text", "type": "String"},
                 ["size(String text)", "size(\"abc\")"], "com.acme.Service#size(java.lang.CharSequence)"),
                ("changeSignature.addParameter", {"type": "int", "name": "limit", "default": "10"},
                 ["find(String key, boolean unused, int limit)", "find(\"x\", true, 10)"], symbol),
                ("changeSignature.reorderParameters", {"order": "unused,key"},
                 ["find(boolean unused, String key)", "find(true, \"x\")"], symbol),
                ("changeSignature.removeParameter", {"name": "unused"},
                 ["find(String key)", "find(\"x\")"], symbol),
            ]
            request_id = 30
            for operation, arguments, required, case_symbol in cases:
                preview_result = exchange(process, request_id, "refactor.preview", {
                    "operation": operation, "symbol": case_symbol, "arguments": arguments,
                })
                applied_result = exchange(process, request_id + 1, "refactor.apply", {
                    "planId": preview_result["planId"],
                })
                assert_contains(source, required)
                exchange(process, request_id + 2, "patch.rollback", {
                    "transactionId": applied_result["transactionId"],
                })
                if source.read_bytes() != original:
                    raise AssertionError(f"daemon {operation} rollback did not restore exact bytes")
                request_id += 3

            hierarchy_preview = exchange(process, 60, "refactor.preview", {
                "operation": "changeSignature.addParameter",
                "symbol": "com.acme.Lookup#find(java.lang.String,boolean)",
                "arguments": {
                    "type": "int", "name": "limit", "default": "10",
                    "includeHierarchy": True, "acceptExternalConsumerRisk": True,
                },
            })
            hierarchy_apply = exchange(process, 61, "refactor.apply", {"planId": hierarchy_preview["planId"]})
            assert_contains(lookup, ["find(String key, boolean unused, int limit)"])
            assert_contains(implementation, ["find(String value, boolean ignored, int limit)"])
            assert_contains(hierarchy_caller, ["find(\"a\", true, 10)", "find(\"b\", false, 10)"])
            exchange(process, 62, "patch.rollback", {"transactionId": hierarchy_apply["transactionId"]})
            if source_hash(root) != before:
                raise AssertionError("daemon hierarchy rollback did not restore exact source bytes")
            remove_preview = exchange(process, 63, "refactor.preview", {
                "operation": "changeSignature.removeParameter",
                "symbol": "com.acme.Lookup#find(java.lang.String,boolean)",
                "arguments": {"name": "unused", "includeHierarchy": True, "acceptExternalConsumerRisk": True},
            })
            remove_apply = exchange(process, 64, "refactor.apply", {"planId": remove_preview["planId"]})
            assert_contains(hierarchy_caller, ["find(\"a\")", "find(\"b\")"])
            exchange(process, 65, "patch.rollback", {"transactionId": remove_apply["transactionId"]})
            if source_hash(root) != before:
                raise AssertionError("daemon hierarchy remove rollback did not restore exact bytes")
            reorder_preview = exchange(process, 66, "refactor.preview", {
                "operation": "changeSignature.reorderParameters",
                "symbol": "com.acme.Lookup#find(java.lang.String,boolean)",
                "arguments": {"order": "unused,key", "includeHierarchy": True, "acceptExternalConsumerRisk": True},
            })
            reorder_apply = exchange(process, 67, "refactor.apply", {"planId": reorder_preview["planId"]})
            assert_contains(implementation, ["find(boolean ignored, String value)"])
            assert_contains(hierarchy_caller, ["find(true, \"a\")", "find(false, \"b\")"])
            exchange(process, 68, "patch.rollback", {"transactionId": reorder_apply["transactionId"]})
            if source_hash(root) != before:
                raise AssertionError("daemon hierarchy reorder rollback did not restore exact bytes")
            type_preview = exchange(process, 69, "refactor.preview", {
                "operation": "changeSignature.changeParameterType",
                "symbol": "com.acme.Lookup#find(java.lang.String,boolean)",
                "arguments": {"name": "key", "type": "CharSequence", "includeHierarchy": True, "acceptExternalConsumerRisk": True},
            })
            type_apply = exchange(process, 70, "refactor.apply", {"planId": type_preview["planId"]})
            assert_contains(lookup, ["find(CharSequence key, boolean unused)"])
            assert_contains(implementation, ["find(CharSequence value, boolean ignored)"])
            exchange(process, 71, "patch.rollback", {"transactionId": type_apply["transactionId"]})
            if source_hash(root) != before:
                raise AssertionError("daemon hierarchy type rollback did not restore exact bytes")
        finally:
            stop(process)

    print("Packaged Java JDT change signature passed: MCP and daemon rename/type/add/reorder/remove plus hierarchy add/remove/reorder/type restored exact bytes.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
