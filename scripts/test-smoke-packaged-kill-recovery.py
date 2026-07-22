#!/usr/bin/env python3
"""Unit checks for the adaptive packaged kill-recovery observation window."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest


SCRIPT = Path(__file__).with_name("smoke-packaged-kill-recovery.py")
SPEC = importlib.util.spec_from_file_location("smoke_packaged_kill_recovery", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
SMOKE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SMOKE)


class _LiveProcess:
    def poll(self):
        return None


class _Daemon:
    process = _LiveProcess()


class AdaptiveCommitObservationTest(unittest.TestCase):
    def test_continuing_staging_extends_the_inactivity_window(self):
        with tempfile.TemporaryDirectory(prefix="refactorkit-kill-observer-") as temporary:
            workspace, journal, sentinel = self._workspace(Path(temporary))

            def progress_then_commit():
                for index in range(5):
                    time.sleep(0.025)
                    (workspace / "src" / f".refactorkit-stage-{index}.tmp").write_text("staged")
                sentinel.write_text("import { AccountService } from './service';\n")

            worker = threading.Thread(target=progress_then_commit)
            worker.start()
            observed = SMOKE.await_first_committed_image(
                _Daemon(), workspace,
                absolute_timeout_seconds=1.0,
                stall_timeout_seconds=0.06,
                poll_seconds=0.005,
            )
            worker.join(timeout=1)

            self.assertEqual(journal, observed)
            self.assertFalse(worker.is_alive())

    def test_stalled_staging_fails_with_bounded_progress_evidence(self):
        with tempfile.TemporaryDirectory(prefix="refactorkit-kill-observer-") as temporary:
            workspace, _, _ = self._workspace(Path(temporary))
            with self.assertRaisesRegex(AssertionError, r"no staging progress.*maxStagedFiles=0"):
                SMOKE.await_first_committed_image(
                    _Daemon(), workspace,
                    absolute_timeout_seconds=0.5,
                    stall_timeout_seconds=0.03,
                    poll_seconds=0.005,
                )

    @staticmethod
    def _workspace(root: Path) -> tuple[Path, Path, Path]:
        workspace = root / "workspace"
        source = workspace / "src"
        transactions = workspace / ".refactorkit" / "transactions"
        source.mkdir(parents=True)
        transactions.mkdir(parents=True)
        sentinel = source / "consumer-000.ts"
        sentinel.write_text("import { Service } from './service';\n")
        journal = transactions / "transaction-test.json"
        journal.write_text(json.dumps({"state": "APPLYING"}))
        return workspace, journal, sentinel


if __name__ == "__main__":
    unittest.main()
