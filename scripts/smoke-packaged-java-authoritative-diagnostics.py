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
import xml.sax.saxutils


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
        "import com.sun.net.httpserver.HttpServer;\n"
        "public final class RepositoryAdapter {\n"
        "  ReactorApi api; Connection connection; HttpClient client = HttpClient.newHttpClient(); HttpServer server;\n"
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


def write_release_matrix(root: Path) -> None:
    root.mkdir(parents=True)
    modules = "".join(f"<module>release-{release}</module>" for release in range(8, 26))
    (root / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>"
        "<artifactId>release-matrix</artifactId><version>1</version><packaging>pom</packaging>"
        f"<modules>{modules}</modules></project>\n",
        encoding="utf-8",
    )
    for release in range(8, 26):
        module = root / f"release-{release}"
        source = module / f"src/main/java/fixture/release{release}/ReleaseApi.java"
        source.parent.mkdir(parents=True)
        source.write_text(
            f"package fixture.release{release}; final class ReleaseApi {{ Object value; String release() {{ return \"{release}\"; }} }}\n",
            encoding="utf-8",
        )
        (module / "pom.xml").write_text(
            "<project><modelVersion>4.0.0</modelVersion><parent><groupId>fixture</groupId>"
            "<artifactId>release-matrix</artifactId><version>1</version></parent>"
            f"<artifactId>release-{release}</artifactId><properties>"
            f"<maven.compiler.release>{release}</maven.compiler.release>"
            "</properties></project>\n",
            encoding="utf-8",
        )


def compile_jar(jdk_home: Path, jar_path: Path, source_path: str, content: str) -> None:
    with tempfile.TemporaryDirectory(prefix="refactorkit-java-fixture-compile-") as temporary:
        source = Path(temporary) / "src" / source_path
        classes = Path(temporary) / "classes"
        source.parent.mkdir(parents=True)
        classes.mkdir()
        source.write_text(content, encoding="utf-8")
        executable_suffix = ".exe" if os.name == "nt" else ""
        subprocess.run(
            [str(jdk_home / "bin" / f"javac{executable_suffix}"), "--release", "8", "-d", str(classes), str(source)],
            check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
        jar_path.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(
            [str(jdk_home / "bin" / f"jar{executable_suffix}"), "--create", "--file", str(jar_path), "-C", str(classes), "."],
            check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )


def install_artifact(
    repository: Path,
    jdk_home: Path,
    group: str,
    artifact: str,
    version: str,
    source_path: str,
    source: str,
    dependencies: str = "",
    classifier: str | None = None,
) -> Path:
    directory = repository.joinpath(*group.split("."), artifact, version)
    suffix = f"-{classifier}" if classifier else ""
    jar = directory / f"{artifact}-{version}{suffix}.jar"
    compile_jar(jdk_home, jar, source_path, source)
    pom = directory / f"{artifact}-{version}.pom"
    if not pom.exists():
        pom.write_text(
            f"<project><modelVersion>4.0.0</modelVersion><groupId>{group}</groupId>"
            f"<artifactId>{artifact}</artifactId><version>{version}</version>"
            f"<dependencies>{dependencies}</dependencies></project>\n",
            encoding="utf-8",
        )
    return jar


def write_variant_scope_fixture(root: Path, user_home: Path, jdk_home: Path) -> None:
    repository = user_home / ".m2/repository"
    normal = install_artifact(
        repository, jdk_home, "fixture.variant", "variant-api", "1",
        "fixture/variant/NormalOnly.java", "package fixture.variant; public @interface NormalOnly {}\n",
    )
    linux = install_artifact(
        repository, jdk_home, "fixture.variant", "variant-api", "2",
        "fixture/variant/LinuxOnly.java", "package fixture.variant; public @interface LinuxOnly {}\n",
        classifier="linux",
    )
    variant_parent = install_artifact(
        repository, jdk_home, "fixture.variant", "variant-parent", "1",
        "fixture/variant/VariantParent.java", "package fixture.variant; public final class VariantParent {}\n",
        dependencies=(
            "<dependency><groupId>fixture.variant</groupId><artifactId>variant-api</artifactId><version>99</version></dependency>"
            "<dependency><groupId>fixture.variant</groupId><artifactId>variant-api</artifactId><version>99</version><classifier>linux</classifier></dependency>"
        ),
    )
    for artifact, class_name in (
        ("provided-compile", "ProvidedCompile"),
        ("provided-runtime", "ProvidedRuntime"),
        ("test-compile", "TestCompile"),
        ("test-runtime", "TestRuntime"),
    ):
        install_artifact(
            repository, jdk_home, "fixture.scope", artifact, "1",
            f"fixture/scope/{class_name}.java", f"package fixture.scope; public final class {class_name} {{}}\n",
        )
    provided_parent = install_artifact(
        repository, jdk_home, "fixture.scope", "provided-parent", "1",
        "fixture/scope/ProvidedParent.java", "package fixture.scope; public final class ProvidedParent {}\n",
        dependencies=(
            "<dependency><groupId>fixture.scope</groupId><artifactId>provided-compile</artifactId><version>1</version></dependency>"
            "<dependency><groupId>fixture.scope</groupId><artifactId>provided-runtime</artifactId><version>1</version><scope>runtime</scope></dependency>"
        ),
    )
    test_parent = install_artifact(
        repository, jdk_home, "fixture.scope", "test-parent", "1",
        "fixture/scope/TestParent.java", "package fixture.scope; public final class TestParent {}\n",
        dependencies=(
            "<dependency><groupId>fixture.scope</groupId><artifactId>test-compile</artifactId><version>1</version></dependency>"
            "<dependency><groupId>fixture.scope</groupId><artifactId>test-runtime</artifactId><version>1</version><scope>runtime</scope></dependency>"
        ),
    )
    test_fixtures = install_artifact(
        repository, jdk_home, "fixture.variant", "test-fixtures", "1",
        "fixture/variant/FixtureOnly.java", "package fixture.variant; public @interface FixtureOnly {}\n",
        classifier="tests",
    )
    root.mkdir(parents=True)
    system_jar = root / "system-libs/system-api.jar"
    compile_jar(
        jdk_home, system_jar, "fixture/system/SystemOnly.java",
        "package fixture.system; public @interface SystemOnly {}\n",
    )
    escaped_system = xml.sax.saxutils.escape(str(system_jar))
    (root / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>"
        "<artifactId>variant-scope</artifactId><version>1</version>"
        "<properties><maven.compiler.release>21</maven.compiler.release></properties>"
        "<dependencyManagement><dependencies>"
        "<dependency><groupId>fixture.variant</groupId><artifactId>variant-api</artifactId><version>1</version></dependency>"
        "<dependency><groupId>fixture.variant</groupId><artifactId>variant-api</artifactId><version>2</version><classifier>linux</classifier></dependency>"
        "</dependencies></dependencyManagement><dependencies>"
        "<dependency><groupId>fixture.variant</groupId><artifactId>variant-parent</artifactId><version>1</version></dependency>"
        "<dependency><groupId>fixture.scope</groupId><artifactId>provided-parent</artifactId><version>1</version><scope>provided</scope></dependency>"
        "<dependency><groupId>fixture.scope</groupId><artifactId>test-parent</artifactId><version>1</version><scope>test</scope></dependency>"
        "<dependency><groupId>fixture.variant</groupId><artifactId>test-fixtures</artifactId><version>1</version><type>test-jar</type><scope>test</scope></dependency>"
        f"<dependency><groupId>fixture.system</groupId><artifactId>system-api</artifactId><version>1</version><scope>system</scope><systemPath>{escaped_system}</systemPath></dependency>"
        "</dependencies></project>\n",
        encoding="utf-8",
    )
    main = root / "src/main/java/fixture/VariantScopeMain.java"
    main.parent.mkdir(parents=True)
    main.write_text(
        "package fixture; import fixture.scope.*; import fixture.system.SystemOnly; import fixture.variant.*; "
        "@NormalOnly @LinuxOnly @SystemOnly final class VariantScopeMain { "
        "VariantParent variant; ProvidedParent parent; ProvidedCompile compile; ProvidedRuntime runtime; }\n",
        encoding="utf-8",
    )
    test = root / "src/test/java/fixture/VariantScopeTest.java"
    test.parent.mkdir(parents=True)
    test.write_text(
        "package fixture; import fixture.scope.*; import fixture.variant.FixtureOnly; "
        "@FixtureOnly final class VariantScopeTest { TestParent parent; TestCompile compile; TestRuntime runtime; }\n",
        encoding="utf-8",
    )
    if not all(path.is_file() for path in (normal, linux, variant_parent, provided_parent, test_parent, test_fixtures)):
        raise AssertionError("variant/scope fixture repository is incomplete")


def write_availability_fixture(root: Path) -> None:
    root.mkdir(parents=True)
    (root / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>"
        "<artifactId>availability</artifactId><version>1</version>"
        "<properties><maven.compiler.release>21</maven.compiler.release></properties>"
        "<dependencies><dependency><groupId>fixture.missing</groupId><artifactId>test-api</artifactId>"
        "<version>1</version><scope>test</scope></dependency></dependencies></project>\n",
        encoding="utf-8",
    )
    main = root / "src/main/java/fixture/MainFailure.java"
    main.parent.mkdir(parents=True)
    main.write_text("package fixture; class MainFailure { MissingMain value; }\n", encoding="utf-8")
    test = root / "src/test/java/fixture/TestUnavailable.java"
    test.parent.mkdir(parents=True)
    test.write_text(
        "package fixture; import fixture.missing.TestApi; class TestUnavailable { TestApi value; }\n",
        encoding="utf-8",
    )


def runtime_environment(user_home: Path | None = None) -> dict[str, str]:
    environment = os.environ.copy()
    environment.pop("JAVA_HOME", None)
    if user_home:
        existing = environment.get("JAVA_TOOL_OPTIONS", "").strip()
        user_home_option = f'"-Duser.home={user_home}"'
        environment["JAVA_TOOL_OPTIONS"] = f"{existing} {user_home_option}".strip()
    return environment


def run_cli(
    cli: Path,
    root: Path,
    module: str | None = None,
    jdk_home: Path | None = None,
    user_home: Path | None = None,
) -> subprocess.CompletedProcess[str]:
    environment = runtime_environment(user_home)
    return subprocess.run(
        command_for(cli, [
            "diagnostics", str(root),
            *(["--module", module] if module else []),
            *(["--jdk-home", str(jdk_home)] if jdk_home else []),
        ]),
        text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        env=environment, timeout=120,
    )


def daemon_diagnostics(
    daemon: Path,
    root: Path,
    module: str | None = None,
    jdk_home: Path | None = None,
    user_home: Path | None = None,
) -> list[dict]:
    environment = runtime_environment(user_home)
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
        return exchange(2, "diagnostics", {
            "languageId": "java",
            **({"module": module} if module else {}),
            **({"jdkHome": str(jdk_home)} if jdk_home else {}),
        })
    finally:
        process.terminate()
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            process.kill(); process.wait(timeout=20)


def mcp_diagnostics(
    mcp: Path,
    root: Path,
    jdk_home: Path | None = None,
    user_home: Path | None = None,
) -> str:
    environment = runtime_environment(user_home)
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
        return tool(2, "diagnostics", {
            "languageId": "java",
            **({"jdkHome": str(jdk_home)} if jdk_home else {}),
        })
    finally:
        process.terminate()
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            process.kill(); process.wait(timeout=20)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime", default="modules/refactorkit-cli/build/package/refactorkit")
    parser.add_argument("--jdk-home", default=os.environ.get("REFACTORKIT_JAVA_25_HOME"))
    options = parser.parse_args()
    runtime = (Path.cwd() / options.runtime).resolve()
    suffix = ".bat" if os.name == "nt" else ""
    cli = runtime / "bin" / f"refactorkit{suffix}"
    daemon = runtime / "bin" / f"refactorkit-daemon{suffix}"
    mcp = runtime / "bin" / f"refactorkit-mcp{suffix}"
    platform = runtime / "runtime/lib/ct.sym"
    if not platform.is_file():
        raise AssertionError(f"packaged Java platform signatures are missing: {platform}")
    if not options.jdk_home:
        raise AssertionError("--jdk-home or REFACTORKIT_JAVA_25_HOME is required for the Java 8-through-25 matrix")
    jdk_home = Path(options.jdk_home).resolve()
    release_metadata = jdk_home / "release"
    if not release_metadata.is_file() or 'JAVA_VERSION="25' not in release_metadata.read_text(encoding="utf-8"):
        raise AssertionError(f"the release-matrix input is not an explicit JDK 25 home: {jdk_home}")

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

        matrix = Path(temporary) / "release-matrix"
        write_release_matrix(matrix)
        matrix_hash = tree_hash(matrix)
        matrix_cli = run_cli(cli, matrix, jdk_home=jdk_home)
        if matrix_cli.returncode != 0 or matrix_cli.stdout.strip() != "No diagnostics.":
            raise AssertionError(f"packaged Java 8-through-25 CLI matrix failed: {matrix_cli.stdout}\n{matrix_cli.stderr}")
        matrix_daemon = daemon_diagnostics(daemon, matrix, jdk_home=jdk_home)
        if matrix_daemon:
            raise AssertionError(f"packaged Java 8-through-25 daemon matrix failed: {matrix_daemon}")
        matrix_mcp = mcp_diagnostics(mcp, matrix, jdk_home=jdk_home)
        if matrix_mcp.strip() != "No diagnostics.":
            raise AssertionError(f"packaged Java 8-through-25 MCP matrix failed: {matrix_mcp}")
        if tree_hash(matrix) != matrix_hash or (matrix / ".refactorkit").exists():
            raise AssertionError("Java 8-through-25 packaged diagnostics modified the matrix workspace")

        variant_scope = Path(temporary) / "variant-scope"
        maven_home = Path(temporary) / "maven-home"
        write_variant_scope_fixture(variant_scope, maven_home, jdk_home)
        variant_scope_hash = tree_hash(variant_scope)
        variant_cli = run_cli(cli, variant_scope, jdk_home=jdk_home, user_home=maven_home)
        if variant_cli.returncode != 0 or variant_cli.stdout.strip() != "No diagnostics.":
            raise AssertionError(f"packaged Maven variant/scope CLI row failed: {variant_cli.stdout}\n{variant_cli.stderr}")
        variant_daemon = daemon_diagnostics(daemon, variant_scope, jdk_home=jdk_home, user_home=maven_home)
        if variant_daemon:
            raise AssertionError(f"packaged Maven variant/scope daemon row failed: {variant_daemon}")
        variant_mcp = mcp_diagnostics(mcp, variant_scope, jdk_home=jdk_home, user_home=maven_home)
        if variant_mcp.strip() != "No diagnostics.":
            raise AssertionError(f"packaged Maven variant/scope MCP row failed: {variant_mcp}")
        if tree_hash(variant_scope) != variant_scope_hash or (variant_scope / ".refactorkit").exists():
            raise AssertionError("packaged Maven variant/scope diagnostics modified the workspace")

        unavailable = Path(temporary) / "availability"
        write_availability_fixture(unavailable)
        unavailable_hash = tree_hash(unavailable)
        unavailable_cli = run_cli(cli, unavailable)
        unavailable_output = unavailable_cli.stdout + unavailable_cli.stderr
        if (unavailable_cli.returncode == 0 or unavailable_output.count("classpath.unavailable") != 1 or
                "MissingMain" not in unavailable_output or "TestApi" in unavailable_output):
            raise AssertionError(f"packaged source-set availability CLI failed: {unavailable_output}")
        unavailable_daemon = daemon_diagnostics(daemon, unavailable)
        if ([row.get("code") for row in unavailable_daemon].count("classpath.unavailable") != 1 or
                not any("MissingMain" in row.get("message", "") for row in unavailable_daemon) or
                any("TestApi" in row.get("message", "") for row in unavailable_daemon)):
            raise AssertionError(f"packaged source-set availability daemon failed: {unavailable_daemon}")
        unavailable_mcp = mcp_diagnostics(mcp, unavailable)
        if "MissingMain" not in unavailable_mcp or "TestApi" in unavailable_mcp:
            raise AssertionError(f"packaged source-set availability MCP failed: {unavailable_mcp}")
        if tree_hash(unavailable) != unavailable_hash or (unavailable / ".refactorkit").exists():
            raise AssertionError("source-set availability diagnostics modified the workspace")

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

    print("Packaged authoritative Java diagnostics acceptance passed: exact releases 8 through 25, Maven variant/scope derivation, release-21 reactor closure, source-set availability, and release-8 API boundary.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
