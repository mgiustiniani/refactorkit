#!/usr/bin/env python3
"""End-to-end smoke for the daemon launcher in a self-contained distribution."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

launcher = Path(sys.argv[1]).resolve()
with tempfile.TemporaryDirectory(prefix="refactorkit-daemon-smoke-") as temporary:
    root = Path(temporary)
    target_dir = root / "module" / "src" / "main" / "java" / "com" / "example"
    target_dir.mkdir(parents=True)
    original = b"package com.example;\npublic class App {}\n"
    app = target_dir / "App.java"
    app.write_bytes(original)
    imported = target_dir / "ImportedSmoke.java"

    command = [str(launcher)] if os.name != "nt" else ["cmd", "/c", str(launcher)]
    process = subprocess.Popen(
        command,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
    )

    def call(request_id, method, params=None):
        request = {"jsonrpc": "2.0", "id": request_id, "method": method}
        if params is not None:
            request["params"] = params
        process.stdin.write(json.dumps(request, separators=(",", ":")) + "\n")
        process.stdin.flush()
        line = process.stdout.readline()
        if not line:
            raise AssertionError(f"daemon exited before response to {method}")
        response = json.loads(line)
        if response.get("error") is not None:
            raise AssertionError(f"{method} failed: {response['error']}")
        return response["result"], line

    capabilities, _ = call(1, "server.capabilities")
    importer = next(item for item in capabilities["methods"] if item["name"] == "java.importExternalClass")
    assert importer["features"] == {"targetDirectory": True, "preview": True, "apply": True, "rollback": True}
    call(2, "project.open", {"root": str(root)})
    preview, preview_line = call(3, "java.importExternalClass", {
        "sourceKind": "clipboard",
        "code": "package old.pkg;\npublic class ImportedSmoke {}",
        "targetDirectory": "module/src/main/java/com/example",
        "licensePolicy": "allow",
    })
    assert preview["status"] == "PREVIEW"
    assert preview["resolvedPackage"] == "com.example"
    assert preview["sourceSet"] == "MAIN"
    assert preview["primaryFile"].replace("\\", "/") == "module/src/main/java/com/example/ImportedSmoke.java"
    assert not imported.exists(), "preview wrote the target file"
    assert "public class ImportedSmoke" not in preview_line, "raw clipboard source leaked into response metadata"

    applied, _ = call(4, "refactor.apply", {"planId": preview["planId"]})
    assert imported.read_text(encoding="utf-8").startswith("package com.example;")
    assert applied["primaryFile"].replace("\\", "/").endswith("ImportedSmoke.java")
    transaction_id = applied["transactionId"]
    call(5, "patch.rollback", {"transactionId": transaction_id})
    assert not imported.exists(), "rollback retained created target"
    assert app.read_bytes() == original, "rollback changed pre-existing bytes"

    process.stdin.close()
    process.wait(timeout=20)
    stderr = process.stderr.read()
    assert process.returncode == 0, stderr
    assert "public class ImportedSmoke" not in stderr, "raw clipboard source leaked to daemon logs"

print("Packaged daemon smoke passed: capabilities/open/targetDirectory preview/exact apply/WAL rollback.")
