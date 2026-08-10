#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path, PureWindowsPath
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
FINALIZER = ROOT / "scripts/finalize-native-k5-move-next.py"
SPEC = importlib.util.spec_from_file_location("k5_move_next_finalizer", FINALIZER)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class NativeK5MoveNextFinalizerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.repository = self.root / "repository"
        self.repository.mkdir()
        for relative in MODULE.SUBJECT_FILES:
            target = self.repository / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            source = ROOT / relative
            if source.is_file():
                shutil.copy2(source, target)
            else:
                target.write_text(f"subject {relative}\n", encoding="utf-8")
        requirement = self.repository / MODULE.REQUIREMENT
        checksum = requirement.with_suffix(requirement.suffix + ".sha256")
        checksum.write_text(
            f"{MODULE.REQUIREMENT_SHA256}  {MODULE.REQUIREMENT.as_posix()}\n", encoding="utf-8"
        )
        (self.repository / "tracked.txt").write_text("clean\n", encoding="utf-8")
        subprocess.run(["git", "init", "-q"], cwd=self.repository, check=True)
        subprocess.run(["git", "config", "user.name", "RefactorKit Test"], cwd=self.repository, check=True)
        subprocess.run(["git", "config", "user.email", "test@refactorkit.invalid"], cwd=self.repository, check=True)
        subprocess.run(["git", "add", "."], cwd=self.repository, check=True)
        subprocess.run(["git", "commit", "-qm", "sealed K5 test subject"], cwd=self.repository, check=True)
        subprocess.run(
            ["git", "commit", "--allow-empty", "-qm", "non-merge qualification candidate"],
            cwd=self.repository, check=True,
        )
        self.revision = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=self.repository,
            check=True, text=True, capture_output=True,
        ).stdout.strip()

        self.reports = self.root / "modules"
        report_dir = self.reports / "refactorkit-jvm/build/test-results/test"
        report_dir.mkdir(parents=True)
        self.reports_by_suite = {}
        for suite, required_tests in MODULE.REQUIRED_SUITES.items():
            cases = "".join(
                f'<testcase name="{name}" classname="{suite}"/>'
                for name in sorted(required_tests)
            )
            report = report_dir / f"TEST-{suite}.xml"
            report.write_text(
                f'<testsuite name="{suite}" tests="{len(required_tests)}" '
                f'skipped="0" failures="0" errors="0">{cases}</testsuite>\n',
                encoding="utf-8",
            )
            self.reports_by_suite[suite] = report
        self.report = self.reports_by_suite[MODULE.SUITE]
        self.smoke = self.root / "smoke.log"
        self.smoke.write_text(MODULE.SMOKE_MARKER + "\n", encoding="utf-8")

        self.java_home = self.root / "jdk"
        self._write_java(self.java_home / "bin/java", build=10)
        self.package = self.root / "package"
        self._write_java(self.package / "runtime/bin/java", build=10)
        (self.package / "bin").mkdir(parents=True)
        for launcher in ("refactorkit", "refactorkit-daemon", "refactorkit-mcp"):
            path = self.package / "bin" / launcher
            path.write_text("#!/bin/sh\n", encoding="utf-8")
            path.chmod(0o755)
        (self.package / "lib").mkdir()
        for jar in MODULE.REQUIRED_JARS:
            (self.package / "lib" / jar).write_bytes((jar + "\n").encode())
        self.output = self.root / "receipt.json"

    def tearDown(self) -> None:
        self.temp.cleanup()

    @staticmethod
    def _write_java(path: Path, build: int) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            "#!/bin/sh\n"
            "echo 'openjdk version \"21.0.11\"' >&2\n"
            f"echo 'OpenJDK Runtime Environment (build 21.0.11+{build})' >&2\n",
            encoding="utf-8",
        )
        path.chmod(0o755)

    def run_finalizer(self, *, platform: str | None = None, revision: str | None = None):
        environment = os.environ.copy()
        environment["JAVA_HOME"] = str(self.java_home)
        return subprocess.run([
            "python3", str(FINALIZER),
            "--platform", platform or MODULE.detected_platform(),
            "--revision", revision or self.revision,
            "--smoke-log", str(self.smoke),
            "--report-root", str(self.reports),
            "--package-root", str(self.package),
            "--repository-root", str(self.repository),
            "--output", str(self.output),
        ], cwd=ROOT, env=environment, text=True, capture_output=True, check=False)

    def test_passing_inputs_create_bound_receipt(self) -> None:
        result = self.run_finalizer()
        self.assertEqual(0, result.returncode, result.stderr)
        receipt = json.loads(self.output.read_text(encoding="utf-8"))
        self.assertEqual("PASSED", receipt["status"])
        self.assertEqual("v0.7.0-k5-move-next", receipt["scope"])
        self.assertEqual(
            sum(len(cases) for cases in MODULE.REQUIRED_SUITES.values()),
            receipt["junit"]["totalRequiredTests"],
        )
        self.assertEqual(sorted(MODULE.REQUIRED_SUITES), sorted(receipt["junit"]["suites"]))
        self.assertEqual(MODULE.REQUIREMENT_SHA256, receipt["requirement"]["sha256"])

    def test_checksum_record_path_is_repository_posix_on_windows(self) -> None:
        windows_requirement = PureWindowsPath(
            "docs\\requirements\\kotlin-jvm-move-top-level-function-and-companion-refusal.md"
        )
        fields = [MODULE.REQUIREMENT_SHA256, windows_requirement.as_posix()]
        self.assertTrue(MODULE.valid_checksum_record(fields, windows_requirement))
        self.assertFalse(MODULE.valid_checksum_record([MODULE.REQUIREMENT_SHA256, str(windows_requirement)]))

    def test_native_workflow_fetches_candidate_parent_for_topology_check(self) -> None:
        workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        marker = "\n  k5-move-next-native:\n"
        self.assertEqual(1, workflow.count(marker))
        section = workflow.split(marker, 1)[1]
        checkout = (
            "      - name: Checkout exact subject revision\n"
            "        uses: actions/checkout@v4\n"
            "        with:\n"
            "          ref: ${{ github.event.pull_request.head.sha || github.sha }}\n"
            "          fetch-depth: 2\n"
        )
        self.assertIn(checkout, section)

    def test_dirty_tracked_checkout_fails(self) -> None:
        (self.repository / "tracked.txt").write_text("dirty\n", encoding="utf-8")
        result = self.run_finalizer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("tracked or untracked checkout is dirty", result.stderr)

    def test_untracked_source_subject_fails(self) -> None:
        source = self.repository / "modules/refactorkit-jvm/src/main/kotlin/reviewer/UntrackedBypass.kt"
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text("package reviewer\nclass UntrackedBypass\n", encoding="utf-8")
        result = self.run_finalizer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("tracked or untracked checkout is dirty", result.stderr)

    def test_revision_mismatch_fails(self) -> None:
        result = self.run_finalizer(revision="0" * 40)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("revision mismatch", result.stderr)

    def test_declared_platform_mismatch_fails(self) -> None:
        wrong = next(value for value in MODULE.PLATFORMS if value != MODULE.detected_platform())
        result = self.run_finalizer(platform=wrong)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("platform mismatch", result.stderr)

    def test_synthetic_merge_revision_fails(self) -> None:
        main_branch = subprocess.run(
            ["git", "branch", "--show-current"], cwd=self.repository,
            check=True, text=True, capture_output=True,
        ).stdout.strip()
        subprocess.run(["git", "branch", "side"], cwd=self.repository, check=True)
        (self.repository / "main.txt").write_text("main\n", encoding="utf-8")
        subprocess.run(["git", "add", "."], cwd=self.repository, check=True)
        subprocess.run(["git", "commit", "-qm", "main side"], cwd=self.repository, check=True)
        subprocess.run(["git", "checkout", "-q", "side"], cwd=self.repository, check=True)
        (self.repository / "side.txt").write_text("side\n", encoding="utf-8")
        subprocess.run(["git", "add", "."], cwd=self.repository, check=True)
        subprocess.run(["git", "commit", "-qm", "feature side"], cwd=self.repository, check=True)
        subprocess.run(["git", "checkout", "-q", main_branch], cwd=self.repository, check=True)
        subprocess.run(["git", "merge", "--no-ff", "-qm", "synthetic merge", "side"], cwd=self.repository, check=True)
        self.revision = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=self.repository,
            check=True, text=True, capture_output=True,
        ).stdout.strip()
        result = self.run_finalizer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("synthetic merge revision", result.stderr)

    def test_requirement_mutation_fails_even_if_checksum_record_is_rewritten(self) -> None:
        requirement = self.repository / MODULE.REQUIREMENT
        requirement.write_text("mutated\n", encoding="utf-8")
        checksum = requirement.with_suffix(requirement.suffix + ".sha256")
        checksum.write_text(f"{'0' * 64}  {MODULE.REQUIREMENT.as_posix()}\n", encoding="utf-8")
        subprocess.run(["git", "add", "."], cwd=self.repository, check=True)
        subprocess.run(["git", "commit", "-qm", "mutated requirement"], cwd=self.repository, check=True)
        self.revision = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=self.repository,
            check=True, text=True, capture_output=True,
        ).stdout.strip()
        result = self.run_finalizer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("immutable baseline", result.stderr)

    def test_diagnostics_route_requirement_mutation_fails(self) -> None:
        feature = self.repository / MODULE.DIAGNOSTICS_FEATURE
        feature.write_text(feature.read_text(encoding="utf-8") + "# drift\n", encoding="utf-8")
        subprocess.run(["git", "add", "."], cwd=self.repository, check=True)
        subprocess.run(["git", "commit", "-qm", "mutated route"], cwd=self.repository, check=True)
        self.revision = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=self.repository,
            check=True, text=True, capture_output=True,
        ).stdout.strip()
        result = self.run_finalizer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("diagnostics route feature", result.stderr)

    def test_non_terminal_or_duplicate_smoke_marker_fails(self) -> None:
        self.smoke.write_text(MODULE.SMOKE_MARKER + "\nextra\n" + MODULE.SMOKE_MARKER + "\n")
        result = self.run_finalizer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("terminal exact passing marker", result.stderr)

    def test_missing_or_skipped_exact_junit_case_fails(self) -> None:
        missing = sorted(MODULE.REQUIRED_TESTS)[0]
        text = self.report.read_text(encoding="utf-8").replace(
            f'<testcase name="{missing}" classname="{MODULE.SUITE}"/>', ""
        ).replace(f'tests="{len(MODULE.REQUIRED_TESTS)}"', f'tests="{len(MODULE.REQUIRED_TESTS) - 1}"')
        self.report.write_text(text, encoding="utf-8")
        result = self.run_finalizer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("incomplete or non-passing", result.stderr)

    def test_unexpected_junit_report_fails(self) -> None:
        other = self.report.parent / "TEST-unrelated.xml"
        other.write_text(
            '<testsuite name="Unrelated" tests="1" skipped="0" failures="0" errors="0"/>\n',
            encoding="utf-8",
        )
        result = self.run_finalizer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("focused JUnit reports", result.stderr)

    def test_wrong_host_or_embedded_runtime_fails(self) -> None:
        self._write_java(self.java_home / "bin/java", build=11)
        result = self.run_finalizer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("qualification host Java is not exact", result.stderr)
        self._write_java(self.java_home / "bin/java", build=10)
        self._write_java(self.package / "runtime/bin/java", build=11)
        result = self.run_finalizer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("embedded runtime is not exact", result.stderr)

    def test_missing_subject_jar_fails(self) -> None:
        (self.package / "lib" / sorted(MODULE.REQUIRED_JARS)[0]).unlink()
        result = self.run_finalizer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("subject jars are missing", result.stderr)


if __name__ == "__main__":
    unittest.main()
