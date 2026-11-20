#!/usr/bin/env python3
"""CI-only fixture input validation. Does not qualify source grammar or install tools."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

JAVA_PROFILES = {'extended': (21, 25, 26, 27), 'lts': (8, 11, 17, 21, 25)}

REFERENCE = {
    26: ('26.0.2.1+1', '39ddb4b13c04c83c39e26106855ca0f8e9ab0d7668e2c81a68d990910edbc82f',
         '047b7862d65027524fdc1fe18129f3de6282f521863e509dcb445405beb7d4cb'),
    27: ('27.0.1.0.2+1', '7718b4ea47f370bf1a4183b933a446d3609d3e83c20d2edafbcb2c050c24eef0',
         'e0b6302dd1d31b9a9d40bd379ce62c625768609c068cdaa3a988f6edfeddcd2e'),
}

class Refused(Exception):
    pass

def digest(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def selected(value: str, directory: bool = False) -> Path:
    if '\n' in value or '\r' in value:
        raise Refused('INVALID_INPUT_PATH')
    path = Path(value)
    if not path.is_absolute():
        raise Refused('INPUT_PATH_MUST_BE_ABSOLUTE')
    path = path.resolve(strict=True)
    if directory and not path.is_dir():
        raise Refused('INPUT_DIRECTORY_REQUIRED')
    if not directory and (not path.is_file() or not os.access(path, os.X_OK)):
        raise Refused('INPUT_EXECUTABLE_REQUIRED')
    return path

def sdk(value: str, major: int) -> tuple[Path, dict]:
    home = selected(value, True)
    release = home / 'release'
    if release.stat().st_size > 1_048_576:
        raise Refused('SDK_RELEASE_METADATA_LIMIT')
    properties = dict(re.findall(r'^([A-Z0-9_]+)="([^"\r\n]*)"$', release.read_text(), re.M))
    version = properties.get('JAVA_VERSION', '')
    if version.removeprefix('1.').split('.')[0] != str(major):
        raise Refused(f'SDK{major}_VERSION_MISMATCH')
    paths = ['release', 'bin/java', 'bin/javac', 'lib/ct.sym']
    if major == 8:
        paths += ['jre/lib/jce.jar', 'jre/lib/jsse.jar']
    facts = {relative: digest(home / relative) for relative in paths}
    if major in REFERENCE:
        full, release_hash, signatures_hash = REFERENCE[major]
        if properties.get('JAVA_RUNTIME_VERSION') != full or facts['release'] != release_hash or facts['lib/ct.sym'] != signatures_hash:
            raise Refused(f'SDK{major}_REFERENCE_IDENTITY_MISMATCH')
    return home, {'version': version, 'files': facts}

def node(value: str) -> tuple[Path, dict]:
    executable = selected(value)
    environment = os.environ.copy()
    environment.pop('NODE_OPTIONS', None)
    environment.pop('NODE_PATH', None)
    with tempfile.TemporaryFile() as output:
        result = subprocess.run([str(executable), '--version'], env=environment,
                                stdout=output, stderr=subprocess.STDOUT, timeout=10)
        if output.tell() > 4096:
            raise Refused('NODE_VERSION_OUTPUT_LIMIT')
        output.seek(0)
        version = output.read().decode('utf-8').strip()
    if result.returncode != 0 or version != 'v22.18.0':
        raise Refused('NODE_VERSION_MISMATCH')
    return executable, {'version': version, 'sha256': digest(executable)}

def validate(args: argparse.Namespace) -> dict:
    homes = {}
    facts = {}
    for major in JAVA_PROFILES[args.java_scope]:
        home = getattr(args, f'jdk{major}')
        if home is None:
            raise Refused(f'SDK{major}_INPUT_REQUIRED')
        homes[major], facts[f'sdk{major}'] = sdk(home, major)
    gradle = selected(args.gradle98)
    launcher = gradle.parent.parent / 'lib/gradle-launcher-9.8.1.jar'
    if not launcher.is_file():
        raise Refused('GRADLE98_DISTRIBUTION_UNAVAILABLE')
    facts['gradle98'] = {'version': '9.8.1', 'launcherSha256': digest(gradle),
                         'distributionLauncherJarSha256': digest(launcher)}
    node_path, facts['node'] = node(args.node)
    ajv = selected(args.ajv_module, True)
    package = ajv / 'package.json'
    metadata = json.loads(package.read_text())
    if metadata.get('name') != 'ajv' or metadata.get('version') != '6.15.0':
        raise Refused('EXISTING_AJV6_PROFILE_UNAVAILABLE')
    facts['ajv'] = {'version': '6.15.0', 'packageSha256': digest(package),
                    'fullDraft2020Qualification': False}
    candidate = Path(args.candidate_root)
    if not candidate.is_absolute() or '\n' in str(candidate) or '\r' in str(candidate):
        raise Refused('CANDIDATE_PATH_MUST_BE_ABSOLUTE')
    selectors = {
        'REFACTORKIT_JAVA_25_HOME': str(homes[25]),
        'REFACTORKIT_ENTRYPOINT_SDK25_HOME': str(homes[25]),
        'REFACTORKIT_GRADLE_9_8_1_EXECUTABLE': str(gradle),
        'REFACTORKIT_SCHEMA_NODE': str(node_path),
        'REFACTORKIT_SCHEMA_AJV_MODULE': str(ajv),
        'REFACTORKIT_ENTRYPOINT_CANDIDATE_ROOT': str(candidate.resolve()),
    }
    if args.java_scope == 'lts':
        selectors.update({f'REFACTORKIT_JAVA_{major}_HOME': str(homes[major]) for major in (8, 11, 17)})
    else:
        selectors.update(REFACTORKIT_JDK26_HOME=str(homes[26]), REFACTORKIT_JDK27_HOME=str(homes[27]),
                         REFACTORKIT_SDK27_HOME=str(homes[27]))
    return {'schemaVersion': 1, 'status': 'VERIFIED_FIXTURE_INPUTS', 'javaScope': args.java_scope, 'selectors': selectors,
            'artifacts': facts, 'sourceGrammarQualified': False, 'newToolsInstalled': False}

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-scope', choices=JAVA_PROFILES, default='extended')
    for major in sorted({major for profile in JAVA_PROFILES.values() for major in profile}):
        parser.add_argument(f'--jdk{major}')
    for key in ['gradle98', 'node', 'ajv-module', 'candidate-root', 'github-env', 'evidence']:
        parser.add_argument('--' + key, required=True)
    args = parser.parse_args()
    try:
        result = validate(args)
        # No output is touched until all selected inputs have passed validation.
        evidence = Path(args.evidence)
        evidence.write_text(json.dumps(result, indent=2) + '\n')
        with Path(args.github_env).open('a') as environment:
            environment.write(''.join(key + '=' + value + '\n' for key, value in result['selectors'].items()))
        print(json.dumps({'status': result['status'], 'selectors': sorted(result['selectors'])}))
        return 0
    except (Refused, OSError, ValueError, subprocess.SubprocessError) as failure:
        code = str(failure) if isinstance(failure, Refused) else 'FIXTURE_INPUT_UNAVAILABLE'
        print(json.dumps({'status': 'REFUSED', 'code': code}), file=sys.stderr)
        return 1

if __name__ == '__main__':
    raise SystemExit(main())
