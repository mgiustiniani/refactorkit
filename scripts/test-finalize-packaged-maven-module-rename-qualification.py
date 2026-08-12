#!/usr/bin/env python3
"""Focused fail-closed tests for packaged module-rename evidence finalization."""

from __future__ import annotations

import importlib.util
import pathlib
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name("finalize-packaged-maven-module-rename-qualification.py")
SPEC = importlib.util.spec_from_file_location("packaged_module_rename_finalizer", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
FINALIZER_MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = FINALIZER_MODULE
SPEC.loader.exec_module(FINALIZER_MODULE)


class VerifierEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repository = pathlib.Path(self.temporary.name).resolve()
        self.build = self.repository / "modules/refactorkit-cli/build"
        self.layout = FINALIZER_MODULE.Layout(repository=self.repository, build=self.build)
        self.layout.archive.parent.mkdir(parents=True)
        self.layout.archive.write_bytes(b"candidate-runtime-zip")
        self.archive_sha256 = FINALIZER_MODULE.sha256_file(self.layout.archive)
        self.layout.candidate_checksum.parent.mkdir(parents=True)
        self.layout.candidate_checksum.write_text(
            f"{self.archive_sha256}  {self.layout.archive.name}\n",
            encoding="ascii",
        )
        self.checksum_sha256 = FINALIZER_MODULE.sha256_file(self.layout.candidate_checksum)
        self.host = {"platform": "linux-x86_64"}

    def new_finalizer(self):
        return FINALIZER_MODULE.Finalizer(self.layout, native_qualification=False)

    def write_verifier(
        self,
        *,
        write_log: bool = True,
        write_status: bool = True,
        exit_code: str = "0",
        platform: str = "linux-x86_64",
        archive_sha256: str | None = None,
        checksum_sha256: str | None = None,
    ) -> None:
        archive_sha256 = archive_sha256 or self.archive_sha256
        checksum_sha256 = checksum_sha256 or self.checksum_sha256
        self.layout.verifier.mkdir(parents=True, exist_ok=True)
        if write_status:
            (self.layout.verifier / "runtime-archive-verifier-status.properties").write_text(
                "\n".join(
                    (
                        "schemaVersion=1",
                        f"platform={platform}",
                        f"exitCode={exit_code}",
                        f"archiveSha256={archive_sha256}",
                        f"checksumSha256={checksum_sha256}",
                        "",
                    )
                ),
                encoding="utf-8",
            )
        if write_log:
            (self.layout.verifier / "runtime-archive-verifier-complete.log").write_text(
                "\n".join(
                    (
                        "schemaVersion=1",
                        f"platform={platform}",
                        f"archive={self.layout.archive.relative_to(self.repository).as_posix()}",
                        f"archiveSha256={archive_sha256}",
                        f"checksum={self.layout.candidate_checksum.relative_to(self.repository).as_posix()}",
                        f"checksumSha256={checksum_sha256}",
                        f"exitCode={exit_code}",
                        "===== stdout =====",
                        "verified",
                        "===== stderr =====",
                        "",
                    )
                ),
                encoding="utf-8",
            )

    def evaluate(self):
        finalizer = self.new_finalizer()
        evidence = finalizer.verifier_evidence(self.host, self.archive_sha256)
        return finalizer, evidence

    def test_complete_matching_verifier_is_accepted(self) -> None:
        self.write_verifier()
        finalizer, evidence = self.evaluate()
        self.assertEqual("PRESENT", evidence["status"])
        self.assertEqual([], finalizer.missing)
        self.assertEqual([], finalizer.errors)

    def test_both_verifier_artifacts_are_mandatory(self) -> None:
        finalizer, evidence = self.evaluate()
        self.assertEqual("MISSING", evidence["status"])
        self.assertEqual(2, len(finalizer.missing))

        self.write_verifier(write_log=False)
        finalizer, evidence = self.evaluate()
        self.assertEqual("MISSING", evidence["status"])
        self.assertEqual(1, len(finalizer.missing))
        self.assertTrue(finalizer.missing[0].endswith("runtime-archive-verifier-complete.log"))

        for child in self.layout.verifier.iterdir():
            child.unlink()
        self.write_verifier(write_status=False)
        finalizer, evidence = self.evaluate()
        self.assertEqual("MISSING", evidence["status"])
        self.assertEqual(1, len(finalizer.missing))
        self.assertTrue(finalizer.missing[0].endswith("runtime-archive-verifier-status.properties"))

    def assert_rejected(self, expected_fragment: str, **overrides: str) -> None:
        self.write_verifier(**overrides)
        finalizer, _ = self.evaluate()
        self.assertTrue(
            any(expected_fragment in error for error in finalizer.errors),
            finalizer.errors,
        )
        for child in self.layout.verifier.iterdir():
            child.unlink()

    def test_nonzero_and_identity_mismatches_are_rejected(self) -> None:
        self.assert_rejected("did not exit successfully", exit_code="7")
        self.assert_rejected("platform differs", platform="macos-x86_64")
        self.assert_rejected("archive hash differs", archive_sha256="0" * 64)
        self.assert_rejected("checksum-file hash differs", checksum_sha256="f" * 64)

    def test_malformed_status_is_rejected(self) -> None:
        self.write_verifier()
        status = self.layout.verifier / "runtime-archive-verifier-status.properties"
        status.write_text("schemaVersion=1\nnot-a-property\n", encoding="utf-8")
        finalizer, _ = self.evaluate()
        self.assertTrue(any("invalid properties" in error for error in finalizer.errors))


if __name__ == "__main__":
    unittest.main(verbosity=2)
