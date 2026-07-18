#!/usr/bin/env python3
"""Permanent packaged acceptance for release-aware Java/Maven diagnostics."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def command_for(executable: Path, args: list[str]) -> list[str]:
    if os.name == "nt":
        return [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/s", "/c", str(executable), *args]
    return [str(executable), *args]


def tree_hash(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(item for item in root.rglob("*") if item.is_file()):
        digest.update(path.relative_to(root).as_posix().encode())
        digest.update(path.read_bytes())
    return digest.hexdigest()


def write_reactor(root: Path, release: int) -> None:
    root.mkdir(parents=True)
    (root / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>"
        "<artifactId>reactor</artifactId><version>1</version><packaging>pom</packaging>"
        f"<properties><maven.compiler.release>{release}</maven.compiler.release></properties>"
        "<modules><module>api</module><module>infrastructure</module></modules></project>\n",
        encoding="utf-8",
    )
    api = root / "api/src/main/java/fixture/api/ReactorApi.java"
    api.parent.mkdir(parents=True)
    api.write_text("package fixture.api; public interface ReactorApi { String value(); }\n", encoding="utf-8")
    (root / "api/pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion><parent><groupId>fixture</groupId>"
        "<artifactId>reactor</artifactId><version>1</version></parent><artifactId>api</artifactId></project>\n",
        encoding="utf-8",
    )
    infra = root / "infrastructure/src/main/java/fixture/infra/RepositoryAdapter.java"
    infra.parent.mkdir(parents=True)
    infra.write_text(
        "package fixture.infra;\n"
        "import fixture.api.ReactorApi;\n"
        "import java.sql.Connection;\n"
        "import java.net.http.HttpClient;\n"
        "public final class RepositoryAdapter {\n"
        "  ReactorApi api; Connection connection; HttpClient client = HttpClient.newHttpClient();\n"
        "  boolean blank(String value) { return value.isBlank(); }\n"
        "}\n",
        encoding="utf-8",
    )
    (root / "infrastructure/pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion><parent><groupId>fixture</groupId>"
        "<artifactId>reactor</artifactId><version>1</version></parent><artifactId>infrastructure</artifactId>"
        "<dependencies><dependency><groupId>fixture</groupId><artifactId>api</artifactId>"
        "<version>1</version></dependency></dependencies></project>\n",
        encoding="utf-8",
    )


def run_cli(cli: Path, root: Path, module: str | None = None) -> subprocess.CompletedProcess[str]:
    environment = os.environ.copy()
    environment.pop("JAVA_HOME", None)
    return subprocess.run(
        command_for(cli, ["diagnostics", str(root), *(["--module", module] if module else [])]),
        text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        env=environment, timeout=120,
    )


def daemon_diagnostics(daemon: Path, root: Path, module: str | None = None) -> list[dict]:
    environment = os.environ.copy()
    environment.pop("JAVA_HOME", None)
    process = subprocess.Popen(
        command_for(daemon, []), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, text=True, encoding="utf-8", env=environment,
    )
    try:
        def exchange(request_id: int, method: str, params: dict | None = None):
            request = {"jsonrpc": "2.0", "id": request_id, "method": method}
            if params is not None:
                request["params"] = params
            process.stdin.write(json.dumps(request, separators=(",", ":")) + "\n")
            process.stdin.flush()
            response = json.loads(process.stdout.readline())
            if response.get("error") is not None:
                raise AssertionError(f"daemon {method} failed: {response}")
            return response["result"]

        exchange(1, "project.open", {"root": str(root)})
        return exchange(2, "diagnostics", {"languageId": "java", **({"module": module} if module else {})})
    finally:
        process.terminate()
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            process.kill(); process.wait(timeout=20)


def mcp_diagnostics(mcp: Path, root: Path) -> str:
    environment = os.environ.copy()
    environment.pop("JAVA_HOME", None)
    process = subprocess.Popen(
        command_for(mcp, []), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, text=True, encoding="utf-8", env=environment,
    )
    try:
        def tool(request_id: int, name: str, arguments: dict) -> str:
            request = {"jsonrpc": "2.0", "id": request_id, "method": "tools/call",
                       "params": {"name": name, "arguments": arguments}}
            process.stdin.write(json.dumps(request, separators=(",", ":")) + "\n")
            process.stdin.flush()
            response = json.loads(process.stdout.readline())
            if response.get("error") is not None:
                raise AssertionError(f"MCP {name} failed: {response}")
            return response["result"]["content"][0]["text"]

        tool(1, "project_scan", {"root": str(root)})
        return tool(2, "diagnostics", {"languageId": "java"})
    finally:
        process.terminate()
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            process.kill(); process.wait(timeout=20)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime", default="modules/refactorkit-cli/build/package/refactorkit")
    options = parser.parse_args()
    runtime = (Path.cwd() / options.runtime).resolve()
    suffix = ".bat" if os.name == "nt" else ""
    cli = runtime / "bin" / f"refactorkit{suffix}"
    daemon = runtime / "bin" / f"refactorkit-daemon{suffix}"
    mcp = runtime / "bin" / f"refactorkit-mcp{suffix}"
    platform = runtime / "runtime/lib/ct.sym"
    if not platform.is_file():
        raise AssertionError(f"packaged Java platform signatures are missing: {platform}")

    with tempfile.TemporaryDirectory(prefix="refactorkit-java-diagnostics-") as temporary:
        root = Path(temporary) / "reactor"
        write_reactor(root, 21)
        clean_hash = tree_hash(root)
        clean = run_cli(cli, root)
        if clean.returncode != 0 or clean.stdout.strip() != "No diagnostics.":
            raise AssertionError(f"packaged release-21 CLI diagnostics failed: {clean.stdout}\n{clean.stderr}")
        daemon_clean = daemon_diagnostics(daemon, root)
        if daemon_clean:
            raise AssertionError(f"packaged release-21 daemon diagnostics failed: {daemon_clean}")
        mcp_clean = mcp_diagnostics(mcp, root)
        if mcp_clean.strip() != "No diagnostics.":
            raise AssertionError(f"packaged release-21 MCP diagnostics failed: {mcp_clean}")
        if tree_hash(root) != clean_hash or (root / ".refactorkit").exists():
            raise AssertionError("read-only packaged diagnostics modified the reactor")

        shutil.rmtree(root)
        write_reactor(root, 8)
        historical_hash = tree_hash(root)
        historical = run_cli(cli, root)
        output = historical.stdout + historical.stderr
        if historical.returncode == 0 or "HttpClient" not in output or "isBlank" not in output:
            raise AssertionError(f"packaged release-8 API boundary was not enforced: {output}")
        api_only = run_cli(cli, root, "api")
        if api_only.returncode != 0 or api_only.stdout.strip() != "No diagnostics.":
            raise AssertionError(f"module filter narrowed authority instead of filtering results: {api_only.stdout}")
        if daemon_diagnostics(daemon, root, "api"):
            raise AssertionError("daemon module filter returned diagnostics for the clean API module")
        if "ReactorApi cannot be resolved" in output or "Connection cannot be resolved" in output:
            raise AssertionError(f"release-8 diagnostics fabricated reactor/platform failures: {output}")
        if tree_hash(root) != historical_hash or (root / ".refactorkit").exists():
            raise AssertionError("historical packaged diagnostics modified the reactor")

    print("Packaged authoritative Java diagnostics acceptance passed: release-21 platform/reactor closure and release-8 API boundary.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
