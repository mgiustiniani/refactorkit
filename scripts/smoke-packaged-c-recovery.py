#!/usr/bin/env python3
"""Qualify interrupted native C component relocation on the packaged Linux daemon."""

from __future__ import annotations

import argparse
import ctypes
import importlib.util
import json
import os
from pathlib import Path
import select
import signal
import struct
import sys
import tempfile
import time
import zipfile


FORWARDER = Path('component/include/api.h')
IN_MOVED_TO = 0x80
INOTIFY_EVENT = struct.Struct('iIII')
CASES = {'c17': 'c17', 'c11-conflict': 'c11'}
TOOL_RELATIVES = {'clang': 'usr/bin/clang-22', 'clangd': 'usr/bin/clangd',
                  'clangFormat': 'usr/bin/clang-format'}
RECOVERY_REQUIRED = -32006


def load_sibling(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def fixture_images(root, dialect, clang):
    source = {
        FORWARDER: '#pragma once\nint api_value(void);\n',
        Path('component/src/api.c'): '#include "api.h"\nint api_value(void) { return 7; }\n',
        Path('component/src/use.c'): '#include "api.h"\nint api_use(void) { return api_value(); }\n',
        Path('CMakeLists.txt'): (
            'cmake_minimum_required(VERSION 3.20)\nproject(demo LANGUAGES C)\n'
            'add_library(api STATIC component/src/api.c component/src/use.c)\n'
            'target_sources(api PRIVATE component/include/api.h)\n'
            'target_include_directories(api PRIVATE component/include)\n'
        ),
        Path('operator-note.txt'): 'Unrelated workspace file.\n',
    }
    source[Path('compile_commands.json')] = json.dumps([
        {'directory': str(root), 'file': 'component/src/' + name,
         'arguments': [str(clang), '-std=' + dialect, '-I', 'component/include', '-c', 'component/src/' + name]}
        for name in ('api.c', 'use.c')
    ], separators=(',', ':'))
    relocated = {}
    for name, content in source.items():
        if name.parts[0] == 'component':
            relocated[Path('library', *name.parts[1:])] = content.replace('api_value', 'library_value')
        else:
            relocated[name] = content.replace('component/', 'library/')
    relocated[FORWARDER] = '#include "../../library/include/api.h"\n'
    return ({p: text.encode('utf-8') for p, text in source.items()},
            {p: text.encode('utf-8') for p, text in relocated.items()})


def workspace_image(root, *, include_staging=True):
    files = {}
    directories = set()
    for path in root.rglob('*'):
        relative = path.relative_to(root)
        if relative.parts[0] == '.refactorkit':
            continue
        if not include_staging and path.name.startswith('.refactorkit-stage-'):
            continue
        assert not path.is_symlink(), f'unexpected fixture symlink: {relative}'
        if path.is_dir():
            directories.add(relative)
        else:
            assert path.is_file(), f'unexpected fixture entry: {relative}'
            files[relative] = path.read_bytes()
    return files, directories


def interrupt_after_replacement(daemon, root, plan_id):
    libc = ctypes.CDLL(None, use_errno=True)
    libc.inotify_add_watch.argtypes = (ctypes.c_int, ctypes.c_char_p, ctypes.c_uint32)
    handle = libc.inotify_init1(os.O_CLOEXEC | os.O_NONBLOCK)
    if handle < 0:
        raise OSError(ctypes.get_errno(), 'inotify_init1')
    try:
        watched = libc.inotify_add_watch(handle, os.fsencode(root / FORWARDER.parent), IN_MOVED_TO)
        if watched < 0:
            raise OSError(ctypes.get_errno(), 'inotify_add_watch')
        daemon.send('refactor.apply', {'planId': plan_id})
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if select.select([handle], [], [], 0.1)[0]:
                data = os.read(handle, 65536)
                offset = 0
                while offset < len(data):
                    watch, mask, cookie, size = INOTIFY_EVENT.unpack_from(data, offset)
                    offset += INOTIFY_EVENT.size
                    name = data[offset:offset + size].split(b'\0', 1)[0]
                    offset += size
                    if watch == watched and mask & IN_MOVED_TO and name == os.fsencode(FORWARDER.name):
                        daemon.kill_tree()
                        assert daemon.process.returncode == -signal.SIGKILL, daemon.process.returncode
                        return cookie
            assert daemon.process.poll() is None, 'daemon exited before replacing the forwarding header'
            assert daemon.responses.empty(), 'apply responded before replacing the forwarding header'
        raise AssertionError('timed out waiting for the forwarding header replacement')
    finally:
        os.close(handle)


def require_recovery_error(daemon, method, root, detail):
    response = daemon.receive_reply(daemon.send(method, {'root': str(root)}))
    error = response.get('error', {})
    assert 'result' not in response and error.get('code') == RECOVERY_REQUIRED, response
    assert detail in error['message'].lower(), error


def read_journal(record):
    return json.loads(record.read_text(encoding='utf-8'))


def recover(launch, root, record, original_image, original_hash, conflict):
    restarted = launch('restart')
    try:
        require_recovery_error(restarted, 'project.open', root, 'recovery required')
        if conflict:
            path = root / FORWARDER
            committed = path.read_bytes()
            path.write_bytes(committed + b'\n/* edit made after the interruption */\n')
            foreign = workspace_image(root)
            require_recovery_error(restarted, 'patch.recover', root, 'manual recovery')
            assert workspace_image(root) == foreign, 'refused recovery changed fixture files or directories'
            assert read_journal(record)['state'] == 'RECOVERY_REQUIRED'
            path.write_bytes(committed)
        assert restarted.call('patch.recover', {'root': str(root)})['recovered'] is True
        assert restarted.call('project.open', {'root': str(root)})['snapshotHash'] == original_hash
        assert workspace_image(root) == original_image, 'recovery changed original workspace images'
        assert read_journal(record)['state'] == 'ROLLED_BACK'
    finally:
        restarted.close()
    assert restarted.process.returncode == 0, 'recovery daemon did not shut down normally'


def exercise(daemon_type, launcher, tools, case):
    dialect = CASES[case]
    conflict = case == 'c11-conflict'
    # Retry only if the real kill missed the APPLYING/partial-image window.
    for attempts in range(1, 4):
        with tempfile.TemporaryDirectory(prefix='rk-c-relocation-recovery-') as temporary:
            fixture = Path(temporary)
            root = fixture / 'workspace'
            env = {**os.environ, 'TMPDIR': temporary, 'JAVA_TOOL_OPTIONS': f'-Djava.io.tmpdir="{temporary}"'}
            original, post = fixture_images(root, dialect, tools['clang'])
            for relative, body in original.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(body)
            initial_image = workspace_image(root)
            journals = root / '.refactorkit/transactions'

            def launch(role):
                with (fixture / (role + '.stderr')).open('w', encoding='utf-8') as error_log:
                    return daemon_type(launcher, cwd=root, env=env, stderr=error_log)

            daemon = launch('apply')
            try:
                snapshot = daemon.call('project.open', {'root': str(root)})['snapshotHash']
                plan = daemon.call('c.preview', {
                    'operation': 'relocateComponent', **{name: str(path) for name, path in tools.items()},
                    'arguments': {'componentDir': 'component', 'newDir': 'library',
                                  'rename.api_value': 'library_value', 'forwardHeaders': str(FORWARDER)},
                })
                assert plan['status'] == 'PREVIEW' and plan['evidence'] == 'COMPILER_PROVEN', plan
                assert plan['snapshot']['hash'] == snapshot and plan['snapshot']['validatedOnApply'] is True
                changed = {p for p in original.keys() | post.keys() if original.get(p) != post.get(p)}
                assert {Path(p) for p in plan['affectedFiles']} == changed
                assert workspace_image(root) == initial_image and not journals.exists()
                cookie = interrupt_after_replacement(daemon, root, plan['planId'])
            finally:
                daemon.close()
            record, = journals.glob('transaction-*.json')
            interrupted = read_journal(record)
            assert interrupted['operation'] == 'relocateComponent'
            partial, _ = workspace_image(root, include_staging=False)
            assert partial.get(FORWARDER) == post.get(FORWARDER)
            assert all(partial.get(p) in (original.get(p), post.get(p)) for p in partial.keys() | changed)
            pending = {p for p in changed if partial.get(p) != post.get(p)}
            if not pending and (interrupted['state'] == 'APPLIED' or (conflict and interrupted['state'] == 'APPLYING')):
                continue
            assert interrupted['state'] == 'APPLYING', interrupted['state']
            recover(launch, root, record, initial_image, snapshot, conflict)
            return {'case': case, 'dialect': dialect, 'attempts': attempts, 'signal': 'SIGKILL',
                    'firstObservedReplacement': FORWARDER.as_posix(), 'inotifyCookie': cookie,
                    'beforeRecoveryState': 'APPLYING', 'afterRecoveryState': 'ROLLED_BACK',
                    'uncommittedImagesAtKill': sorted(p.as_posix() for p in pending),
                    'explicitRecoveryRequired': True, 'foreignEditRefusedAndPreserved': conflict,
                    'originalWorkspaceAndSnapshotRestored': True}
    raise AssertionError(f'{case}: all three real interruptions missed the required apply window')


def main():
    if sys.flags.optimize or sys.platform != 'linux':
        raise RuntimeError('C recovery qualification requires Linux and Python assertions')
    parser = argparse.ArgumentParser(description=__doc__)
    candidate = parser.add_mutually_exclusive_group(required=True)
    candidate.add_argument('--archive', type=Path)
    candidate.add_argument('--runtime', type=Path)
    parser.add_argument('--checksum', type=Path)
    parser.add_argument('--toolchain', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--case', choices=CASES)
    args = parser.parse_args()
    if (args.archive is None) != (args.checksum is None):
        parser.error('--archive and --checksum must be supplied together')
    archive_tools = load_sibling('verify-runtime-archive')
    daemon = load_sibling('smoke-packaged-kill-recovery').Daemon
    tools = {name: (args.toolchain / rel).resolve(strict=True) for name, rel in TOOL_RELATIVES.items()}
    tool_hashes = {name: archive_tools.sha256(path) for name, path in tools.items()}
    archive_sha256 = None
    with tempfile.TemporaryDirectory(prefix='rk-c-recovery-runtime-') as temporary:
        if args.archive is not None:
            archive_tools.verify_checksum(args.archive, args.checksum)
            archive_sha256 = archive_tools.sha256(args.archive)
            with zipfile.ZipFile(args.archive) as package:
                entries = archive_tools.safe_entries(package)
                archive_tools.require_layout(entries, 'linux-x86_64')
                package.extractall(temporary)
                archive_tools.restore_archive_permissions(Path(temporary), entries)
            runtime = Path(temporary) / 'refactorkit'
        else:
            runtime = args.runtime.resolve(strict=True)
        launcher = runtime / 'bin/refactorkit-daemon'
        assert launcher.is_file() and os.access(launcher, os.X_OK), 'packaged daemon launcher is unavailable'
        cases = [exercise(daemon, launcher, tools, case) for case in ([args.case] if args.case else CASES)]
    assert tool_hashes == {name: archive_tools.sha256(path) for name, path in tools.items()}, 'toolchain changed'
    if archive_sha256 is not None:
        assert archive_sha256 == archive_tools.sha256(args.archive), 'archive changed'
    receipt = {'schemaVersion': 1, 'status': 'PASS_PACKAGED_C_RELOCATION_CRASH_RECOVERY',
               'archiveSha256': archive_sha256, 'toolchainSha256': tool_hashes,
               'smokeSourceSha256': archive_tools.sha256(Path(__file__)), 'cases': cases}
    with args.output.open('x', encoding='utf-8') as output:
        output.write(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
