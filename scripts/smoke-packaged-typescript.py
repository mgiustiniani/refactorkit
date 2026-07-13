#!/usr/bin/env python3
"""Native packaged acceptance against pinned real TypeScript language tooling."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


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
        common = [
            str(workspace), "--node", str(Path(options.node).resolve()),
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
        if not any(item.get("code") == "semantic.diagnosticsIncomplete" for item in diagnostics):
            raise AssertionError(f"upstream unversioned diagnostics did not fail closed: {diagnostics}")
        refusal = run(
            cli, common,
            ["rename", "--file", "src/core/UserService.ts", "--line", "1", "--character", "13", "--to", "AccountService"],
            expected=1,
        )
        if "Exact versioned diagnostics were not published" not in refusal.stderr:
            raise AssertionError(f"managed rename did not preserve exact-version refusal: {refusal.stderr}")
        if tree_hash(workspace) != before:
            raise AssertionError("proposal/refusal acceptance changed source files")
        if (workspace / ".refactorkit" / "transactions").exists():
            raise AssertionError("refused real-toolchain rename created a transaction journal")

    print("Packaged TypeScript acceptance passed: real search/definition/references and exact-version fail-closed rename.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
