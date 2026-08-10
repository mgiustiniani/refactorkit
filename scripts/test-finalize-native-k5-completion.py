#!/usr/bin/env python3
"""Contract tests for the K5 completion native receipt finalizer."""

from __future__ import annotations

import importlib.util
from pathlib import Path, PureWindowsPath
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "finalize_native_k5_completion", ROOT / "scripts/finalize-native-k5-completion.py"
)
assert SPEC is not None and SPEC.loader is not None
FINALIZER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FINALIZER)


class K5CompletionFinalizerContractTest(unittest.TestCase):
    def test_exact_oracle_has_twenty_three_unique_cases_in_five_suites(self) -> None:
        self.assertEqual(5, len(FINALIZER.REQUIRED_SUITES))
        cases = [case for suite in FINALIZER.REQUIRED_SUITES.values() for case in suite]
        self.assertEqual(23, len(cases))
        self.assertEqual(len(cases), len(set(cases)))

    def test_exact_platform_matrix_is_closed(self) -> None:
        self.assertEqual(
            {"linux-x86_64", "windows-x86_64", "macos-x86_64", "macos-aarch64"},
            FINALIZER.PLATFORMS,
        )

    def test_windows_checksum_path_is_compared_as_posix(self) -> None:
        fields = [FINALIZER.REQUIREMENT_SHA256, "docs/requirements/kotlin-k5-bounded-completion.md"]
        self.assertTrue(FINALIZER.valid_checksum_record(fields, PureWindowsPath(
            "docs/requirements/kotlin-k5-bounded-completion.md"
        )))
        self.assertFalse(FINALIZER.valid_checksum_record([fields[0], "docs\\requirements\\wrong.md"]))

    def test_sealed_requirement_and_bound_hashes_match_checkout(self) -> None:
        self.assertEqual(FINALIZER.REQUIREMENT_SHA256, FINALIZER.sha256(ROOT / FINALIZER.REQUIREMENT))
        for path, digest in FINALIZER.BOUND_REQUIREMENTS.items():
            self.assertEqual(digest, FINALIZER.sha256(ROOT / path), path.as_posix())

    def test_checksum_sidecar_is_exact(self) -> None:
        sidecar = ROOT / FINALIZER.REQUIREMENT.with_suffix(FINALIZER.REQUIREMENT.suffix + ".sha256")
        self.assertTrue(FINALIZER.valid_checksum_record(sidecar.read_text().strip().split()))
        self.assertIn("shared Java/Kotlin add-parameter", FINALIZER.COMPATIBILITY_SMOKE_MARKER)

    def test_every_subject_file_exists_and_includes_finalizer_contract(self) -> None:
        self.assertIn(Path("scripts/finalize-native-k5-completion.py"), FINALIZER.SUBJECT_FILES)
        self.assertIn(Path("scripts/test-finalize-native-k5-completion.py"), FINALIZER.SUBJECT_FILES)
        self.assertFalse([path for path in FINALIZER.SUBJECT_FILES if not (ROOT / path).is_file()])

    def test_both_packaged_markers_are_terminal_unique_and_failure_free(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "smoke.log"
            for marker in (FINALIZER.SMOKE_MARKER, FINALIZER.COMPATIBILITY_SMOKE_MARKER):
                log.write_text("progress\n" + marker + "\n")
                FINALIZER.validate_smoke(log, marker, "test smoke")
                log.write_text(marker + "\nextra\n" + marker + "\n")
                with self.assertRaisesRegex(ValueError, "terminal exact marker"):
                    FINALIZER.validate_smoke(log, marker, "test smoke")
                log.write_text("Traceback\n" + marker + "\n")
                with self.assertRaisesRegex(ValueError, "failure evidence"):
                    FINALIZER.validate_smoke(log, marker, "test smoke")

    def test_parse_count_rejects_invalid_and_negative_values(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / "TEST.xml"
            report.write_text('<testsuite tests="x"/>')
            root = ET.parse(report).getroot()
            with self.assertRaisesRegex(ValueError, "invalid JUnit tests"):
                FINALIZER.parse_count(root, "tests", report)
            root.set("tests", "-1")
            with self.assertRaisesRegex(ValueError, "negative JUnit tests"):
                FINALIZER.parse_count(root, "tests", report)

    def test_detected_platform_normalizes_supported_machine_names(self) -> None:
        with patch.object(FINALIZER.host_platform, "system", return_value="Windows"), \
                patch.object(FINALIZER.host_platform, "machine", return_value="AMD64"):
            self.assertEqual("windows-x86_64", FINALIZER.detected_platform())
        with patch.object(FINALIZER.host_platform, "system", return_value="Darwin"), \
                patch.object(FINALIZER.host_platform, "machine", return_value="arm64"):
            self.assertEqual("macos-aarch64", FINALIZER.detected_platform())
        with patch.object(FINALIZER.host_platform, "system", return_value="Solaris"), \
                patch.object(FINALIZER.host_platform, "machine", return_value="sparc"):
            with self.assertRaisesRegex(ValueError, "unsupported qualification host"):
                FINALIZER.detected_platform()


if __name__ == "__main__":
    unittest.main()
