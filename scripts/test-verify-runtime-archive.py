#!/usr/bin/env python3

import importlib.util
import pathlib
import tempfile
import unittest
import zipfile

SCRIPT = pathlib.Path(__file__).with_name("verify-runtime-archive.py")
SPEC = importlib.util.spec_from_file_location("runtime_archive_verifier", SCRIPT)
VERIFIER = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(VERIFIER)


class RuntimeArchiveVerifierTest(unittest.TestCase):
    def archive(self, entries):
        temporary = tempfile.TemporaryDirectory()
        path = pathlib.Path(temporary.name) / "runtime.zip"
        with zipfile.ZipFile(path, "w") as archive:
            for name, content in entries:
                info = zipfile.ZipInfo(name, VERIFIER.FIXED_TIMESTAMP)
                info.external_attr = (0o100644 << 16)
                archive.writestr(info, content)
        return temporary, path

    def test_rejects_traversal(self):
        temporary, path = self.archive([("refactorkit/../escape", b"x")])
        self.addCleanup(temporary.cleanup)
        with zipfile.ZipFile(path) as archive:
            with self.assertRaises(AssertionError):
                VERIFIER.safe_entries(archive)

    def test_rejects_case_fold_collision(self):
        temporary, path = self.archive([
            ("refactorkit/bin/App", b"x"),
            ("refactorkit/bin/app", b"y"),
        ])
        self.addCleanup(temporary.cleanup)
        with zipfile.ZipFile(path) as archive:
            with self.assertRaises(AssertionError):
                VERIFIER.safe_entries(archive)

    def test_rejects_checksum_mismatch(self):
        temporary, path = self.archive([("refactorkit/file", b"x")])
        self.addCleanup(temporary.cleanup)
        checksum = pathlib.Path(temporary.name) / "runtime.zip.sha256"
        checksum.write_text(f"{'0' * 64}  runtime.zip\n", encoding="ascii")
        with self.assertRaises(AssertionError):
            VERIFIER.verify_checksum(path, checksum)

    def test_detects_native_architectures(self):
        elf = bytearray(20)
        elf[:6] = b"\x7fELF\x02\x01"
        elf[18:20] = (62).to_bytes(2, "little")
        self.assertEqual("x86_64", VERIFIER.binary_architecture(bytes(elf), "linux-x86_64"))
        pe = bytearray(80)
        pe[:2] = b"MZ"
        pe[60:64] = (64).to_bytes(4, "little")
        pe[64:68] = b"PE\0\0"
        pe[68:70] = (0x8664).to_bytes(2, "little")
        self.assertEqual("x86_64", VERIFIER.binary_architecture(bytes(pe), "windows-x86_64"))
        macho = bytearray(8)
        macho[:4] = b"\xcf\xfa\xed\xfe"
        macho[4:8] = (0x0100000C).to_bytes(4, "little")
        self.assertEqual("aarch64", VERIFIER.binary_architecture(bytes(macho), "macos-aarch64"))


if __name__ == "__main__":
    unittest.main()
