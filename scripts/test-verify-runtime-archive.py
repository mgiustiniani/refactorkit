#!/usr/bin/env python3

import importlib.util
import os
import pathlib
import sys
import tempfile
import textwrap
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

    def entry(self, name, mode=0o644):
        info = zipfile.ZipInfo(name, VERIFIER.FIXED_TIMESTAMP)
        info.external_attr = ((0o100000 | mode) << 16)
        return info

    def layout(self, platform):
        windows = platform.startswith("windows-")
        suffix = ".bat" if windows else ""
        mode = 0o644 if windows else 0o755
        names = {
            "refactorkit/runtime/release": self.entry("refactorkit/runtime/release"),
            f"refactorkit/bin/refactorkit{suffix}": self.entry(
                f"refactorkit/bin/refactorkit{suffix}", mode,
            ),
            f"refactorkit/bin/refactorkit-daemon{suffix}": self.entry(
                f"refactorkit/bin/refactorkit-daemon{suffix}", mode,
            ),
            f"refactorkit/bin/refactorkit-mcp{suffix}": self.entry(
                f"refactorkit/bin/refactorkit-mcp{suffix}", mode,
            ),
            f"refactorkit/runtime/bin/java{'.exe' if windows else ''}": self.entry(
                f"refactorkit/runtime/bin/java{'.exe' if windows else ''}",
                mode,
            ),
            "refactorkit/lib/refactorkit.jar": self.entry("refactorkit/lib/refactorkit.jar"),
        }
        return names

    def mcp_program(self, *, exit_code=0, stderr_bytes=0):
        return textwrap.dedent(f"""
            import json
            import sys

            initialize = json.loads(sys.stdin.readline())
            assert initialize["method"] == "initialize"
            print(json.dumps({{
                "jsonrpc": "2.0",
                "id": initialize["id"],
                "result": {{
                    "protocolVersion": "2024-11-05",
                    "serverInfo": {{"name": "refactorkit", "version": "test"}},
                }},
            }}), flush=True)
            initialized = json.loads(sys.stdin.readline())
            assert initialized["method"] == "notifications/initialized"
            tools = json.loads(sys.stdin.readline())
            assert tools["method"] == "tools/list"
            print(json.dumps({{
                "jsonrpc": "2.0",
                "id": tools["id"],
                "result": {{"tools": [
                    {{"name": "project_scan"}},
                    {{"name": "preview_refactoring"}},
                    {{"name": "apply_refactoring"}},
                    {{"name": "rollback_refactoring"}},
                ]}},
            }}), flush=True)
            sys.stderr.buffer.write(b"x" * {stderr_bytes})
            sys.stderr.buffer.flush()
            assert sys.stdin.read() == ""
            raise SystemExit({exit_code})
        """)

    def verify_fake_mcp(self, program, **bounds):
        VERIFIER.verify_mcp_stdio(
            [sys.executable, "-c", program],
            os.environ.copy(),
            **bounds,
        )

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

    def test_requires_all_three_logical_launchers(self):
        for platform in ("linux-x86_64", "windows-x86_64"):
            suffix = ".bat" if platform.startswith("windows-") else ""
            for launcher in ("refactorkit", "refactorkit-daemon", "refactorkit-mcp"):
                with self.subTest(platform=platform, launcher=launcher):
                    entries = self.layout(platform)
                    del entries[f"refactorkit/bin/{launcher}{suffix}"]
                    with self.assertRaises(AssertionError):
                        VERIFIER.require_layout(entries, platform)

    def test_requires_posix_mcp_launcher_execute_bits(self):
        entries = self.layout("linux-x86_64")
        VERIFIER.require_layout(entries, "linux-x86_64")
        entries["refactorkit/bin/refactorkit-mcp"] = self.entry(
            "refactorkit/bin/refactorkit-mcp", 0o644,
        )
        with self.assertRaisesRegex(AssertionError, "executable bit"):
            VERIFIER.require_layout(entries, "linux-x86_64")

    def test_windows_batch_launchers_keep_non_posix_mode_semantics(self):
        VERIFIER.require_layout(self.layout("windows-x86_64"), "windows-x86_64")

    def test_bounded_mcp_exchange_accepts_clean_eof_shutdown(self):
        self.verify_fake_mcp(
            self.mcp_program(),
            response_timeout=2,
            shutdown_timeout=2,
            output_limit=65_536,
        )

    def test_bounded_mcp_exchange_rejects_malformed_response(self):
        program = "import sys; sys.stdin.readline(); print('{not-json', flush=True); sys.stdin.read()"
        with self.assertRaisesRegex(AssertionError, "malformed response"):
            self.verify_fake_mcp(
                program,
                response_timeout=2,
                shutdown_timeout=2,
                output_limit=65_536,
            )

    def test_bounded_mcp_exchange_rejects_timeout(self):
        program = "import sys, time; sys.stdin.readline(); time.sleep(30)"
        with self.assertRaisesRegex(AssertionError, "response timeout"):
            self.verify_fake_mcp(
                program,
                response_timeout=0.1,
                shutdown_timeout=0.2,
                output_limit=65_536,
            )

    def test_bounded_mcp_exchange_rejects_truncated_stream(self):
        with self.assertRaisesRegex(AssertionError, "per-stream bound"):
            self.verify_fake_mcp(
                self.mcp_program(stderr_bytes=4096),
                response_timeout=2,
                shutdown_timeout=2,
                output_limit=1024,
            )

    def test_bounded_mcp_exchange_rejects_nonzero_exit(self):
        with self.assertRaisesRegex(AssertionError, "exited nonzero"):
            self.verify_fake_mcp(
                self.mcp_program(exit_code=7),
                response_timeout=2,
                shutdown_timeout=2,
                output_limit=65_536,
            )

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
