#!/usr/bin/env python3
"""Pinned Linux Syft preparation and source-preserving SPDX generation; not release approval."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import signal
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
import zipfile

VERSION = '1.42.3'
COMMIT = '860126c650c2d05b63b83a3895e41268162315a3'
ARCHIVE_SHA256 = '0d6be741479eddd2c8644a288990c04f3df0d609bbc1599a005532a9dff63509'
EXECUTABLE_SHA256 = '6c1eb5c6f15c177fa3dd727ee186c61a660a3939a4e1dc1bc4b3e00eafec098e'
LICENSE_SHA256 = 'c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4'
ARCHIVE_URL = 'https://github.com/anchore/syft/releases/download/v1.42.3/syft_1.42.3_linux_amd64.tar.gz'
CONFIGURATION = b'check-for-app-update: false\njava:\n  use-network: false\n'


class Refused(RuntimeError):
    pass


def require(condition, code):
    if not condition:
        raise Refused(code)


def direct(path):
    path = Path(path).absolute()
    require(all(not p.is_symlink() for p in (path, *path.parents)), 'SBOM_INPUT_SYMLINK')
    require(path == path.resolve(), 'SBOM_INPUT_ALIAS')
    return path


def regular(path, limit=128 * 1024 * 1024):
    path = direct(path)
    require(path.is_file() and path.stat().st_size <= limit, 'SBOM_INPUT_UNAVAILABLE')
    return path


def digest(path, limit=1024 * 1024 * 1024):
    with regular(path, limit).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def read_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'SBOM_DUPLICATE_JSON_FIELD')
            result[key] = value
        return result
    return json.loads(regular(path, 64 * 1024 * 1024).read_bytes(), object_pairs_hook=unique,
                      parse_constant=lambda _: (_ for _ in ()).throw(Refused('SBOM_JSON_INVALID')))


def json_bytes(value):
    return (json.dumps(value, indent=2) + '\n').encode()


def publish(outputs):
    """Publish fresh evidence only, and remove only our own links on a partial refusal."""
    temporary = []; linked = []
    try:
        for path, content in outputs:
            path = direct(path)
            require(not path.exists(), 'SBOM_OUTPUT_EXISTS')
            path.parent.mkdir(parents=True, exist_ok=True)
            descriptor, name = tempfile.mkstemp(prefix='.' + path.name + '-', dir=path.parent)
            with os.fdopen(descriptor, 'wb') as stream:
                stream.write(content); stream.flush(); os.fsync(stream.fileno())
            temporary.append((Path(name), path))
        for source, path in temporary:
            os.link(source, path)
            linked.append((source, path))
    except BaseException:
        for source, path in reversed(linked):
            if not path.is_symlink() and path.exists() and os.path.samefile(source, path):
                path.unlink()
        raise
    finally:
        for source, _ in temporary:
            source.unlink(missing_ok=True)


def identity(executable):
    require(executable, 'SBOM_PRODUCER_SELECTOR_REQUIRED')
    require(platform.system() == 'Linux' and platform.machine().lower() in ('x86_64', 'amd64'),
            'SBOM_HOST_UNQUALIFIED')
    try:
        executable = regular(executable)
        require(os.access(executable, os.X_OK) and digest(executable) == EXECUTABLE_SHA256,
                'SBOM_PRODUCER_CHANGED')
        license_path = regular(executable.parent / 'LICENSE', 64 * 1024)
        require(digest(license_path) == LICENSE_SHA256, 'SBOM_PRODUCER_LICENSE_CHANGED')
    except (OSError, Refused) as failure:
        if isinstance(failure, Refused) and str(failure) == 'SBOM_PRODUCER_LICENSE_CHANGED':
            raise
        raise Refused('SBOM_PRODUCER_CHANGED') from None
    return {'kind': 'refactorkit.sbom-producer-identity/v1', 'version': VERSION,
            'sourceCommit': COMMIT, 'platform': 'linux-x86_64', 'path': str(executable),
            'executableSha256': EXECUTABLE_SHA256, 'licensePath': str(license_path),
            'licenseSha256': LICENSE_SHA256,
            'configurationSha256': hashlib.sha256(CONFIGURATION).hexdigest()}


def prepare(destination, archive=None, download=False):
    require(bool(archive) != bool(download), 'SBOM_ARCHIVE_SELECTOR_REQUIRED')
    destination = direct(destination)
    require(not destination.exists() and destination.parent.is_dir(), 'SBOM_DESTINATION_EXISTS')
    destination.mkdir(mode=0o700)
    downloaded = None
    if download:
        downloaded = destination / 'verified-upstream.tar.gz'
        with urllib.request.urlopen(ARCHIVE_URL, timeout=45) as incoming, downloaded.open('xb') as output:
            total = 0
            while block := incoming.read(65536):
                total += len(block)
                require(total <= 64 * 1024 * 1024, 'SBOM_ARCHIVE_SIZE_LIMIT')
                output.write(block)
        archive = downloaded
    archive = regular(archive, 64 * 1024 * 1024)
    require(digest(archive) == ARCHIVE_SHA256, 'SBOM_ARCHIVE_IDENTITY_MISMATCH')
    with tarfile.open(archive, 'r:gz') as source:
        members = source.getmembers()
        require(len(members) <= 64, 'SBOM_ARCHIVE_SIZE_LIMIT')
        for name, limit in (('syft', 128 * 1024 * 1024), ('LICENSE', 64 * 1024)):
            selected = [m for m in members if m.name == name]
            require(len(selected) == 1 and selected[0].isreg() and selected[0].size <= limit,
                    'SBOM_ARCHIVE_LAYOUT_INVALID')
            with source.extractfile(selected[0]) as stream:
                data = stream.read(limit + 1)
            require(len(data) == selected[0].size, 'SBOM_ARCHIVE_LAYOUT_INVALID')
            (destination / name).write_bytes(data)
    (destination / 'syft').chmod(0o700)
    selected = identity(destination / 'syft')
    require(digest(archive) == ARCHIVE_SHA256, 'SBOM_ARCHIVE_CHANGED')
    publish([(destination / 'toolchain.json', json_bytes({
        'kind': 'refactorkit.sbom-producer-toolchain/v1', 'producer': selected,
        'archiveUrl': ARCHIVE_URL, 'archiveSha256': ARCHIVE_SHA256, 'license': 'Apache-2.0',
    }))])
    if downloaded:
        downloaded.unlink()
    return {'status': 'VERIFIED_SBOM_PRODUCER', 'root': str(destination), 'version': VERSION}


def archive_members(archive):
    archive = regular(archive, 1024 * 1024 * 1024)
    helper = Path(__file__).with_name('verify-runtime-archive.py')
    spec = importlib.util.spec_from_file_location('rk_sbom_archive', helper)
    verifier = importlib.util.module_from_spec(spec); spec.loader.exec_module(verifier)
    with zipfile.ZipFile(archive) as source:
        entries = verifier.safe_entries(source)
        require(len(entries) <= 4096 and sum(i.file_size for i in entries.values()) <= 2 * 1024**3,
                'SBOM_PAYLOAD_SIZE_LIMIT')
        result = {}
        for name, entry in entries.items():
            mode = stat.S_IFMT(entry.external_attr >> 16)
            require(mode in (0, stat.S_IFDIR if entry.is_dir() else stat.S_IFREG), 'SBOM_PAYLOAD_LAYOUT_INVALID')
            require(name.startswith('refactorkit/'), 'SBOM_PAYLOAD_LAYOUT_INVALID')
            if not entry.is_dir():
                require(entry.file_size <= 512 * 1024 * 1024, 'SBOM_PAYLOAD_SIZE_LIMIT')
                with source.open(entry) as stream:
                    result[name] = hashlib.file_digest(stream, 'sha256').hexdigest()
        require(result, 'SBOM_PAYLOAD_MISMATCH')
        return result


def payload_members(root):
    root = direct(root)
    require(root.is_dir(), 'SBOM_PAYLOAD_MISMATCH')
    result = {}; total = 0; count = 0
    for path in sorted(root.rglob('*')):
        count += 1
        require(count <= 8192 and not path.is_symlink(), 'SBOM_PAYLOAD_LAYOUT_INVALID')
        if path.is_dir():
            continue
        total += regular(path, 512 * 1024 * 1024).stat().st_size
        require(total <= 2 * 1024**3 and len(result) < 4096, 'SBOM_PAYLOAD_SIZE_LIMIT')
        result['refactorkit/' + path.relative_to(root).as_posix()] = digest(path)
    return result


def run_producer(arguments, directory, environment, expected_output=None):
    out = directory / 'process.stdout'; err = directory / 'process.stderr'
    with out.open('wb') as stdout, err.open('wb') as stderr:
        process = subprocess.Popen(arguments, cwd=directory, env=environment,
                                   stdout=stdout, stderr=stderr, start_new_session=True)
        deadline = time.monotonic() + 60
        try:
            while process.poll() is None:
                require(time.monotonic() < deadline, 'SBOM_PRODUCER_TIMEOUT')
                require(out.stat().st_size + err.stat().st_size <= 1024 * 1024,
                        'SBOM_PRODUCER_OUTPUT_LIMIT')
                require(expected_output is None or not expected_output.exists() or
                        expected_output.stat().st_size <= 64 * 1024 * 1024, 'SBOM_PRODUCER_OUTPUT_LIMIT')
                time.sleep(0.02)
            require(process.returncode == 0, 'SBOM_PRODUCER_EXECUTION_UNAVAILABLE')
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
            process.wait()
    require(out.stat().st_size + err.stat().st_size <= 1024 * 1024, 'SBOM_PRODUCER_OUTPUT_LIMIT')
    return out.read_bytes()


def generate(executable, archive, runtime_root, output, receipt):
    selected = identity(executable); archive_sha = digest(archive)
    expected = archive_members(archive)
    require(payload_members(runtime_root) == expected, 'SBOM_PAYLOAD_MISMATCH')
    output = direct(output); receipt = direct(receipt)
    require(output != receipt and not output.exists() and not receipt.exists(), 'SBOM_OUTPUT_EXISTS')
    root = direct(runtime_root)
    require(not output.is_relative_to(root) and not receipt.is_relative_to(root), 'SBOM_OUTPUT_INSIDE_PAYLOAD')
    with tempfile.TemporaryDirectory(prefix='rk-sbom-producer-') as temporary:
        temporary = Path(temporary); configuration = temporary / 'syft.yaml'
        configuration.write_bytes(CONFIGURATION)
        environment = {k: v for k, v in os.environ.items() if not k.startswith('SYFT_')}
        environment.update(SYFT_CHECK_FOR_APP_UPDATE='false', XDG_CACHE_HOME=str(temporary / 'cache'))
        info = json.loads(run_producer([selected['path'], 'version', '-o', 'json', '--config', str(configuration)],
                                       temporary, environment))
        require(info.get('version') == VERSION and info.get('gitCommit') == COMMIT and
                info.get('platform') == 'linux/amd64', 'SBOM_PRODUCER_VERSION_MISMATCH')
        raw = temporary / 'upstream.spdx.json'
        run_producer([selected['path'], 'scan', 'dir:' + str(root), '--config', str(configuration),
                      '-o', 'spdx-json=' + str(raw)], temporary, environment, raw)
        document = read_json(raw)
        require(document.get('spdxVersion') == 'SPDX-2.3' and
                'Tool: syft-' + VERSION in document.get('creationInfo', {}).get('creators', []) and
                document.get('packages'), 'SBOM_PRODUCER_DOCUMENT_INVALID')
        require(identity(executable) == selected and digest(archive) == archive_sha and
                payload_members(root) == expected, 'SBOM_INPUTS_CHANGED')
        data = regular(raw, 64 * 1024 * 1024).read_bytes()
        value = {'schemaVersion': 1, 'kind': 'refactorkit.sbom-producer/v1',
                 'producer': selected, 'versionInfo': info, 'assetSha256': archive_sha,
                 'payloadSha256': expected, 'upstreamSpdxSha256': hashlib.sha256(data).hexdigest(),
                 'sourceHelperSha256': digest(__file__), 'createdEpochNs': time.time_ns(),
                 'wholeReleaseQualified': False}
        publish([(output, data), (receipt, json_bytes(value))])
    return {'status': 'GENERATED_PINNED_SPDX', 'fileCount': len(expected), 'producerVersion': VERSION}


def verify(receipt, archive, upstream, retained_identity, members, started_ns):
    require(receipt and Path(receipt).is_file(), 'SBOM_PRODUCER_RECEIPT_MISSING')
    current = identity(retained_identity.get('path'))  # Read/hash only: never execute a receipt path.
    require(current == retained_identity, 'SBOM_PRODUCER_CHANGED')
    path = regular(receipt, 16 * 1024 * 1024); value = read_json(path)
    require(path.stat().st_mtime_ns >= started_ns and
            isinstance(value.get('createdEpochNs'), int) and
            started_ns <= value['createdEpochNs'] <= time.time_ns(), 'SBOM_PRODUCER_RECEIPT_STALE')
    require(value.get('schemaVersion') == 1 and value.get('kind') == 'refactorkit.sbom-producer/v1' and
            value.get('producer') == current and value.get('assetSha256') == digest(archive) and
            value.get('payloadSha256') == members and value.get('upstreamSpdxSha256') == digest(upstream) and
            value.get('sourceHelperSha256') == digest(__file__) and
            value.get('versionInfo', {}).get('version') == VERSION and
            value.get('versionInfo', {}).get('gitCommit') == COMMIT and
            value.get('wholeReleaseQualified') is False, 'SBOM_PRODUCER_RECEIPT_MISMATCH')
    return {'version': VERSION, 'sourceCommit': COMMIT, 'executableSha256': EXECUTABLE_SHA256,
            'licenseSha256': LICENSE_SHA256, 'receiptSha256': digest(path),
            'upstreamSpdxSha256': digest(upstream), 'assetSha256': digest(archive)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='mode', required=True)
    staging = commands.add_parser('prepare')
    staging.add_argument('--destination', required=True)
    staging.add_argument('--archive'); staging.add_argument('--download', action='store_true')
    scan = commands.add_parser('generate')
    for name in ('producer', 'archive', 'runtime-root', 'output', 'receipt'):
        scan.add_argument('--' + name, required=True)
    args = parser.parse_args()
    try:
        require(sys.flags.optimize == 0 and sys.version_info >= (3, 11), 'SBOM_PYTHON_UNQUALIFIED')
        if args.mode == 'prepare':
            result = prepare(args.destination, args.archive, args.download)
        else:
            result = generate(args.producer, args.archive, args.runtime_root, args.output, args.receipt)
        print(json.dumps(result)); return 0
    except (Refused, OSError, ValueError, KeyError, TypeError, AssertionError, tarfile.TarError,
            zipfile.BadZipFile, subprocess.SubprocessError) as failure:
        code = str(failure) if isinstance(failure, Refused) else 'SBOM_INPUT_UNAVAILABLE'
        print(json.dumps({'status': 'REFUSED', 'code': code}), file=sys.stderr); return 1


if __name__ == '__main__':
    raise SystemExit(main())
