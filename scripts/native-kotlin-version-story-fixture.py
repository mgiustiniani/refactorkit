#!/usr/bin/env python3
"""Exercise native Kotlin receipt finalizers with controlled package-version fixtures."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from tempfile import TemporaryDirectory
from unittest.mock import patch
import xml.etree.ElementTree as ET

SCRIPTS = Path(__file__).resolve().parent
ROOT = SCRIPTS.parent
JOBS = {
    "k1-k2-shared-foundations-native": ("k1-k2-shared-foundations", "k1-k2-shared-foundations"),
    "k5-move-next-native": ("k5-move-next", "k5-move-next"),
    "k5-bounded-completion-native": ("k5-completion", "k5-bounded-completion"),
}


def load_finalizer(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / f"finalize-native-{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def git(repository, *args):
    return subprocess.check_output(["git", "-C", str(repository), *args], text=True).strip()


def qualify(root, module, scope, version, run_options, *, refusal=None):
    repository = root / "source"
    repository.mkdir(parents=True)
    subject = set(getattr(module, "SUBJECT_FILES", set()))
    if hasattr(module, "REQUIREMENT"):
        subject.update((module.REQUIREMENT, module.REQUIREMENT.with_suffix(module.REQUIREMENT.suffix + ".sha256")))
    subject.update(getattr(module, "BOUND_REQUIREMENTS", {}))
    projection = Path("docs/releases/v0.7.0-kotlin-support.json")
    subject.add(projection)
    for path in subject:
        destination = repository / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / path, destination)
    (repository / "build.gradle.kts").write_text(f'allprojects {{\n    version = "{version}"\n}}\n')
    git(repository, "init", "-q")
    git(repository, "add", ".")
    git(repository, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "Qualification fixture")
    git(repository, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "--allow-empty", "-qm", "Qualification subject")
    revision = git(repository, "rev-parse", "HEAD")
    package = root / "package"
    executable = "java.exe" if os.name == "nt" else "java"
    launcher_suffix = ".bat" if os.name == "nt" else ""
    for path in (package/f"runtime/bin/{executable}", root/f"jdk/bin/{executable}",
                 *(package/f"bin/{name}{launcher_suffix}" for name in ("refactorkit", "refactorkit-daemon", "refactorkit-mcp"))):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"version selection fixture\n")
    library = package / "lib"
    library.mkdir()
    old_names = sorted(module.REQUIRED_JARS)
    names = [name.replace("-0.7.0.jar", f"-{version}.jar") for name in old_names]
    mixed = version == "0.8.0" and refusal is not None
    for name in names + (old_names[:1] if mixed else []):
        (library / name).write_text(name)
    reports = root / "modules"
    directory = reports / "fixture/build/test-results/test"
    directory.mkdir(parents=True)
    suites = module.REQUIRED_SUITES
    for index, suite in enumerate(sorted(suites)):
        case_names = sorted(suites[suite]) if isinstance(suites, dict) else ["fixture case"]
        entry = ET.Element("testsuite", name=suite, tests=str(len(case_names)), skipped="0", failures="0", errors="0")
        for case_name in case_names:
            ET.SubElement(entry, "testcase", name=case_name, classname=suite)
        ET.ElementTree(entry).write(directory / f"TEST-{index}.xml")
    smoke = root / "smoke.log"
    smoke.write_text(module.SMOKE_MARKER + "\n")
    result = root / "result.json"
    argv = [
        str(module.__file__), "--platform", module.detected_platform(), "--revision", revision,
        "--repository-root", str(repository), "--package-root", str(package), "--report-root", str(reports),
        "--smoke-log", str(smoke), "--output", str(result), *run_options,
    ]
    if scope == "k1-k2-shared-foundations":
        support = root / "support.json"
        support.write_text(json.dumps({"status":"PASSED", "failures":[],
                                      "supportProjectionSha256":hashlib.sha256((repository/projection).read_bytes()).hexdigest()}))
        argv.extend(("--support-receipt", str(support)))
    if hasattr(module, "COMPATIBILITY_SMOKE_MARKER"):
        compatibility = root / "compatibility-smoke.log"
        compatibility.write_text(module.COMPATIBILITY_SMOKE_MARKER + "\n")
        argv.extend(("--compatibility-smoke-log", str(compatibility)))

    run_command = subprocess.run
    fixture_java = {(package/f"runtime/bin/{executable}").resolve(), (root/f"jdk/bin/{executable}").resolve()}

    def report_jdk(command, *args, **kwargs):
        if command[1:] == ["-version"] and Path(command[0]).resolve() in fixture_java:
            return subprocess.CompletedProcess(command, 0, "", 'openjdk version "21.0.11"\nOpenJDK (build 21.0.11+10-LTS)\n')
        return run_command(command, *args, **kwargs)

    with patch.object(sys, "argv", argv), patch.dict(os.environ, JAVA_HOME=str(root/"jdk")), patch.object(subprocess, "run", report_jdk):
        if refusal is not None:
            try:
                module.main()
            except ValueError as failure:
                assert refusal in str(failure), failure
            else:
                raise AssertionError(f"The finalizer did not refuse: {refusal}")
            assert not result.exists()
            return
        assert module.main() == 0
    receipt = json.loads(result.read_text())
    assert receipt["revision"] == revision and receipt["scope"] == f"v{version}-{scope}"
    assert receipt["packagedSubjectJarSha256"] == {
        name: hashlib.sha256((library/name).read_bytes()).hexdigest() for name in names
    }


def main():
    job, version_option = sys.argv[1:]
    script, scope = JOBS[job]
    module = load_finalizer(script)
    assert version_option in ("legacy", "--package-version-from-source")
    run_options = [] if version_option == "legacy" else [version_option]
    with TemporaryDirectory(prefix="rk-native-kotlin-version-story-") as temporary:
        root = Path(temporary)
        qualify(root/"current", module, scope, "0.8.0", run_options)
        qualify(root/"mixed", module, scope, "0.8.0", run_options, refusal="mixed-version packaged subject JARs")
        historical_refusal = ("bound K5 completion requirement is missing or drifted"
                              if hasattr(module, "HISTORICAL_BOUND_REQUIREMENTS") else None)
        qualify(root/"historical", module, scope, "0.7.0", [], refusal=historical_refusal)
    print(f"{job}: exact current receipt and mixed-version refusal verified with the historical guard")


if __name__ == "__main__":
    main()
