#!/usr/bin/env python3
"""Record actual Gradle resolution facts; this is not a release attestation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile

MAX_REQUEST = 1024 * 1024
MAX_COMPONENTS = 1024
MAX_JAR = 64 * 1024 * 1024
MAX_TOTAL = 256 * 1024 * 1024


class Refused(RuntimeError):
    pass


def require(value, code):
    if not value:
        raise Refused(code)


def unique(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'COMPONENT_REQUEST_INVALID')
        result[key] = value
    return result


def identity(path):
    return path.st_dev, path.st_ino, path.st_size, path.st_mtime_ns, path.st_mode


def file_hashes(path):
    require(path.is_absolute() and '..' not in path.parts and
            all(not p.is_symlink() for p in (path, *path.parents)), 'COMPONENT_INPUT_UNAVAILABLE')
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, 'rb') as stream:
        before = os.fstat(stream.fileno())
        require(stat.S_ISREG(before.st_mode) and before.st_size <= MAX_JAR, 'COMPONENT_INPUT_UNAVAILABLE')
        sha256 = hashlib.sha256(); sha1 = hashlib.sha1(); size = 0
        for block in iter(lambda: stream.read(65536), b''):
            size += len(block)
            require(size <= MAX_JAR, 'COMPONENT_INPUT_LIMIT')
            sha256.update(block); sha1.update(block)
        require(size == before.st_size and identity(before) == identity(os.fstat(stream.fileno())) and
                identity(before) == identity(path.stat(follow_symlinks=False)), 'COMPONENT_INPUT_CHANGED')
    return {'sha256': sha256.hexdigest(), 'sha1': sha1.hexdigest()}, identity(before)


def output_path(root, output):
    require(output, 'COMPONENT_OUTPUT_REQUIRED')
    path = Path(output)
    require(path.is_absolute() and '..' not in path.parts and path.is_relative_to(root) and path != root,
            'COMPONENT_OUTPUT_UNAVAILABLE')
    require(all(not p.is_symlink() for p in (path, *path.parents)), 'COMPONENT_OUTPUT_UNAVAILABLE')
    require(not path.exists(), 'COMPONENT_OUTPUT_EXISTS')
    require(path.parent.is_dir(), 'COMPONENT_OUTPUT_UNAVAILABLE')
    return path


def capture(request):
    require(isinstance(request, dict) and set(request) == {'sourceVersion', 'configuration', 'artifacts'},
            'COMPONENT_REQUEST_INVALID')
    version = request['sourceVersion']; configuration = request['configuration']; artifacts = request['artifacts']
    require(isinstance(version, str) and re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?', version) and
            isinstance(configuration, str) and configuration.endswith(':runtimeClasspath'), 'COMPONENT_REQUEST_INVALID')
    require(isinstance(artifacts, list) and 0 < len(artifacts) <= MAX_COMPONENTS, 'COMPONENT_INPUT_LIMIT')
    paths = set(); rows = []; current = []; total = 0
    for artifact in artifacts:
        require(isinstance(artifact, dict) and set(artifact) == {'file', 'coordinates'} and
                isinstance(artifact['file'], str), 'COMPONENT_REQUEST_INVALID')
        source = Path(artifact['file']); name = source.name; coordinates = artifact['coordinates']
        require(isinstance(coordinates, dict) and coordinates.get('kind') in ('module', 'project'),
                'COMPONENT_IDENTITY_UNAVAILABLE')
        keys = {'kind', 'group', 'artifact', 'version'} | ({'projectPath'} if coordinates['kind'] == 'project' else set())
        require(set(coordinates) == keys and all(isinstance(v, str) and v and len(v) <= 512 and
                not any(ord(c) < 32 for c in v) for v in coordinates.values()), 'COMPONENT_IDENTITY_UNAVAILABLE')
        require(all(re.fullmatch(r'[A-Za-z0-9_.+-]+', coordinates[k]) for k in ('group', 'artifact', 'version')),
                'COMPONENT_IDENTITY_UNAVAILABLE')
        if coordinates['kind'] == 'project':
            require(coordinates['version'] == version and coordinates['projectPath'].startswith(':'),
                    'COMPONENT_IDENTITY_UNAVAILABLE')
        require(re.fullmatch(r'[A-Za-z0-9_.+-]+\.jar', name), 'COMPONENT_PATH_INVALID')
        target = 'lib/' + name
        require(target.lower() not in paths, 'COMPONENT_PATH_COLLISION'); paths.add(target.lower())
        hashes, facts = file_hashes(source); total += facts[2]
        require(total <= MAX_TOTAL, 'COMPONENT_INPUT_LIMIT')
        current.append((source, facts)); rows.append({'path': target, 'coordinates': coordinates, **hashes})
    for source, facts in current:
        require(identity(source.stat(follow_symlinks=False)) == facts and
                all(not p.is_symlink() for p in source.parents), 'COMPONENT_INPUT_CHANGED')
    return {'kind': 'refactorkit.resolved-runtime-components/v1', 'sourceVersion': version,
            'configuration': configuration, 'components': sorted(rows, key=lambda row: row['path'])}


def publish(path, record):
    descriptor, temporary = tempfile.mkstemp(prefix='.' + path.name + '-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'w') as stream:
            json.dump(record, stream, indent=2); stream.write('\n'); stream.flush(); os.fsync(stream.fileno())
        try:
            os.link(temporary, path, follow_symlinks=False)
        except FileExistsError:
            raise Refused('COMPONENT_OUTPUT_EXISTS') from None
    finally:
        os.unlink(temporary)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', required=True); parser.add_argument('--output', required=True)
    args = parser.parse_args()
    try:
        require(sys.flags.optimize == 0, 'OPTIMIZED_PYTHON_UNQUALIFIED')
        root = Path(args.root).absolute()
        require(root.is_dir() and all(not p.is_symlink() for p in (root, *root.parents)), 'COMPONENT_OUTPUT_UNAVAILABLE')
        output = output_path(root, args.output)
        raw = sys.stdin.buffer.read(MAX_REQUEST + 1); require(len(raw) <= MAX_REQUEST, 'COMPONENT_INPUT_LIMIT')
        request = json.loads(raw, object_pairs_hook=unique,
            parse_constant=lambda _: (_ for _ in ()).throw(Refused('COMPONENT_REQUEST_INVALID')))
        record = capture(request)
        require(output_path(root, args.output) == output, 'COMPONENT_OUTPUT_UNAVAILABLE')
        publish(output, record)
        print(json.dumps({'status': 'RECORDED_RESOLVED_RUNTIME_COMPONENTS', 'components': len(record['components']),
                          'releaseAttestation': False}))
        return 0
    except Refused as error:
        print(json.dumps({'code': str(error)}), file=sys.stderr); return 1
    except (OSError, ValueError, TypeError, KeyError, RecursionError):
        print(json.dumps({'code': 'COMPONENT_CAPTURE_UNAVAILABLE'}), file=sys.stderr); return 1


if __name__ == '__main__':
    raise SystemExit(main())
