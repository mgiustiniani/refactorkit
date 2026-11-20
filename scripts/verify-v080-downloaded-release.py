#!/usr/bin/env python3
"""Verify a separately obtained Linux v0.8 asset set against its release attestation."""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import shutil
import signal
import subprocess
import sys
import tempfile

PLATFORM = 'linux-x86_64'
PROVENANCE = 'https://slsa.dev/provenance/v1'
SPDX = 'https://spdx.dev/Document/v2.3'
HERE = Path(__file__).resolve().parent


class Refused(RuntimeError):
    pass


def require(condition, code):
    if not condition:
        raise Refused(code)


def load_spdx_tools():
    spec = importlib.util.spec_from_file_location('rk_download_spdx', HERE / 'qualify-release-spdx.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run_checked(command, refusal, *, timeout, cwd=None, env=None, output_limit=64 * 1024 * 1024):
    with tempfile.TemporaryFile() as stdout, tempfile.TemporaryFile() as stderr:
        try:
            with subprocess.Popen(command, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                                  stdout=stdout, stderr=stderr, start_new_session=True) as child:
                try:
                    code = child.wait(timeout)
                except subprocess.TimeoutExpired:
                    os.killpg(child.pid, signal.SIGKILL)
                    child.wait()
                    raise Refused(refusal) from None
            require(code == 0 and stdout.tell() <= output_limit and stderr.tell() <= output_limit, refusal)
        except OSError:
            raise Refused(refusal) from None
        stdout.seek(0)
        return stdout.read()


def verified_statements(gh, args, subject, sha, predicate):
    command = [str(gh), 'attestation', 'verify', str(subject), '--repo', args.repo,
               '--signer-workflow', args.repo + '/.github/workflows/release.yml',
               '--source-ref', 'refs/tags/' + args.tag, '--source-digest', args.revision,
               '--predicate-type', predicate, '--deny-self-hosted-runners',
               '--hostname', 'github.com', '--limit', '5', '--format', 'json']
    output = run_checked(command, 'DOWNLOAD_ATTESTATION_REFUSED', timeout=90)
    rows = json.loads(output)
    require(isinstance(rows, list) and 0 < len(rows) <= 5, 'DOWNLOAD_ATTESTATION_INVALID')
    statements = [row['verificationResult']['statement'] for row in rows]
    matches = [
        statement for statement in statements
        if statement.get('predicateType') == predicate and any(
            item.get('name') == subject.name and item.get('digest', {}).get('sha256') == sha
            for item in statement.get('subject', []))
    ]
    require(matches, 'DOWNLOAD_ATTESTATION_SUBJECT_MISMATCH')
    return matches


def asset_limits(args):
    basename = f'refactorkit-runtime-{args.tag[1:]}-{PLATFORM}'
    limits = {
        basename + '.zip': 1024 ** 3,
        basename + '.zip.sha256': 4096,
        basename + '.release.json': 16 * 1024 ** 2,
        f'refactorkit-runtime-{PLATFORM}.spdx.json': 32 * 1024 ** 2,
    }
    require(args.asset_dir.is_dir() and not args.asset_dir.is_symlink(), 'DOWNLOAD_ASSET_SET_INVALID')
    require({item.name for item in args.asset_dir.iterdir()} == limits.keys(), 'DOWNLOAD_ASSET_SET_INVALID')
    return limits


def snapshot_assets(args, tools, snapshot):
    limits = asset_limits(args)
    digests = {}
    for name, limit in limits.items():
        original = tools.regular(args.asset_dir / name, limit)
        copy = snapshot / name
        shutil.copyfile(original, copy)
        digests[name] = tools.digest(copy, limit)
    return tuple(snapshot / name for name in limits), digests


def validate_subject(args):
    require(platform.system() == 'Linux' and platform.machine() == 'x86_64', 'DOWNLOAD_HOST_UNQUALIFIED')
    require(re.fullmatch(r'v0\.8\.\d+', args.tag) and re.fullmatch(r'[0-9a-f]{40}', args.revision) and
            re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repo), 'DOWNLOAD_SOURCE_INVALID')


def review(args, tools):
    require(args.gh is not None, 'DOWNLOAD_ATTESTATION_TOOL_UNAVAILABLE')
    gh = args.gh.resolve(strict=True)
    require(args.gh.is_absolute() and gh.is_file() and os.access(gh, os.X_OK), 'DOWNLOAD_ATTESTATION_TOOL_UNAVAILABLE')
    with tempfile.TemporaryDirectory(prefix='rk-download-review-') as temporary:
        scratch = Path(temporary)
        (archive, checksum, binding, sbom), digests = snapshot_assets(args, tools, scratch)
        archive_sha = digests[archive.name]
        require(checksum.read_text(encoding='ascii').split() == [archive_sha, archive.name], 'DOWNLOAD_CHECKSUM_MISMATCH')
        metadata = tools.read_json(binding)
        require(metadata.get('kind') == 'refactorkit.v080-release-binding/v1' and
                metadata.get('status') == 'BOUND_EVIDENCE' and metadata.get('platform') == PLATFORM,
                'DOWNLOAD_BINDING_INVALID')
        require(metadata.get('tag') == args.tag and metadata.get('version') == args.tag[1:] and
                metadata.get('sourceCommit') == args.revision and metadata.get('apiVersion') == '0.2',
                'DOWNLOAD_SOURCE_MISMATCH')
        require(metadata.get('asset') == {
            'name': archive.name, 'sha256': archive_sha, 'bytes': archive.stat().st_size,
        }, 'DOWNLOAD_BINDING_MISMATCH')
        spdx_binding = metadata['spdx']
        require(spdx_binding.get('sha256') == digests[sbom.name] and spdx_binding.get('name') == sbom.name and
                spdx_binding.get('runtimeComponents', {}).get('basis') == 'SOURCE_OWNED_GRADLE_REPLAY',
                'DOWNLOAD_SPDX_BINDING_MISMATCH')
        verified_statements(gh, args, archive, archive_sha, PROVENANCE)
        verified_statements(gh, args, binding, digests[binding.name], PROVENANCE)
        predicates = verified_statements(gh, args, archive, archive_sha, SPDX)
        document = tools.read_json(sbom)
        require(any(statement.get('predicate') == document for statement in predicates),
                'DOWNLOAD_SPDX_ATTESTATION_MISMATCH')
        members = tools.verify_inventory(document, tools.archive_inventory(archive), archive, args.tag[1:], archive_sha)
        require(metadata.get('runtimeMembersSha256') == members and spdx_binding.get('fileCount') == len(members),
                'DOWNLOAD_BINDING_MISMATCH')
        command = [sys.executable, str(HERE / 'verify-runtime-archive.py'), str(archive), str(checksum),
                   '--platform', PLATFORM, '--expected-version', args.tag[1:]]
        run_checked(command, 'DOWNLOAD_RUNTIME_EXECUTION_FAILED', timeout=180, cwd=scratch,
                    env={'PATH': os.defpath, 'HOME': str(scratch), 'LANG': 'C.UTF-8'}, output_limit=1024 * 1024)
        require(all(tools.digest(args.asset_dir / name) == sha for name, sha in digests.items()),
                'DOWNLOAD_INPUTS_CHANGED')
    return {'status': 'VERIFIED_DOWNLOADED_ASSET_SET', 'tag': args.tag, 'platform': PLATFORM, 'repo': args.repo,
            'sourceCommit': args.revision, 'sha256': digests, 'runtimeFilesVerified': len(members),
            'attestations': ['runtimeProvenance', 'releaseBindingProvenance', 'runtimeSpdx'],
            'runtimeSmoke': 'CLI_VERSION_AND_MCP_STDIO', 'wholeReleaseQualified': False}


def match_review(args, tools):
    receipt = tools.read_json(args.against_receipt)
    require(receipt.get('status') == 'VERIFIED_DOWNLOADED_ASSET_SET' and receipt.get('tag') == args.tag and
            receipt.get('sourceCommit') == args.revision and receipt.get('repo') == args.repo and
            receipt.get('platform') == PLATFORM and receipt.get('runtimeSmoke') == 'CLI_VERSION_AND_MCP_STDIO',
            'DOWNLOAD_REVIEW_RECEIPT_MISMATCH')
    hashes = {name: tools.digest(args.asset_dir / name, limit) for name, limit in asset_limits(args).items()}
    require(hashes == receipt.get('sha256'), 'DOWNLOAD_REVIEW_RECEIPT_MISMATCH')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--asset-dir', required=True, type=Path)
    parser.add_argument('--gh', type=Path)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--repo', required=True)
    outputs = parser.add_mutually_exclusive_group(required=True)
    outputs.add_argument('--receipt', type=Path)
    outputs.add_argument('--against-receipt', type=Path)
    args = parser.parse_args()
    tools = load_spdx_tools()
    try:
        require(not sys.flags.optimize, 'OPTIMIZED_PYTHON_UNQUALIFIED')
        validate_subject(args)
        if args.against_receipt is not None:
            match_review(args, tools)
            print(json.dumps({'status': 'DOWNLOADED_ASSETS_MATCH_REVIEW'}))
            return 0
        require(not args.receipt.is_symlink() and not args.receipt.exists() and
                not args.receipt.resolve().is_relative_to(args.asset_dir.resolve()), 'DOWNLOAD_RECEIPT_UNAVAILABLE')
        result = review(args, tools)
        tools.atomic_record(args.receipt, result)
        print(json.dumps({'status': result['status'], 'runtimeFilesVerified': result['runtimeFilesVerified']}))
        return 0
    except (Refused, tools.Refused) as error:
        print(json.dumps({'status': 'REFUSED', 'code': str(error)}), file=sys.stderr)
    except (OSError, ValueError, TypeError, KeyError, AttributeError, AssertionError):
        print(json.dumps({'status': 'REFUSED', 'code': 'DOWNLOAD_EVIDENCE_INVALID'}), file=sys.stderr)
    return 1


if __name__ == '__main__':
    raise SystemExit(main())
