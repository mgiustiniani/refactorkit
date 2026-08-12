#!/usr/bin/env python3
"""Close one host's packaged Maven module-rename qualification evidence.

The finalizer is intentionally build/CI owned. It does not execute product behavior;
it reconciles and hash-binds the artifacts emitted by the dedicated Cucumber task.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import platform
import re
import subprocess
import sys
import traceback
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from typing import Any

REQUIREMENT_ID = "REQ-JAVA-MAVEN-MODULE-RENAME-PACKAGED-001"
MANIFEST_KIND = "packaged-maven-module-rename-host-evidence"
SCHEMA_VERSION = 1
SHA1 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
EXPECTED_APPLIED_HISTORY = ["PREPARED", "APPLYING", "APPLIED"]
EXPECTED_FINAL_HISTORY = EXPECTED_APPLIED_HISTORY + ["ROLLING_BACK", "ROLLED_BACK"]
CASE_SPECS = (
    ("packaged-cli", "packaged CLI", 5),
    ("packaged-daemon", "packaged daemon", 1),
    ("packaged-mcp", "packaged MCP", 1),
)
REQUIRED_GUARD_FLAGS = (
    "launcherBoundaryAlreadyStarted",
    "socketConnectDenied",
    "socketListenDenied",
    "socketAcceptDenied",
    "socketMulticastDenied",
    "networkDenied",
    "processExecDenied",
    "outsideWriteDenied",
    "readOnlyCandidateWriteDenied",
    "readOnlyCandidateReportsNotWritable",
    "installedRuntimeReadDenied",
    "symbolicAndHardLinksDenied",
    "expectedSecurityManagerWarningsObserved",
)
REQUIRED_READ_ONLY_FLAGS = (
    "allRegularFilesReadOnly",
    "allDirectoriesReadOnly",
    "executeBitsPreserved",
    "regularFileMutationDenied",
    "directoryMutationDenied",
)


def sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def canonical_json_sha256(value: Any) -> str:
    return sha256_bytes(canonical_json_bytes(value))


def write_json(path: pathlib.Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    os.replace(temporary, path)


def normalized_system() -> str:
    observed = platform.system()
    return {"Linux": "Linux", "Windows": "Windows", "Darwin": "macOS"}.get(observed, observed)


def normalized_architecture() -> str:
    observed = platform.machine().lower()
    if observed in {"amd64", "x86_64", "x64"}:
        return "x86-64"
    if observed in {"aarch64", "arm64"}:
        return "arm64"
    return observed


def platform_token(operating_system: str, architecture: str) -> str:
    expected = {
        ("Linux", "x86-64"): "linux-x86_64",
        ("Windows", "x86-64"): "windows-x86_64",
        ("macOS", "x86-64"): "macos-x86_64",
        ("macOS", "arm64"): "macos-aarch64",
    }
    return expected.get((operating_system, architecture), "UNSUPPORTED")


@dataclass(frozen=True)
class Layout:
    repository: pathlib.Path
    build: pathlib.Path

    @property
    def evidence(self) -> pathlib.Path:
        return self.build / "qualification" / "packaged-maven-module-rename"

    @property
    def manifests(self) -> pathlib.Path:
        return self.evidence / "manifests"

    @property
    def logs(self) -> pathlib.Path:
        return self.evidence / "logs"

    @property
    def diagnostics(self) -> pathlib.Path:
        return self.evidence / "failure-diagnostics"

    @property
    def verifier(self) -> pathlib.Path:
        return self.evidence / "verifier"

    @property
    def candidate_checksum(self) -> pathlib.Path:
        return self.evidence / "candidate" / "refactorkit-runtime.zip.sha256"

    @property
    def archive(self) -> pathlib.Path:
        return self.build / "distributions" / "refactorkit-runtime.zip"

    @property
    def cucumber(self) -> pathlib.Path:
        return self.build / "reports" / "cucumber" / "packaged-maven-module-rename-qualification.json"

    @property
    def junit_root(self) -> pathlib.Path:
        return self.build / "test-results" / "packagedMavenModuleRenameQualificationTest"

    @property
    def host_manifest(self) -> pathlib.Path:
        return self.manifests / "packaged-maven-module-rename-host-evidence-manifest.json"

    @property
    def junit_aggregate(self) -> pathlib.Path:
        return self.manifests / "packaged-maven-module-rename-junit-xml-aggregate.json"

    @property
    def diagnostics_aggregate(self) -> pathlib.Path:
        return self.diagnostics / "aggregate-failure-diagnostics.json"

    @property
    def focused_log(self) -> pathlib.Path:
        return self.logs / "packaged-maven-module-rename-qualification-focused.log"

    @property
    def test_outcome(self) -> pathlib.Path:
        return self.evidence / "run" / "test-task-outcome.properties"


class Finalizer:
    def __init__(self, layout: Layout, native_qualification: bool):
        self.layout = layout
        self.native_qualification = native_qualification
        self.missing: list[str] = []
        self.errors: list[str] = []

    def relative(self, path: pathlib.Path) -> str:
        resolved = path.resolve(strict=False)
        try:
            return resolved.relative_to(self.layout.repository).as_posix()
        except ValueError:
            self.error(f"evidence path escapes repository root: {resolved}")
            return resolved.as_posix()

    def error(self, message: str) -> None:
        if message not in self.errors:
            self.errors.append(message)

    def require(self, condition: bool, message: str) -> bool:
        if not condition:
            self.error(message)
        return condition

    def required_file(self, path: pathlib.Path) -> pathlib.Path | None:
        relative = self.relative(path)
        if path.is_symlink():
            self.error(f"required artifact is a symbolic link: {relative}")
            return None
        if not path.is_file():
            if relative not in self.missing:
                self.missing.append(relative)
            return None
        return path

    def artifact(self, path: pathlib.Path) -> dict[str, Any] | None:
        admitted = self.required_file(path)
        if admitted is None:
            return None
        return {
            "path": self.relative(admitted),
            "byteLength": admitted.stat().st_size,
            "sha256": sha256_file(admitted),
        }

    def read_json(self, path: pathlib.Path, required: bool = True) -> Any | None:
        admitted = self.required_file(path) if required else (path if path.is_file() and not path.is_symlink() else None)
        if admitted is None:
            return None
        try:
            return json.loads(admitted.read_text(encoding="utf-8"))
        except Exception as failure:  # noqa: BLE001 - evidence must report malformed inputs
            self.error(f"invalid JSON in {self.relative(path)}: {failure}")
            return None

    def read_properties(self, path: pathlib.Path, required: bool = True) -> dict[str, str] | None:
        admitted = self.required_file(path) if required else (path if path.is_file() and not path.is_symlink() else None)
        if admitted is None:
            return None
        values: dict[str, str] = {}
        try:
            for line in admitted.read_text(encoding="utf-8").splitlines():
                if not line or line.startswith("#"):
                    continue
                key, separator, value = line.partition("=")
                if not separator or not key:
                    raise ValueError(f"non-property line {line!r}")
                if key in values:
                    raise ValueError(f"duplicate property {key!r}")
                values[key] = value
            return values
        except Exception as failure:  # noqa: BLE001
            self.error(f"invalid properties in {self.relative(path)}: {failure}")
            return None

    def git_bytes(self, *arguments: str) -> bytes:
        environment = os.environ.copy()
        environment.update(
            {
                "GIT_OPTIONAL_LOCKS": "0",
                "GIT_CONFIG_NOSYSTEM": "1",
                "GIT_CONFIG_GLOBAL": "NUL" if os.name == "nt" else "/dev/null",
            }
        )
        result = subprocess.run(
            ["git", "-C", str(self.layout.repository), *arguments],
            check=False,
            capture_output=True,
            timeout=30,
            env=environment,
        )
        if result.returncode != 0:
            raise RuntimeError(
                f"git {' '.join(arguments)} failed ({result.returncode}): "
                f"{result.stderr.decode('utf-8', errors='replace')[:4096]}"
            )
        return result.stdout

    def revision(self) -> dict[str, Any]:
        try:
            top_level = pathlib.Path(self.git_bytes("rev-parse", "--show-toplevel").decode("utf-8").strip()).resolve()
            self.require(top_level == self.layout.repository, "configured repository root is not Git's top-level directory")
            commit = self.git_bytes("rev-parse", "--verify", "HEAD").decode("ascii").strip().lower()
            tree = self.git_bytes("rev-parse", "--verify", "HEAD^{tree}").decode("ascii").strip().lower()
            status = self.git_bytes("status", "--porcelain=v1", "-z", "--untracked-files=all")
        except Exception as failure:  # noqa: BLE001
            self.error(f"repository identity unavailable: {failure}")
            return {
                "commit": "UNAVAILABLE",
                "tree": "UNAVAILABLE",
                "worktreeState": "UNAVAILABLE",
                "statusSha256": "UNAVAILABLE",
                "statusEntryCount": -1,
                "githubSha": os.environ.get("GITHUB_SHA", "").strip() or "ABSENT",
                "githubShaPresent": bool(os.environ.get("GITHUB_SHA", "").strip()),
                "githubShaConsistent": False,
                "nativeQualification": self.native_qualification,
                "cleanExactCommitSatisfied": False,
            }

        self.require(bool(SHA1.fullmatch(commit)), f"repository HEAD is not a full lowercase commit: {commit}")
        self.require(bool(SHA1.fullmatch(tree)), f"repository tree is not a full lowercase identity: {tree}")
        github_sha = os.environ.get("GITHUB_SHA", "").strip().lower()
        github_present = bool(github_sha)
        github_consistent = github_present and bool(SHA1.fullmatch(github_sha)) and github_sha == commit
        if github_present:
            self.require(bool(SHA1.fullmatch(github_sha)), "GITHUB_SHA is not a full commit identity")
            self.require(github_sha == commit, "GITHUB_SHA does not equal the runtime-observed repository HEAD")
        clean = not status
        if self.native_qualification:
            self.require(github_present, "native qualification requires GITHUB_SHA")
            self.require(github_consistent, "native qualification requires GITHUB_SHA to equal HEAD")
            self.require(clean, "native qualification requires a clean exact candidate worktree")
        return {
            "commit": commit,
            "tree": tree,
            "worktreeState": "CLEAN" if clean else "DIRTY",
            "statusSha256": sha256_bytes(status),
            "statusEntryCount": status.count(b"\0"),
            "githubSha": github_sha if github_present else "ABSENT",
            "githubShaPresent": github_present,
            "githubShaConsistent": github_consistent if github_present else False,
            "nativeQualification": self.native_qualification,
            "cleanExactCommitSatisfied": (not self.native_qualification) or (clean and github_consistent),
        }

    def host(self) -> dict[str, Any]:
        operating_system = normalized_system()
        architecture = normalized_architecture()
        token = platform_token(operating_system, architecture)
        self.require(token != "UNSUPPORTED", f"host is outside the closed qualification matrix: {operating_system}/{architecture}")
        return {
            "operatingSystem": operating_system,
            "architecture": architecture,
            "platform": token,
            "runner": os.environ.get("RUNNER_NAME", "local-jdk21-runner"),
            "job": os.environ.get("GITHUB_JOB", "local-focused-red"),
            "run": os.environ.get("GITHUB_RUN_ID", "local-run"),
            "attempt": os.environ.get("GITHUB_RUN_ATTEMPT", "1"),
        }

    def input_artifact(
        self,
        path: pathlib.Path,
        expected_sha256: str | None = None,
    ) -> dict[str, Any] | None:
        artifact = self.artifact(path)
        if artifact is not None and expected_sha256 is not None:
            self.require(
                artifact["sha256"] == expected_sha256,
                f"{artifact['path']} SHA-256 differs from the qualification oracle",
            )
        return artifact

    def checksum_sidecar(self, path: pathlib.Path, subject: pathlib.Path) -> dict[str, Any]:
        artifact = self.artifact(path)
        result: dict[str, Any] = {"artifact": artifact}
        if artifact is None:
            return result
        try:
            text = path.read_text(encoding="ascii").rstrip("\r\n")
            fields = text.split()
            if len(fields) != 2:
                raise ValueError("expected exactly a digest and archive basename")
            declared = fields[0].lower()
            declared_name = pathlib.PurePosixPath(fields[1].lstrip("*").replace("\\", "/")).name
            self.require(bool(SHA256.fullmatch(declared)), f"non-canonical checksum digest in {self.relative(path)}")
            self.require(declared_name == subject.name, f"checksum basename does not name {subject.name}")
            actual = sha256_file(subject) if subject.is_file() else "UNAVAILABLE"
            self.require(declared == actual, "candidate checksum does not match the runtime ZIP")
            result.update(
                {
                    "declaredArchiveName": declared_name,
                    "declaredArchiveSha256": declared,
                    "matchesArchive": declared == actual,
                }
            )
        except Exception as failure:  # noqa: BLE001
            self.error(f"invalid candidate checksum sidecar: {failure}")
        return result

    def requirements(self) -> tuple[dict[str, Any], dict[str, Any] | None]:
        oracle_path = self.layout.repository / (
            "modules/refactorkit-cli/src/packagedMavenModuleRenameQualificationTest/resources/"
            "org/refactorkit/cli/packagedmavenmodulerename/qualification-oracle.json"
        )
        oracle = self.read_json(oracle_path)
        if not isinstance(oracle, dict):
            oracle = None
        feature = self.layout.repository / "features/java-maven-module-rename-packaged.feature"
        baseline = self.layout.repository / "docs/requirements/req-java-maven-module-rename-packaged-001-baseline.md"
        change = self.layout.repository / "docs/requirements/req-java-maven-module-rename-packaged-001-approved-change-001.md"
        expected = oracle or {}
        result = {
            "feature": self.input_artifact(feature, expected.get("featureSha256")),
            "baseline": self.input_artifact(baseline, expected.get("baselineSha256")),
            "approvedChange": self.input_artifact(change, expected.get("approvedChangeSha256")),
            "baselineChecksumFile": self.input_artifact(baseline.with_suffix(".sha256")),
            "approvedChangeChecksumFile": self.input_artifact(change.with_suffix(".sha256")),
            "oracle": self.input_artifact(oracle_path),
            "request": expected.get("request", "UNAVAILABLE"),
            "requestSha256": canonical_json_sha256(expected.get("request", "UNAVAILABLE")),
            "operation": expected.get("operation", "UNAVAILABLE"),
            "diagnosticsGate": expected.get("gate", "UNAVAILABLE"),
        }
        if oracle is not None:
            self.require(oracle.get("requirementId") == REQUIREMENT_ID, "oracle requirementId mismatch")
            self.require(isinstance(oracle.get("request"), dict), "oracle request is missing")
            for document, key in ((baseline, "baselineSha256"), (change, "approvedChangeSha256")):
                checksum_path = document.with_suffix(".sha256")
                if checksum_path.is_file():
                    try:
                        fields = checksum_path.read_text(encoding="ascii").split()
                        declared_path = fields[1].replace("\\", "/") if len(fields) == 2 else ""
                        self.require(
                            len(fields) == 2
                            and fields[0].lower() == oracle.get(key)
                            and declared_path == self.relative(document),
                            f"{self.relative(checksum_path)} does not bind the oracle's document hash",
                        )
                    except Exception as failure:  # noqa: BLE001
                        self.error(f"invalid requirement checksum file {self.relative(checksum_path)}: {failure}")
        return result, oracle

    def test_outcome_evidence(self) -> dict[str, Any]:
        artifact = self.artifact(self.layout.test_outcome)
        properties = self.read_properties(self.layout.test_outcome) if artifact is not None else None
        state = properties.get("state", "UNAVAILABLE") if properties else "UNAVAILABLE"
        self.require(state == "COMPLETED", f"qualification Test task did not complete successfully (state={state})")
        return {"artifact": artifact, "state": state}

    def verifier_evidence(self, host: dict[str, Any], archive_sha256: str) -> dict[str, Any]:
        log = self.layout.verifier / "runtime-archive-verifier-complete.log"
        status = self.layout.verifier / "runtime-archive-verifier-status.properties"
        log_artifact = self.artifact(log)
        status_artifact = self.artifact(status)
        present = log_artifact is not None and status_artifact is not None
        result: dict[str, Any] = {
            "status": "PRESENT" if present else "MISSING",
            "completeLog": log_artifact,
            "statusFile": status_artifact,
        }

        expected_keys = {
            "schemaVersion",
            "platform",
            "exitCode",
            "archiveSha256",
            "checksumSha256",
        }
        checksum_sha256 = (
            sha256_file(self.layout.candidate_checksum)
            if self.layout.candidate_checksum.is_file() and not self.layout.candidate_checksum.is_symlink()
            else "UNAVAILABLE"
        )

        properties = self.read_properties(status) if status_artifact is not None else None
        if properties is not None:
            result["result"] = properties
            self.require(set(properties) == expected_keys, "runtime archive verifier status has an inexact field set")
            self.require(properties.get("schemaVersion") == "1", "runtime archive verifier status schema is not 1")
            self.require(properties.get("exitCode") == "0", "runtime archive verifier did not exit successfully")
            self.require(properties.get("platform") == host["platform"], "verifier platform differs from the current host")
            self.require(properties.get("archiveSha256") == archive_sha256, "verifier archive hash differs from the bound ZIP")
            self.require(
                properties.get("checksumSha256") == checksum_sha256,
                "verifier checksum-file hash differs from the bound checksum",
            )

        if log_artifact is not None:
            try:
                lines = log.read_text(encoding="utf-8").splitlines()
                stdout_index = lines.index("===== stdout =====")
                stderr_index = lines.index("===== stderr =====")
                if stderr_index <= stdout_index:
                    raise ValueError("stderr section does not follow stdout")
                log_properties: dict[str, str] = {}
                for line in lines[:stdout_index]:
                    key, separator, value = line.partition("=")
                    if not separator or not key:
                        raise ValueError(f"non-property verifier-log header {line!r}")
                    if key in log_properties:
                        raise ValueError(f"duplicate verifier-log property {key!r}")
                    log_properties[key] = value
                expected_log_keys = expected_keys | {"archive", "checksum"}
                self.require(
                    set(log_properties) == expected_log_keys,
                    "runtime archive verifier complete log has an inexact header field set",
                )
                self.require(log_properties.get("schemaVersion") == "1", "runtime archive verifier log schema is not 1")
                self.require(log_properties.get("exitCode") == "0", "runtime archive verifier log records failure")
                self.require(log_properties.get("platform") == host["platform"], "verifier log platform differs from host")
                self.require(log_properties.get("archiveSha256") == archive_sha256, "verifier log archive hash differs")
                self.require(log_properties.get("checksumSha256") == checksum_sha256, "verifier log checksum hash differs")
                self.require(
                    log_properties.get("archive") == self.relative(self.layout.archive),
                    "verifier log archive path differs from the bound ZIP",
                )
                self.require(
                    log_properties.get("checksum") == self.relative(self.layout.candidate_checksum),
                    "verifier log checksum path differs from the bound checksum",
                )
            except Exception as failure:  # noqa: BLE001 - malformed verifier evidence must fail closed
                self.error(f"invalid runtime archive verifier complete log: {failure}")
        return result

    def junit_evidence(self) -> dict[str, Any]:
        xml_paths = []
        if self.layout.junit_root.is_dir():
            xml_paths = sorted(
                (path for path in self.layout.junit_root.rglob("*.xml") if path.is_file() and not path.is_symlink()),
                key=lambda path: self.relative(path),
            )
        if not xml_paths:
            self.required_file(self.layout.junit_root / "TEST-*.xml")
        files: list[dict[str, Any]] = []
        totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
        for path in xml_paths:
            artifact = self.artifact(path)
            if artifact is None:
                continue
            counts = {name: 0 for name in totals}
            root_name = "UNAVAILABLE"
            try:
                root = ET.parse(path).getroot()
                root_name = root.tag
                if root.tag == "testsuite":
                    sources = [root]
                elif root.tag == "testsuites" and all(name in root.attrib for name in totals):
                    sources = [root]
                else:
                    sources = list(root.findall(".//testsuite"))
                for source in sources:
                    for name in totals:
                        counts[name] += int(source.attrib.get(name, "0"))
            except Exception as failure:  # noqa: BLE001
                self.error(f"invalid JUnit XML {self.relative(path)}: {failure}")
            for name in totals:
                totals[name] += counts[name]
            files.append({**artifact, "rootElement": root_name, "counts": counts})
        aggregate_inputs = {"files": files, "totals": totals}
        aggregate = {
            "schemaVersion": 1,
            "kind": "junit-xml-deterministic-aggregate",
            **aggregate_inputs,
            "aggregateSha256": canonical_json_sha256(aggregate_inputs),
        }
        write_json(self.layout.junit_aggregate, aggregate)
        self.require(totals["tests"] == 3, f"JUnit aggregate expected 3 tests, observed {totals['tests']}")
        self.require(totals["failures"] == 0, f"JUnit aggregate contains {totals['failures']} failures")
        self.require(totals["errors"] == 0, f"JUnit aggregate contains {totals['errors']} errors")
        self.require(totals["skipped"] == 0, f"JUnit aggregate contains {totals['skipped']} skipped tests")
        return {
            "files": files,
            "deterministicAggregate": self.artifact(self.layout.junit_aggregate),
            "aggregateSha256": aggregate["aggregateSha256"],
            "totals": totals,
        }

    def cucumber_evidence(self) -> dict[str, Any]:
        artifact = self.artifact(self.layout.cucumber)
        report = self.read_json(self.layout.cucumber) if artifact is not None else None
        scenario_count = 0
        step_count = 0
        statuses: dict[str, int] = {}
        if isinstance(report, list):
            for feature in report:
                if not isinstance(feature, dict):
                    self.error("Cucumber JSON contains a non-object feature")
                    continue
                for scenario in feature.get("elements", []):
                    if not isinstance(scenario, dict):
                        self.error("Cucumber JSON contains a non-object scenario")
                        continue
                    scenario_count += 1
                    for step in scenario.get("steps", []):
                        step_count += 1
                        status = str((step.get("result") or {}).get("status", "missing"))
                        statuses[status] = statuses.get(status, 0) + 1
        elif report is not None:
            self.error("Cucumber JSON root is not an array")
        self.require(scenario_count == 3, f"Cucumber report expected 3 scenarios, observed {scenario_count}")
        self.require(step_count == 138, f"Cucumber report expected 138 steps, observed {step_count}")
        self.require(statuses == {"passed": 138}, f"Cucumber step statuses are not 138 passed: {statuses}")
        return {
            "artifact": artifact,
            "scenarioCount": scenario_count,
            "stepCount": step_count,
            "stepStatuses": dict(sorted(statuses.items())),
        }

    def global_manifests(self, revision: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any] | None]:
        names = (
            "candidate-archive-checksum.json",
            "candidate-extracted-no-follow-manifest.json",
            "fixture-m0-no-follow-manifest.json",
            "pairwise-disposable-copies.json",
        )
        artifacts = {name: self.artifact(self.layout.manifests / name) for name in names}
        candidate = self.read_json(self.layout.manifests / "candidate-extracted-no-follow-manifest.json")
        fixture = self.read_json(self.layout.manifests / "fixture-m0-no-follow-manifest.json")
        pairwise = self.read_json(self.layout.manifests / "pairwise-disposable-copies.json")
        archive_checksum = self.read_json(self.layout.manifests / "candidate-archive-checksum.json")
        if isinstance(candidate, dict):
            candidate_revision = candidate.get("revision", {})
            for key in ("commit", "tree", "worktreeState", "statusSha256"):
                self.require(
                    candidate_revision.get(key) == revision.get(key),
                    f"extracted-tree manifest revision {key} differs from finalizer observation",
                )
            if self.native_qualification:
                self.require(
                    candidate_revision.get("githubSha") == revision.get("githubSha"),
                    "extracted-tree manifest GITHUB_SHA differs from finalizer observation",
                )
                self.require(
                    candidate_revision.get("qualificationMode") == "NATIVE_CI",
                    "extracted-tree manifest is not marked as native CI evidence",
                )
                self.require(
                    candidate_revision.get("cleanExactCandidateSatisfied") is True,
                    "extracted-tree manifest did not satisfy clean exact candidate identity",
                )
            read_only = candidate.get("readOnly", {})
            for flag in REQUIRED_READ_ONLY_FLAGS:
                self.require(read_only.get(flag) is True, f"extracted-tree read-only attestation {flag} is not true")
            self.require(
                read_only.get("hardenedTreeSha256") == candidate.get("sha256"),
                "read-only hardened tree hash differs from extracted-tree manifest",
            )
        if isinstance(pairwise, dict):
            self.require(pairwise.get("count") == 3, "pairwise disposable-copy manifest does not contain all three cases")
            self.require(pairwise.get("pathsPairwiseDistinct") is True, "disposable-copy paths are not pairwise distinct")
            self.require(pairwise.get("identitiesPairwiseDistinct") is True, "disposable-copy identities are not pairwise distinct")
        if isinstance(archive_checksum, dict):
            self.require(
                archive_checksum.get("repositoryCommit") == revision.get("commit"),
                "candidate archive checksum manifest commit differs from finalizer observation",
            )
            self.require(
                archive_checksum.get("repositoryTree") == revision.get("tree"),
                "candidate archive checksum manifest tree differs from finalizer observation",
            )
        return {
            "artifacts": artifacts,
            "candidateExtracted": {
                "treeSha256": candidate.get("sha256", "UNAVAILABLE") if isinstance(candidate, dict) else "UNAVAILABLE",
                "entryCount": candidate.get("entryCount", -1) if isinstance(candidate, dict) else -1,
                "regularFileCount": candidate.get("regularFileCount", -1) if isinstance(candidate, dict) else -1,
                "directoryCount": candidate.get("directoryCount", -1) if isinstance(candidate, dict) else -1,
                "readOnlyAttestationSha256": canonical_json_sha256(candidate.get("readOnly", {}))
                if isinstance(candidate, dict)
                else "UNAVAILABLE",
            },
            "fixture": {
                "treeSha256": fixture.get("sha256", "UNAVAILABLE") if isinstance(fixture, dict) else "UNAVAILABLE",
                "entryCount": fixture.get("entryCount", -1) if isinstance(fixture, dict) else -1,
            },
            "pairwiseCopies": pairwise if isinstance(pairwise, dict) else "UNAVAILABLE",
            "archiveChecksumManifest": archive_checksum if isinstance(archive_checksum, dict) else "UNAVAILABLE",
        }, candidate if isinstance(candidate, dict) else None

    def case_evidence(
        self,
        revision: dict[str, Any],
        host: dict[str, Any],
        oracle: dict[str, Any] | None,
        candidate_manifest: dict[str, Any] | None,
        fixture_sha256: str,
    ) -> list[dict[str, Any]]:
        results: list[dict[str, Any]] = []
        candidate_entries = candidate_manifest.get("entries", {}) if candidate_manifest else {}
        for slug, surface, expected_processes in CASE_SPECS:
            path = self.layout.manifests / f"{slug}-case-manifest.json"
            artifact = self.artifact(path)
            document = self.read_json(path) if artifact is not None else None
            if not isinstance(document, dict):
                results.append({"surface": surface, "artifact": artifact, "status": "MISSING_OR_INVALID"})
                continue
            status = document.get("status", "UNAVAILABLE")
            self.require(document.get("requirementId") == REQUIREMENT_ID, f"{surface} case requirementId mismatch")
            self.require(document.get("surface") == surface, f"{surface} case surface mismatch")
            self.require(status == "PASSED", f"{surface} case status is {status}")
            case_revision = document.get("revision", {})
            for key in ("commit", "tree", "worktreeState", "statusSha256"):
                self.require(case_revision.get(key) == revision.get(key), f"{surface} revision {key} differs from host evidence")
            if revision.get("githubShaPresent"):
                self.require(case_revision.get("githubSha") == revision.get("githubSha"), f"{surface} GITHUB_SHA differs")
            if self.native_qualification:
                self.require(case_revision.get("githubShaPresent") is True, f"{surface} omitted native GITHUB_SHA")
                self.require(case_revision.get("qualificationMode") == "NATIVE_CI", f"{surface} is not native CI evidence")
                self.require(
                    case_revision.get("cleanExactCandidateSatisfied") is True,
                    f"{surface} did not satisfy clean exact candidate identity",
                )
            runner = document.get("nativeRunner", {})
            for key in ("operatingSystem", "architecture", "runner", "job", "run", "attempt"):
                self.require(runner.get(key) == host.get(key), f"{surface} native runner {key} differs from host evidence")

            requirement = document.get("requirement", {})
            if oracle:
                self.require(requirement.get("request") == oracle.get("request"), f"{surface} request differs from oracle")
                self.require(requirement.get("featureSha256") == oracle.get("featureSha256"), f"{surface} feature hash differs")
                self.require(requirement.get("canonicalOperation") == oracle.get("operation"), f"{surface} operation differs")

            transaction = document.get("transaction", {})
            schema = transaction.get("schemaVersion")
            normalized_forward = transaction.get("normalizedForwardEditSha256", "UNAVAILABLE")
            forward_oracle = transaction.get("forwardEditOracleSha256", "UNAVAILABLE")
            applied_history = transaction.get("appliedHistory", [])
            final_history = transaction.get("finalHistory", [])
            self.require(schema == 8, f"{surface} transaction schemaVersion is not 8")
            self.require(bool(SHA256.fullmatch(str(normalized_forward))), f"{surface} normalized forward-edit SHA is unavailable")
            self.require(normalized_forward == forward_oracle, f"{surface} forward-edit SHA differs from its oracle")
            self.require(
                normalized_forward == requirement.get("fiveEditOracleSha256"),
                f"{surface} forward-edit SHA differs from the five-edit requirement oracle",
            )
            self.require(applied_history == EXPECTED_APPLIED_HISTORY, f"{surface} APPLIED history is incomplete")
            self.require(final_history == EXPECTED_FINAL_HISTORY, f"{surface} ROLLED_BACK history is incomplete")
            self.require(transaction.get("gateIdentityVerified") is True, f"{surface} selector gate identity is unverified")

            package = document.get("package", {})
            read_only = package.get("readOnlyAttestation", {})
            for flag in REQUIRED_READ_ONLY_FLAGS:
                self.require(read_only.get(flag) is True, f"{surface} read-only attestation {flag} is not true")
            if candidate_manifest:
                self.require(
                    package.get("extractedTreeSha256") == candidate_manifest.get("sha256"),
                    f"{surface} extracted tree hash differs from global candidate manifest",
                )
            gate_selector = package.get("gateSelector", {})
            if oracle:
                self.require(gate_selector.get("publicOperation") == oracle.get("operation"), f"{surface} selector operation differs")
                self.require(gate_selector.get("diagnosticsGateId") == oracle.get("gate"), f"{surface} selector gate differs")
            for field in (
                "jvmJarSha256",
                "archiveEntrySha256",
                "selectorClassSha256",
                "javapSelectorMethodSha256",
                "operationToGateMappingProofSha256",
            ):
                self.require(bool(SHA256.fullmatch(str(gate_selector.get(field, "")))), f"{surface} selector proof {field} is absent")
            self.require(
                gate_selector.get("jvmJarSha256") == gate_selector.get("archiveEntrySha256"),
                f"{surface} extracted JVM JAR differs from its ZIP entry",
            )
            jvm_path = str(gate_selector.get("jvmJarPath", "UNAVAILABLE")).replace("\\", "/")
            jvm_entry = candidate_entries.get(jvm_path, {}) if isinstance(candidate_entries, dict) else {}
            self.require(
                jvm_entry.get("sha256") == gate_selector.get("jvmJarSha256"),
                f"{surface} JVM JAR hash differs from the extracted-tree manifest",
            )

            launcher_path = str(package.get("launcherPath", "UNAVAILABLE")).replace("\\", "/")
            embedded_path = str(package.get("embeddedJavaPath", "UNAVAILABLE")).replace("\\", "/")
            launcher_entry = candidate_entries.get(launcher_path, {}) if isinstance(candidate_entries, dict) else {}
            embedded_entry = candidate_entries.get(embedded_path, {}) if isinstance(candidate_entries, dict) else {}
            self.require(
                bool(re.fullmatch(r"0[0-7]{3}", str(package.get("launcherMode", "")))),
                f"{surface} launcher archive mode is unavailable",
            )
            self.require(
                bool(re.fullmatch(r"0[0-7]{3}", str(package.get("embeddedJavaMode", "")))),
                f"{surface} embedded Java archive mode is unavailable",
            )
            self.require(launcher_entry.get("sha256") == package.get("launcherSha256"), f"{surface} launcher hash differs from extracted tree")
            self.require(embedded_entry.get("sha256") == package.get("embeddedJavaSha256"), f"{surface} embedded Java hash differs from extracted tree")

            process = document.get("process", [])
            self.require(len(process) == expected_processes, f"{surface} process evidence count is {len(process)}, expected {expected_processes}")
            guard_values: list[dict[str, Any]] = []
            for index, process_evidence in enumerate(process):
                label = f"{surface} process {index + 1}"
                self.require(process_evidence.get("embeddedJavaObserved") is True, f"{label} did not observe embedded Java")
                self.require(process_evidence.get("guardProcessMatchedEmbeddedJava") is True, f"{label} guard process did not match embedded Java")
                self.require(process_evidence.get("networkObservationSucceeded") is True, f"{label} network observation failed")
                self.require(not process_evidence.get("deniedCommands", []), f"{label} requested a denied command")
                self.require(not process_evidence.get("unexpectedDescendantCommands", []), f"{label} spawned an unexpected descendant")
                guard = process_evidence.get("childSecurityGuard", {})
                guard_values.append(guard)
                self.require(guard.get("javaFeature") == 21, f"{label} guard did not run on Java 21")
                self.require(bool(SHA256.fullmatch(str(guard.get("agentSha256", "")))), f"{label} guard agent hash is absent")
                self.require(bool(SHA256.fullmatch(str(guard.get("attestationSha256", "")))), f"{label} guard attestation hash is absent")
                for flag in REQUIRED_GUARD_FLAGS:
                    self.require(guard.get(flag) is True, f"{label} guard attestation {flag} is not true")

            fixture = document.get("fixture", {})
            self.require(fixture.get("M0") == fixture_sha256, f"{surface} fixture M0 differs from the global fixture manifest")
            self.require(fixture.get("S0") == fixture_sha256, f"{surface} independent S0 differs from fixture M0")
            self.require(fixture.get("C1") == fixture.get("S1"), f"{surface} C1 differs from independent S1")

            results.append(
                {
                    "surface": surface,
                    "artifact": artifact,
                    "status": status,
                    "transaction": {
                        "schemaVersion": schema,
                        "id": transaction.get("id", "UNAVAILABLE"),
                        "normalizedForwardEditSha256": normalized_forward,
                        "forwardEditOracleSha256": forward_oracle,
                        "appliedHistory": applied_history,
                        "finalHistory": final_history,
                    },
                    "launcher": {
                        "path": launcher_path,
                        "archiveMode": package.get("launcherMode", "UNAVAILABLE"),
                        "extractedMode": launcher_entry.get("mode", "UNAVAILABLE"),
                        "sha256": package.get("launcherSha256", "UNAVAILABLE"),
                    },
                    "embeddedJava": {
                        "path": embedded_path,
                        "archiveMode": package.get("embeddedJavaMode", "UNAVAILABLE"),
                        "extractedMode": embedded_entry.get("mode", "UNAVAILABLE"),
                        "sha256": package.get("embeddedJavaSha256", "UNAVAILABLE"),
                    },
                    "jvmAndSelector": {
                        "jvmJarPath": gate_selector.get("jvmJarPath", "UNAVAILABLE"),
                        "jvmJarSha256": gate_selector.get("jvmJarSha256", "UNAVAILABLE"),
                        "selectorClassPath": gate_selector.get("selectorClassPath", "UNAVAILABLE"),
                        "selectorClassSha256": gate_selector.get("selectorClassSha256", "UNAVAILABLE"),
                        "javapSelectorMethodSha256": gate_selector.get("javapSelectorMethodSha256", "UNAVAILABLE"),
                        "operationToGateMappingProofSha256": gate_selector.get(
                            "operationToGateMappingProofSha256", "UNAVAILABLE"
                        ),
                    },
                    "attestations": {
                        "readOnlySha256": canonical_json_sha256(read_only),
                        "processSha256": canonical_json_sha256(process),
                        "childSecurityGuardsSha256": canonical_json_sha256(guard_values),
                        "processCount": len(process),
                        "guardAgentSha256s": sorted(
                            {str(guard.get("agentSha256", "UNAVAILABLE")) for guard in guard_values}
                        ),
                    },
                    "fixture": {
                        "M0": fixture.get("M0", "UNAVAILABLE"),
                        "copyIdentity": fixture.get("copyIdentity", "UNAVAILABLE"),
                        "S0": fixture.get("S0", "UNAVAILABLE"),
                        "D0": fixture.get("D0", "UNAVAILABLE"),
                        "C1": fixture.get("C1", "UNAVAILABLE"),
                        "S1": fixture.get("S1", "UNAVAILABLE"),
                    },
                }
            )
        return results

    def log_inputs(self) -> list[dict[str, Any]]:
        expected = [
            self.layout.logs / f"{slug}-{stream}.log"
            for slug, _, _ in CASE_SPECS
            for stream in ("complete", "stdout", "stderr")
        ]
        for path in expected:
            self.required_file(path)
        if not self.layout.logs.is_dir():
            return []
        artifacts: list[dict[str, Any]] = []
        for path in sorted(
            (
                path
                for path in self.layout.logs.rglob("*")
                if path.is_file() and not path.is_symlink() and path != self.layout.focused_log
            ),
            key=lambda candidate: self.relative(candidate),
        ):
            artifact = self.artifact(path)
            if artifact is not None:
                artifacts.append(artifact)
        return artifacts

    def failure_diagnostics(self) -> tuple[list[dict[str, Any]], str]:
        expected = [self.layout.diagnostics / f"{slug}-failure-diagnostic.json" for slug, _, _ in CASE_SPECS]
        for path in expected:
            self.required_file(path)
        leaves: list[dict[str, Any]] = []
        if self.layout.diagnostics.is_dir():
            paths = sorted(
                (
                    path
                    for path in self.layout.diagnostics.rglob("*")
                    if path.is_file() and not path.is_symlink() and path != self.layout.diagnostics_aggregate
                ),
                key=lambda path: self.relative(path),
            )
            for path in paths:
                artifact = self.artifact(path)
                if artifact is None:
                    continue
                entry: dict[str, Any] = dict(artifact)
                if path.suffix == ".json":
                    document = self.read_json(path)
                    if isinstance(document, dict):
                        entry["status"] = document.get("status", "UNAVAILABLE")
                        entry["firstFailure"] = document.get("firstFailure")
                leaves.append(entry)
        tree_sha256 = canonical_json_sha256(leaves)
        return leaves, tree_sha256

    def write_failure_aggregate(self, leaves: list[dict[str, Any]], tree_sha256: str) -> dict[str, Any]:
        value = {
            "schemaVersion": 1,
            "kind": "aggregate-failure-diagnostics-tree",
            "requirementId": REQUIREMENT_ID,
            "leafFiles": leaves,
            "treeSha256": tree_sha256,
            "missingArtifacts": sorted(self.missing),
            "validationErrors": sorted(self.errors),
        }
        write_json(self.layout.diagnostics_aggregate, value)
        return self.artifact(self.layout.diagnostics_aggregate) or {}

    def write_focused_log(
        self,
        revision: dict[str, Any],
        host: dict[str, Any],
        log_artifacts: list[dict[str, Any]],
        verifier: dict[str, Any],
        cucumber: dict[str, Any],
        junit: dict[str, Any],
    ) -> dict[str, Any]:
        sections: list[str] = [
            "RefactorKit packaged Maven module-rename qualification focused log",
            f"schemaVersion={SCHEMA_VERSION}",
            f"requirementId={REQUIREMENT_ID}",
            f"repositoryCommit={revision.get('commit')}",
            f"repositoryTree={revision.get('tree')}",
            f"repositoryWorktreeState={revision.get('worktreeState')}",
            f"githubSha={revision.get('githubSha')}",
            f"host={host.get('operatingSystem')}/{host.get('architecture')}",
            f"platform={host.get('platform')}",
            f"cucumberScenarios={cucumber.get('scenarioCount')}",
            f"cucumberSteps={cucumber.get('stepCount')}",
            f"junitTotals={json.dumps(junit.get('totals', {}), sort_keys=True, separators=(',', ':'))}",
            f"verifierStatus={verifier.get('status')}",
            "contentNormalization=CRLF_TO_LF",
            "",
        ]
        source_paths = [self.layout.repository / artifact["path"] for artifact in log_artifacts]
        verifier_log = verifier.get("completeLog") if isinstance(verifier, dict) else None
        if isinstance(verifier_log, dict):
            source_paths.append(self.layout.repository / verifier_log["path"])
        for path in source_paths:
            content = path.read_text(encoding="utf-8", errors="backslashreplace").replace("\r\n", "\n").replace("\r", "\n")
            sections.append(f"===== {self.relative(path)} sha256={sha256_file(path)} =====\n")
            sections.append(content)
            if not content.endswith("\n"):
                sections.append("\n")
        sections.extend(
            [
                "===== finalizer reconciliation =====\n",
                f"missingArtifacts={json.dumps(sorted(self.missing), ensure_ascii=False, separators=(',', ':'))}\n",
                f"validationErrors={json.dumps(sorted(self.errors), ensure_ascii=False, separators=(',', ':'))}\n",
            ]
        )
        self.layout.focused_log.parent.mkdir(parents=True, exist_ok=True)
        self.layout.focused_log.write_text("".join(sections), encoding="utf-8", newline="\n")
        return self.artifact(self.layout.focused_log) or {}

    def run(self) -> int:
        self.layout.manifests.mkdir(parents=True, exist_ok=True)
        self.layout.logs.mkdir(parents=True, exist_ok=True)
        self.layout.diagnostics.mkdir(parents=True, exist_ok=True)

        revision = self.revision()
        host = self.host()
        requirements, oracle = self.requirements()
        test_outcome = self.test_outcome_evidence()

        archive_artifact = self.artifact(self.layout.archive)
        archive_sha256 = archive_artifact["sha256"] if archive_artifact else "UNAVAILABLE"
        checksum = self.checksum_sidecar(self.layout.candidate_checksum, self.layout.archive)
        verifier = self.verifier_evidence(host, archive_sha256)
        globals_evidence, candidate_manifest = self.global_manifests(revision)
        fixture_sha256 = globals_evidence["fixture"]["treeSha256"]
        cases = self.case_evidence(revision, host, oracle, candidate_manifest, fixture_sha256)
        cucumber = self.cucumber_evidence()
        junit = self.junit_evidence()
        log_artifacts = self.log_inputs()
        diagnostic_leaves, diagnostic_tree_sha256 = self.failure_diagnostics()

        expected_manifest_paths = [
            self.layout.manifests / name
            for name in (
                "candidate-archive-checksum.json",
                "candidate-extracted-no-follow-manifest.json",
                "fixture-m0-no-follow-manifest.json",
                "pairwise-disposable-copies.json",
                "packaged-cli-case-manifest.json",
                "packaged-daemon-case-manifest.json",
                "packaged-mcp-case-manifest.json",
            )
        ]
        families = {
            "logs": {
                "uploadRoot": self.relative(self.layout.logs),
                "requiredComplete": all(path.is_file() and not path.is_symlink() for path in [
                    self.layout.logs / f"{slug}-{stream}.log"
                    for slug, _, _ in CASE_SPECS
                    for stream in ("complete", "stdout", "stderr")
                ]),
            },
            "cucumberJson": {
                "uploadRoot": self.relative(self.layout.cucumber),
                "requiredComplete": self.layout.cucumber.is_file() and not self.layout.cucumber.is_symlink(),
            },
            "junitXml": {
                "uploadRoot": self.relative(self.layout.junit_root),
                "requiredComplete": bool(junit["files"]),
            },
            "manifests": {
                "uploadRoot": self.relative(self.layout.manifests),
                "requiredComplete": all(path.is_file() and not path.is_symlink() for path in expected_manifest_paths),
            },
            "failureDiagnostics": {
                "uploadRoot": self.relative(self.layout.diagnostics),
                "requiredComplete": all(
                    (self.layout.diagnostics / f"{slug}-failure-diagnostic.json").is_file()
                    for slug, _, _ in CASE_SPECS
                ),
            },
            "candidateChecksum": {
                "uploadRoot": self.relative(self.layout.candidate_checksum),
                "requiredComplete": self.layout.candidate_checksum.is_file() and not self.layout.candidate_checksum.is_symlink(),
            },
        }
        for name, family in families.items():
            self.require(family["requiredComplete"] is True, f"CI upload family {name} is incomplete")

        diagnostics_aggregate = self.write_failure_aggregate(diagnostic_leaves, diagnostic_tree_sha256)
        focused_log = self.write_focused_log(revision, host, log_artifacts, verifier, cucumber, junit)
        families["logs"]["focusedLog"] = focused_log
        families["junitXml"]["deterministicAggregate"] = junit["deterministicAggregate"]
        families["failureDiagnostics"]["deterministicAggregate"] = diagnostics_aggregate
        families["manifests"]["hostManifestPlannedPath"] = self.relative(self.layout.host_manifest)

        missing = sorted(set(self.missing))
        errors = sorted(set(self.errors))
        status = "PASSED" if not missing and not errors else "FAILED"
        binding = {
            "revision": revision,
            "host": host,
            "requirements": requirements,
            "testTask": test_outcome,
            "package": {
                "runtimeZip": archive_artifact,
                "checksumFile": checksum,
                "extractedTree": globals_evidence["candidateExtracted"],
                "globalManifests": globals_evidence["artifacts"],
                "verifier": verifier,
            },
            "fixture": globals_evidence["fixture"],
            "pairwiseCopies": globals_evidence["pairwiseCopies"],
            "cases": cases,
            "reports": {
                "cucumberJson": cucumber,
                "junitXml": junit,
                "focusedLog": focused_log,
                "failureDiagnostics": {
                    "leafFiles": diagnostic_leaves,
                    "treeSha256": diagnostic_tree_sha256,
                    "deterministicAggregate": diagnostics_aggregate,
                },
            },
            "guardReadOnlyProcessAttestations": [case.get("attestations", {}) for case in cases],
            "ciUploadFamilies": families,
            "completeness": {
                "status": status,
                "missingArtifacts": missing,
                "validationErrors": errors,
            },
        }
        manifest = {
            "schemaVersion": SCHEMA_VERSION,
            "manifestKind": MANIFEST_KIND,
            "requirementId": REQUIREMENT_ID,
            "status": status,
            "binding": binding,
            "bindingSha256": canonical_json_sha256(binding),
        }
        write_json(self.layout.host_manifest, manifest)
        manifest_sha256 = sha256_file(self.layout.host_manifest)
        print(f"Qualification host evidence manifest: {self.relative(self.layout.host_manifest)}")
        print(f"Qualification host evidence manifest SHA-256: {manifest_sha256}")
        print(f"Qualification host evidence binding SHA-256: {manifest['bindingSha256']}")
        print(f"Qualification host evidence status: {status}")
        if missing:
            print("Missing required artifacts:", file=sys.stderr)
            for path in missing:
                print(f"- {path}", file=sys.stderr)
        if errors:
            print("Qualification evidence validation errors:", file=sys.stderr)
            for error in errors:
                print(f"- {error}", file=sys.stderr)
        return 0 if status == "PASSED" else 1


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", required=True, type=pathlib.Path)
    parser.add_argument("--build-directory", required=True, type=pathlib.Path)
    parser.add_argument("--native-qualification", action="store_true")
    return parser.parse_args()


def emergency_manifest(layout: Layout, failure: BaseException) -> None:
    error = f"finalizer internal failure: {failure.__class__.__name__}: {failure}"
    binding = {
        "completeness": {
            "status": "FAILED",
            "missingArtifacts": [],
            "validationErrors": [error],
        },
        "internalFailureTraceback": traceback.format_exc(),
    }
    manifest = {
        "schemaVersion": SCHEMA_VERSION,
        "manifestKind": MANIFEST_KIND,
        "requirementId": REQUIREMENT_ID,
        "status": "FAILED",
        "binding": binding,
        "bindingSha256": canonical_json_sha256(binding),
    }
    write_json(layout.host_manifest, manifest)
    print(error, file=sys.stderr)
    print(f"Failure manifest: {layout.host_manifest}", file=sys.stderr)


def main() -> int:
    arguments = parse_arguments()
    repository = arguments.repository_root.resolve(strict=True)
    build = arguments.build_directory.resolve(strict=False)
    layout = Layout(repository=repository, build=build)
    native = arguments.native_qualification or os.environ.get("GITHUB_ACTIONS", "").lower() == "true"
    try:
        return Finalizer(layout, native).run()
    except BaseException as failure:  # noqa: BLE001 - a failure manifest is mandatory
        emergency_manifest(layout, failure)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
