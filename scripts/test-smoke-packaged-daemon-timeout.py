#!/usr/bin/env python3
"""Proves the packaged daemon smoke fails promptly when startup RPC hangs."""
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import time

with tempfile.TemporaryDirectory(prefix="refactorkit timeout test ") as temporary:
    package = Path(temporary) / "fake package with spaces"
    (package / "bin").mkdir(parents=True)
    (package / "runtime").mkdir()
    (package / "lib").mkdir()
    if os.name == "nt":
        launcher = package / "bin" / "refactorkit-daemon.bat"
        launcher.write_text("@echo off\n%SystemRoot%\\System32\\ping.exe -n 30 127.0.0.1 >nul\n", encoding="utf-8")
    else:
        launcher = package / "bin" / "refactorkit-daemon"
        launcher.write_text("#!/usr/bin/env sh\nsleep 30\n", encoding="utf-8")
        launcher.chmod(launcher.stat().st_mode | stat.S_IXUSR)

    env = os.environ.copy()
    env["RK_DAEMON_STARTUP_TIMEOUT"] = "0.5"
    started = time.monotonic()
    result = subprocess.run(
        [sys.executable, "scripts/smoke-packaged-daemon.py", str(launcher)],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=env,
        timeout=10,
    )
    elapsed = time.monotonic() - started
    assert result.returncode != 0, "hanging fake daemon unexpectedly passed smoke"
    assert elapsed < 8, f"timeout smoke failed too slowly: {elapsed:.2f}s"
    combined = result.stdout + result.stderr
    assert "public class ImportedSmoke" not in combined, "source marker leaked in timeout diagnostics"

print("Packaged daemon timeout self-test passed.")
