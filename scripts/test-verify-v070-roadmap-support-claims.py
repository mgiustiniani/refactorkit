#!/usr/bin/env python3
"""Focused fail-closed tests for the v0.7.0 claim reconciler."""

from __future__ import annotations

import ast
import hashlib
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


class V070ReleasePolicyContractTest(unittest.TestCase):
    def release(self) -> str:
        return (Path(__file__).resolve().parents[1] / ".github/workflows/release.yml").read_text()

    def test_only_v070_selects_one_qualified_host_and_holds_publication(self) -> None:
        text = self.release()
        selection = next(line for line in text.splitlines() if "include: ${{ fromJSON(" in line)
        self.assertIn("github.ref_name == 'v0.7.0'", selection)
        arrays = [json.loads(value) for value in re.findall(r"'(\[.*?\])'", selection)]
        self.assertEqual(2, len(arrays))
        self.assertEqual([{"os": "ubuntu-latest", "platform": "linux-x86_64", "gradle": "./gradlew"}], arrays[0])
        self.assertEqual(["linux-x86_64", "windows-x86_64", "macos-x86_64", "macos-aarch64"], [row["platform"] for row in arrays[1]])
        self.assertIn("draft: ${{ github.ref_name == 'v0.7.0' }}", text)
        self.assertIn("'21.0.11+10.0.LTS'", text)
        self.assertIn("if: always() && github.ref_name == 'v0.7.0'", text)

    def test_v070_checkout_exposes_the_source_parent_before_building(self) -> None:
        text = self.release()
        checkout = text.split("      - name: Checkout\n", 1)[1].split("      - name:", 1)[0]
        self.assertIn("fetch-depth: ${{ github.ref_name == 'v0.7.0' && '2' || '1' }}", checkout)
        self.assertIn("require_source_revision(os.environ['GITHUB_SHA'])", text)
        self.assertLess(text.index("require_source_revision(os.environ['GITHUB_SHA'])"), text.index("clean build goldenTest"))

    def test_source_parent_preflight_refuses_shallow_root_and_merge(self) -> None:
        blocks = re.findall(r"        shell: python\n        run: \|\n((?:          .*\n|\n)+)", self.release())
        functions = [node for block in blocks for node in ast.parse("\n".join(line[10:] if line.startswith("          ") else line for line in block.splitlines())).body if isinstance(node, ast.FunctionDef) and node.name == "require_source_revision"]
        self.assertEqual(1, len(functions), "one exact early revision preflight is required")
        source = "import subprocess, sys\n" + ast.unparse(functions[0]) + "\nrequire_source_revision(sys.argv[1])\n"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            origin, fetched = root / "origin.git", root / "fetched.git"
            def git(repository: Path, *args: str, input: str | None = None) -> str:
                return subprocess.run(["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "-C", str(repository), *args], input=input, check=True, text=True, capture_output=True).stdout.strip()
            for repository in (origin, fetched):
                repository.mkdir()
                git(repository, "init", "--bare", ".")
            tree = git(origin, "mktree", input="")
            parent = git(origin, "commit-tree", tree, "-m", "parent")
            candidate = git(origin, "commit-tree", tree, "-p", parent, "-m", "candidate")
            git(origin, "update-ref", "refs/heads/main", candidate)
            def fetch(revision: str, depth: int) -> None:
                git(fetched, "fetch", "--depth=" + str(depth), origin.as_uri(), revision)
                git(fetched, "update-ref", "HEAD", revision)
            def check(revision: str) -> subprocess.CompletedProcess[str]:
                return subprocess.run([sys.executable, "-c", source, revision], cwd=fetched, text=True, capture_output=True)
            fetch(candidate, 1)
            self.assertNotEqual(0, check(candidate).returncode)
            fetch(candidate, 2)
            self.assertEqual(0, check(candidate).returncode)
            self.assertNotEqual(0, check(parent).returncode)
            other = git(origin, "commit-tree", tree, "-p", parent, "-m", "other")
            merge = git(origin, "commit-tree", tree, "-p", candidate, "-p", other, "-m", "merge")
            git(origin, "update-ref", "refs/heads/main", merge)
            fetch(merge, 2)
            self.assertNotEqual(0, check(merge).returncode)

    def test_existing_native_gates_and_supply_chain_remain_required(self) -> None:
        text = self.release()
        for required in (
            "clean build goldenTest", "packagedMavenModuleRenameQualificationTest",
            "scripts/finalize-native-k1-k2-shared-foundations.py", "scripts/finalize-native-k5-",
            "JavaRefactoringPreviewCommandSurfaceCucumberTest", "len(cases) == 34",
            "len(steps) == 126", "len(closed) == 34", "allChildrenExited",
            "scripts/verify-runtime-archive.py", "scripts/smoke-packaged-kill-recovery.py",
            "actions/attest-build-provenance@v2", "actions/attest-sbom@v2", "format: spdx-json",
            "Independently verify downloaded release inputs", "sha256sum -c *.zip.sha256",
            "assert not wire.exists() and not wire.is_symlink()", "report.unlink()",
            "report.stat().st_mtime_ns >= started", "p.stat().st_mtime_ns >= started",
            "receipt['revision'] == binding['revision']", "receipt['schemaVersion'] == 1",
            "joined['revision']['githubSha'] == binding['revision']",
            "joined['package']['runtimeZip']['sha256'] == binding['archiveSha256']",
            "Prepare 0.7.0 isolated installation-boundary fixture",
            "systemProperty 'user.home'", "INSTALLED_BOUNDARY_TRIPWIRE_MUST_NOT_RUN",
            "Verify 0.7.0 installation-boundary fixture remained exact",
            "modules/*/build/test-results/", "modules/*/build/reports/cucumber/",
        ):
            self.assertIn(required, text)
        self.assertNotIn("continue-on-error", text)

    def test_boundary_fixture_uses_independent_path_and_bytes(self) -> None:
        def block(name: str) -> str:
            step = self.release().split("      - name: " + name + "\n", 1)[1]
            body = step.split("        run: |\n", 1)[1].split("\n      - name:", 1)[0]
            return "\n".join(line[10:] if line.startswith("          ") else line for line in body.splitlines())

        create = block("Prepare 0.7.0 isolated installation-boundary fixture")
        verify = block("Verify 0.7.0 installation-boundary fixture remained exact")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            def execute(code: str, optimized: bool = False) -> subprocess.CompletedProcess[str]:
                return subprocess.run([sys.executable, *(["-O"] if optimized else []), "-c", code], cwd=root, capture_output=True, text=True)
            self.assertNotEqual(0, execute(create, optimized=True).returncode)
            self.assertEqual(0, execute(create).returncode)
            self.assertEqual(0, execute(verify).returncode)
            self.assertNotEqual(0, execute(verify, optimized=True).returncode)
            record_path = root / ".refactorkit/runs/v070-release-ci/boundary-fixture.json"
            record = json.loads(record_path.read_text())
            launcher = Path(record["path"])
            launcher.write_bytes(b"altered inert fixture\n")
            record["sha256"] = hashlib.sha256(launcher.read_bytes()).hexdigest()
            record_path.write_text(json.dumps(record))
            self.assertNotEqual(0, execute(verify).returncode, "matching altered bytes and mutable record must not self-authenticate")
            self.assertNotEqual(0, execute(verify, optimized=True).returncode)

    def test_inline_python_blocks_parse(self) -> None:
        blocks = re.findall(r"        shell: python\n        run: \|\n((?:          .*\n|\n)+)", self.release())
        self.assertGreaterEqual(len(blocks), 8)
        for block in blocks:
            ast.parse("\n".join(line[10:] if line.startswith("          ") else line for line in block.splitlines()))


class RoadmapSupportClaimVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        releases = self.root / "docs/releases"
        releases.mkdir(parents=True)
        workflows = self.root / ".github/workflows"
        workflows.mkdir(parents=True)
        repository = Path(__file__).resolve().parents[1]
        for name in ("v0.7.0-plan.md", "v0.7.0-support-matrix.md"):
            (releases / name).write_text(
                (repository / "docs/releases" / name).read_text(encoding="utf-8"),
                encoding="utf-8",
            )
        (workflows / "ci.yml").write_text(
            (repository / ".github/workflows/ci.yml").read_text(encoding="utf-8"),
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _run(self) -> tuple[subprocess.CompletedProcess[str], dict[str, object]]:
        script = Path(__file__).with_name("verify-v070-roadmap-support-claims.py")
        result = subprocess.run(
            [sys.executable, str(script), "--repository-root", str(self.root)],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        return result, json.loads(result.stdout)

    def test_current_projection_passes(self) -> None:
        result, receipt = self._run()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("PASSED", receipt["status"])
        self.assertEqual(4, receipt["nativeRows"])
        self.assertEqual("PASSED", receipt["nativeEvidenceState"])
        self.assertFalse(receipt["nativeParentRowsOpen"])
        self.assertEqual(6, receipt["exactTemurin2111Pins"])
        self.assertEqual(7, len(receipt["closedRoadmapRowsVerified"]))

    def test_reopened_qualified_kotlin_foundation_row_fails(self) -> None:
        path = self.root / "docs/releases/v0.7.0-plan.md"
        text = path.read_text(encoding="utf-8").replace(
            "- [x] Publish qualified Kotlin/JDK/Gradle/Maven support rows and license/SBOM",
            "- [ ] Publish qualified Kotlin/JDK/Gradle/Maven support rows and license/SBOM",
        )
        path.write_text(text, encoding="utf-8")
        result, receipt = self._run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(
            "qualified roadmap row is not explicitly closed: kotlin-support",
            receipt["failures"],
        )

    def test_checked_roadmap_row_without_projection_update_fails(self) -> None:
        path = self.root / "docs/releases/v0.7.0-plan.md"
        text = path.read_text(encoding="utf-8").replace(
            "- [ ] Source-file relocation using exact `getEditsForFileRename` authority,",
            "- [x] Source-file relocation using exact `getEditsForFileRename` authority,",
        )
        path.write_text(text, encoding="utf-8")
        result, receipt = self._run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(
            "roadmap row is no longer explicitly open: typescript-advanced",
            receipt["failures"],
        )

    def test_passed_native_rows_with_open_parent_fail(self) -> None:
        path = self.root / "docs/releases/v0.7.0-plan.md"
        text = path.read_text(encoding="utf-8").replace(
            "- [x] Prove semantic preview, one-transaction apply, exact post-apply diagnostics,",
            "- [ ] Prove semantic preview, one-transaction apply, exact post-apply diagnostics,",
        )
        path.write_text(text, encoding="utf-8")
        result, receipt = self._run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(
            "native rows cannot be PASSED while either P0 native parent row is open",
            receipt["failures"],
        )

    def test_removed_nonclaim_fails(self) -> None:
        path = self.root / "docs/releases/v0.7.0-support-matrix.md"
        text = path.read_text(encoding="utf-8").replace(
            "All nine unchecked T5 advanced-operation rows remain unqualified.",
            "",
        )
        path.write_text(text, encoding="utf-8")
        result, receipt = self._run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("support boundary is missing: typescript-boundary", receipt["failures"])

    def test_missing_dedicated_native_finalizer_fails(self) -> None:
        path = self.root / ".github/workflows/ci.yml"
        text = path.read_text(encoding="utf-8").replace(
            "scripts/finalize-native-maven-move-class-authority.py",
            "scripts/missing-native-finalizer.py",
        )
        path.write_text(text, encoding="utf-8")
        result, receipt = self._run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(
            "dedicated native workflow token is missing: revision-bound-finalizer",
            receipt["failures"],
        )

    def test_k1_native_checkout_must_bind_pull_request_source_head(self) -> None:
        path = self.root / ".github/workflows/ci.yml"
        text = path.read_text(encoding="utf-8")
        prefix, block = text.split("  k1-k2-shared-foundations-native:", 1)
        block = block.replace(
            "ref: ${{ github.event.pull_request.head.sha || github.sha }}",
            "ref: ${{ github.sha }}",
            1,
        )
        path.write_text(prefix + "  k1-k2-shared-foundations-native:" + block, encoding="utf-8")
        result, receipt = self._run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(
            "dedicated K1/K2 native workflow token is missing: exact-source-head-checkout",
            receipt["failures"],
        )

    def test_k1_native_receipt_must_bind_same_source_head(self) -> None:
        path = self.root / ".github/workflows/ci.yml"
        text = path.read_text(encoding="utf-8")
        prefix, block = text.split("  k1-k2-shared-foundations-native:", 1)
        block = block.replace(
            '"--revision", "${{ github.event.pull_request.head.sha || github.sha }}"',
            '"--revision", "${{ github.sha }}"',
            1,
        )
        path.write_text(prefix + "  k1-k2-shared-foundations-native:" + block, encoding="utf-8")
        result, receipt = self._run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(
            "dedicated K1/K2 native workflow token is missing: exact-source-head-receipt",
            receipt["failures"],
        )

    def test_unavailable_or_unpinned_jdk_fails(self) -> None:
        path = self.root / ".github/workflows/ci.yml"
        text = path.read_text(encoding="utf-8").replace(
            "java-version: '21.0.11+10.0.LTS'",
            "java-version: '21.0.11+9'",
            1,
        )
        path.write_text(text, encoding="utf-8")
        result, receipt = self._run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(
            "CI must pin build, dedicated authority, runtime, K1/K2 foundation, and both K5 jobs to exactly six setup-java 21.0.11+10.0.LTS entries, found 5",
            receipt["failures"],
        )
        self.assertIn("CI contains unavailable setup-java version 21.0.11+9", receipt["failures"])


if __name__ == "__main__":
    unittest.main()
