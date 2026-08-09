#!/usr/bin/env python3
"""Focused fail-closed tests for the v0.7.0 claim reconciler."""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


class RoadmapSupportClaimVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        releases = self.root / "docs/releases"
        releases.mkdir(parents=True)
        repository = Path(__file__).resolve().parents[1]
        for name in ("v0.7.0-plan.md", "v0.7.0-support-matrix.md"):
            (releases / name).write_text(
                (repository / "docs/releases" / name).read_text(encoding="utf-8"),
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
        self.assertEqual(4, receipt["configuredUnobservedNativeRows"])

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

    def test_premature_native_promotion_fails(self) -> None:
        path = self.root / "docs/releases/v0.7.0-support-matrix.md"
        text = path.read_text(encoding="utf-8").replace(
            "| `CONFIGURED_UNOBSERVED` |",
            "| `PASSED` |",
            1,
        )
        path.write_text(text, encoding="utf-8")
        result, receipt = self._run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(
            "Maven move-class native ledger must contain exactly four CONFIGURED_UNOBSERVED rows, found 3",
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


if __name__ == "__main__":
    unittest.main()
