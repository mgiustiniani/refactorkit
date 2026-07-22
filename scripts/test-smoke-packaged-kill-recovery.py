#!/usr/bin/env python3
"""Unit checks for the adaptive packaged kill-recovery observation window."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import tempfile
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


class _Clock:
    def __init__(self, on_sleep=None):
        self.now = 0.0
        self.on_sleep = on_sleep or (lambda _: None)

    def monotonic(self):
        return self.now

    def sleep(self, duration):
        self.now += duration
        self.on_sleep(self.now)


class AdaptiveCommitObservationTest(unittest.TestCase):
    def test_continuing_staging_extends_the_inactivity_window(self):
        with tempfile.TemporaryDirectory(prefix="refactorkit-kill-observer-") as temporary:
            workspace, journal, sentinel = self._workspace(Path(temporary))
            next_stage = [0]

            def advance(now):
                while next_stage[0] < 5 and now >= 0.02 * (next_stage[0] + 1):
                    index = next_stage[0]
                    (workspace / "src" / f".refactorkit-stage-{index}.tmp").write_text("staged")
                    next_stage[0] += 1
                if now >= 0.12:
                    sentinel.write_text("import { AccountService } from './service';\n")

            clock = _Clock(advance)
            observed = SMOKE.await_first_committed_image(
                _Daemon(), workspace,
                absolute_timeout_seconds=1.0,
                stall_timeout_seconds=0.05,
                poll_seconds=0.005,
                progress_probe_seconds=0.01,
                monotonic=clock.monotonic,
                sleep=clock.sleep,
            )

            self.assertEqual(journal, observed)
            self.assertGreater(clock.now, 0.05)

    def test_stalled_staging_fails_with_bounded_progress_evidence(self):
        with tempfile.TemporaryDirectory(prefix="refactorkit-kill-observer-") as temporary:
            workspace, _, _ = self._workspace(Path(temporary))
            clock = _Clock()
            with self.assertRaisesRegex(AssertionError, r"no staging progress.*maxStagedFiles=0"):
                SMOKE.await_first_committed_image(
                    _Daemon(), workspace,
                    absolute_timeout_seconds=0.5,
                    stall_timeout_seconds=0.03,
                    poll_seconds=0.005,
                    progress_probe_seconds=0.01,
                    monotonic=clock.monotonic,
                    sleep=clock.sleep,
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
