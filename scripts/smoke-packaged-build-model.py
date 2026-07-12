#!/usr/bin/env python3
"""Native packaged acceptance for Maven build models and moveSourceRoot WAL rollback."""

from __future__ import annotations

import hashlib
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parents[1]
SAMPLE = REPO / "samples" / "java-maven-reactor-21"
RUNTIME = REPO / "modules" / "refactorkit-cli" / "build" / "package" / "refactorkit"
LAUNCHER = RUNTIME / "bin" / ("refactorkit.bat" if os.name == "nt" else "refactorkit")
CLEAN_ENV = os.environ.copy()
CLEAN_ENV.pop("JAVA_HOME", None)
FROM = pathlib.Path("domain/src/main/java")
TO = pathlib.Path("domain-relocated/src/main/java")
RELATIVE_TYPE = pathlib.Path("example/reactor/domain/DomainValue.java")


def command(*args: str) -> list[str]:
    invocation = [str(LAUNCHER), *args]
    return ["cmd", "/d", "/c", *invocation] if os.name == "nt" else invocation


def run(*args: str, expected: int = 0) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(command(*args), text=True, capture_output=True, timeout=180, env=CLEAN_ENV)
    if result.returncode != expected:
        raise AssertionError(
            f"command {args!r} returned {result.returncode}, expected {expected}\n"
            f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
        )
    return result


def source_hashes(root: pathlib.Path) -> dict[str, str]:
    return {
        path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(root.rglob("*.java"))
        if ".refactorkit" not in path.parts
    }


def main() -> int:
    if not LAUNCHER.is_file():
        raise AssertionError(f"packaged launcher not found: {LAUNCHER}")
    with tempfile.TemporaryDirectory(prefix="refactorkit-native-build-model-") as temporary:
        workspace = pathlib.Path(temporary) / "reactor"
        shutil.copytree(SAMPLE, workspace)
        before = source_hashes(workspace)
        source = workspace / FROM / RELATIVE_TYPE
        destination = workspace / TO / RELATIVE_TYPE
        original_bytes = source.read_bytes()

        scan = run("scan", str(workspace))
        if not re.search(r"Modules\s*:\s*6", scan.stdout) or not re.search(r"Files\s*:\s*[1-9]", scan.stdout):
            raise AssertionError(f"reactor scan summary missing:\n{scan.stdout}")
        diagnostics = run("diagnostics", str(workspace))
        if "No diagnostics." not in diagnostics.stdout:
            raise AssertionError(f"reactor diagnostics are not clean:\n{diagnostics.stdout}")

        preview = run(
            "java", "move-source-root", "--from", FROM.as_posix(), "--to", TO.as_posix(),
            "--root", str(workspace),
        )
        if "Status: PREVIEW" not in preview.stdout or "rename domain/src/main/java" not in preview.stdout:
            raise AssertionError(f"rename-only preview missing:\n{preview.stdout}")
        if not source.is_file() or destination.exists():
            raise AssertionError("preview mutated the workspace")

        applied = run(
            "java", "move-source-root", "--from", FROM.as_posix(), "--to", TO.as_posix(),
            "--root", str(workspace), "--apply",
        )
        match = re.search(r"Applied\. Transaction: ([A-Za-z0-9_-]+)", applied.stdout)
        if not match:
            raise AssertionError(f"transaction ID missing after apply:\n{applied.stdout}")
        transaction_id = match.group(1)
        if source.exists() or destination.read_bytes() != original_bytes:
            raise AssertionError("apply did not perform a byte-identical source-root rename")
        transaction_dir = workspace / ".refactorkit" / "transactions"
        if not transaction_dir.is_dir() or not any(transaction_dir.iterdir()):
            raise AssertionError("managed apply did not persist transaction/WAL metadata")
        diagnostics_after = run("diagnostics", str(workspace))
        if "No diagnostics." not in diagnostics_after.stdout:
            raise AssertionError(f"post-apply diagnostics are not clean:\n{diagnostics_after.stdout}")

        rollback = run("patch", "rollback", transaction_id, "--root", str(workspace))
        if "Rolled back" not in rollback.stdout:
            raise AssertionError(f"rollback confirmation missing:\n{rollback.stdout}")
        if source_hashes(workspace) != before or source.read_bytes() != original_bytes or destination.exists():
            raise AssertionError("rollback did not restore the byte-identical Java source tree")

        collision = workspace / TO / RELATIVE_TYPE.with_name("domainvalue.java")
        collision.parent.mkdir(parents=True, exist_ok=True)
        collision.write_text("package example.reactor.domain; class collision {}\n", encoding="utf-8")
        refused = subprocess.run(
            command(
                "java", "move-source-root", "--from", FROM.as_posix(), "--to", TO.as_posix(),
                "--root", str(workspace),
            ),
            text=True,
            capture_output=True,
            timeout=180,
            env=CLEAN_ENV,
        )
        combined = refused.stdout + refused.stderr
        if refused.returncode == 0 or "sourceRoot.destinationCollision" not in combined:
            raise AssertionError(
                f"case-folded collision was not refused ({refused.returncode}):\n{combined}"
            )

    print("Packaged build-model acceptance passed: reactor scan/diagnostics, rename-only preview, apply/WAL/rollback, case-fold collision.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"Packaged build-model acceptance failed: {error}", file=sys.stderr)
        raise
