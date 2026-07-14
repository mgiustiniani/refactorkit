#!/usr/bin/env python3
"""Native packaged acceptance against the explicit real Kotlin 2.0.21 compiler."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def command_for(cli: Path, args: list[str]) -> list[str]:
    if os.name == "nt":
        return [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/s", "/c", str(cli), *args]
    return [str(cli), *args]


def artifact(cache: Path, group: str, name: str, version: str) -> Path:
    root = cache / group / name / version
    matches = sorted(root.glob(f"*/{name}-{version}.jar"))
    if not matches:
        raise AssertionError(f"cached artifact is missing: {group}:{name}:{version} below {root}")
    by_digest: dict[str, list[Path]] = {}
    for match in matches:
        by_digest.setdefault(hashlib.sha256(match.read_bytes()).hexdigest(), []).append(match)
    if len(by_digest) != 1:
        raise AssertionError(
            f"cached artifact has conflicting content: {group}:{name}:{version}; "
            f"digests={sorted(by_digest)}"
        )
    return matches[0].resolve()


def tree_hash(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(item for item in root.rglob("*") if item.is_file()):
        digest.update(path.relative_to(root).as_posix().encode())
        digest.update(path.read_bytes())
    return digest.hexdigest()


def run(
    cli: Path,
    workspace: Path,
    jdk: Path,
    compiler: Path,
    classpath: list[Path],
    request: str,
    operation: str = "diagnostics",
    extra: list[str] | None = None,
) -> dict:
    result = subprocess.run(
        command_for(cli, [
            "kotlin", operation, str(workspace),
            "--jdk-home", str(jdk),
            "--compiler-jar", str(compiler),
            "--compiler-classpath", os.pathsep.join(map(str, classpath)),
            "--request-id", request,
            *(extra or []),
        ]),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=60,
    )
    if result.returncode != 0:
        raise AssertionError(f"Kotlin diagnostics exited {result.returncode}\nstdout={result.stdout}\nstderr={result.stderr}")
    return json.loads(result.stdout)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime", default="modules/refactorkit-cli/build/package/refactorkit")
    parser.add_argument("--jdk-home", default=os.environ.get("JAVA_HOME"))
    options = parser.parse_args()
    repository = Path.cwd()
    runtime = (repository / options.runtime).resolve()
    cli = runtime / "bin" / ("refactorkit.bat" if os.name == "nt" else "refactorkit")
    if not options.jdk_home:
        raise AssertionError("JAVA_HOME or --jdk-home is required")
    jdk = Path(options.jdk_home).resolve()
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches" / "modules-2" / "files-2.1"
    compiler = artifact(cache, "org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.0.21")
    classpath = [
        artifact(cache, "org.jetbrains.kotlin", "kotlin-stdlib", "2.0.21"),
        artifact(cache, "org.jetbrains.kotlin", "kotlin-script-runtime", "2.0.21"),
        artifact(cache, "org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        artifact(cache, "org.jetbrains.kotlin", "kotlin-daemon-embeddable", "2.0.21"),
        artifact(cache, "org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
        artifact(cache, "org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.6.4"),
    ]
    for required in [cli, jdk / "release", compiler, *classpath]:
        if not required.exists():
            raise AssertionError(f"required Kotlin qualification input is missing: {required}")

    with tempfile.TemporaryDirectory(prefix="refactorkit-kotlin-qualification-") as temporary:
        workspace = Path(temporary) / "workspace"
        shutil.copytree(repository / "samples" / "kotlin-maven-simple", workspace)
        before = tree_hash(workspace)
        clean = run(cli, workspace, jdk, compiler, classpath, "native-kotlin-clean")
        if clean.get("status") != "ready" or clean.get("diagnostics") != []:
            raise AssertionError(f"clean Kotlin diagnostics failed: {clean}")
        if clean.get("backend") != "kotlin-compiler-diagnostics-k2-v1" or not clean.get("process"):
            raise AssertionError(f"Kotlin compiler/process attestation is missing: {clean}")
        if tree_hash(workspace) != before:
            raise AssertionError("clean Kotlin diagnostics modified workspace sources")

        symbols = run(
            cli, workspace, jdk, compiler, classpath, "native-kotlin-symbols", "symbols",
            ["--file", "src/main/kotlin/org/refactorkit/samples/Greeting.kt"],
        )
        symbol_rows = symbols.get("symbols", [])
        if symbols.get("status") != "ready" or symbols.get("backend") != "kotlin-compiler-jvm-types-k2-v1":
            raise AssertionError(f"Kotlin compiler symbols failed: {symbols}")
        greeting = next((item for item in symbol_rows if item.get("name") == "Greeting"), None)
        if not greeting or not greeting.get("id", "").startswith("kotlin-jvm-type-v1:"):
            raise AssertionError(f"Kotlin JVM type identity is missing: {symbols}")
        if greeting.get("startLine") != 2 or greeting.get("startCharacter") != 6 or greeting.get("endCharacter") != 14:
            raise AssertionError(f"Kotlin compiler PSI range is not exact UTF-16: {greeting}")
        definition = run(
            cli, workspace, jdk, compiler, classpath, "native-kotlin-definition", "definition",
            ["--symbol", greeting["id"]],
        )
        if definition.get("status") != "ready" or definition.get("symbols") != [greeting]:
            raise AssertionError(f"Kotlin opaque definition lookup failed: {definition}")
        if tree_hash(workspace) != before:
            raise AssertionError("Kotlin symbol reads modified workspace sources")

        source = workspace / "src" / "main" / "kotlin" / "org" / "refactorkit" / "samples" / "Greeting.kt"
        source.write_text("package org.refactorkit.samples\n\nclass Greeting(val missing: MissingType)\n", encoding="utf-8")
        broken_before = tree_hash(workspace)
        broken = run(cli, workspace, jdk, compiler, classpath, "native-kotlin-broken")
        diagnostics = broken.get("diagnostics", [])
        if broken.get("status") != "ready" or not any("unresolved" in item.get("message", "").lower() for item in diagnostics):
            raise AssertionError(f"real Kotlin compiler error was not preserved: {broken}")
        if not all(item.get("locationPrecision") in {"line-only", "none"} for item in diagnostics):
            raise AssertionError(f"Kotlin diagnostics fabricated exact ranges: {diagnostics}")
        if tree_hash(workspace) != broken_before:
            raise AssertionError("broken Kotlin diagnostics modified workspace sources")

    print("Packaged Kotlin acceptance passed: K2 diagnostics, durable JVM type symbols, exact definitions and immutable sources.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as failure:
        message = str(failure).replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::error title=Packaged Kotlin qualification failed::{message}", flush=True)
        raise
