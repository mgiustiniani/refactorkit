#!/usr/bin/env python3
"""Bind a v0.8 Linux asset to source, fresh native evidence and SPDX; never approve publication."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import tempfile
import time
from urllib.parse import unquote
import xml.etree.ElementTree as ET
import zipfile


class Refused(RuntimeError):
    pass


PINS = {
    'usr/bin/clang-22': '31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113',
    'usr/bin/clangd': 'aacb4651e1886b98c5413369fc1789f4ca2d4fa5b94fdf0de4f48c98a52d4324',
    'usr/bin/clang-format': 'b60fe916a9d98bfd0f317a210f6a06dfde1217ebb0b605da697ef211208d3bcf',
    'usr/lib/libclang-cpp.so.22.1': 'a99d309f3cadea7fd7203ebec873bc165fc0c9ee0694ba4015c9902a5debf3a3',
}
AREAS = ('compiler-runtime', 'formatter-runtime', 'runtime-discovery', 'runtime-manifest')
MAX_NATIVE_REPORT_BYTES = 32 * 1024 * 1024
MAX_CUCUMBER_REPORT_BYTES = 64 * 1024 * 1024
COMPONENT_BUILD_INPUTS = (
    'gradlew', 'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties',
    'modules/refactorkit-cli/build.gradle.kts', 'scripts/runtime-component-manifest.gradle',
    'scripts/qualify-runtime-components.py',
)


def require(condition, code):
    if not condition:
        raise Refused(code)


def regular(path, limit=128 * 1024 * 1024):
    path = Path(path)
    require(path.is_file() and not path.is_symlink(), 'INPUT_FILE_UNAVAILABLE')
    require(all(not parent.is_symlink() for parent in path.absolute().parents), 'INPUT_SYMLINK_ANCESTOR')
    require(path.stat().st_size <= limit, 'INPUT_SIZE_LIMIT')
    return path


def digest(path, limit=1024 * 1024 * 1024):
    with regular(path, limit).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def read_json(path, *, limit=16 * 1024 * 1024):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'DUPLICATE_JSON_FIELD')
            result[key] = value
        return result
    return json.loads(regular(path, limit).read_text(), object_pairs_hook=unique)


def git(root, *arguments):
    environment = os.environ.copy()
    environment.update(GIT_OPTIONAL_LOCKS='0', GIT_TERMINAL_PROMPT='0')
    process = subprocess.run(['git', '-c', 'core.fsmonitor=false', '-c', 'core.hooksPath=/dev/null',
                              *arguments], cwd=root, env=environment, capture_output=True, timeout=30)
    require(process.returncode == 0 and len(process.stdout) <= 8 * 1024 * 1024, 'SOURCE_GIT_UNAVAILABLE')
    return process.stdout.decode('utf-8')


def source_inventory(root, allowed=(), extracted=None):
    require(not git(root, 'diff', '--no-ext-diff', '--no-textconv', '--name-only', 'HEAD').strip(), 'SOURCE_CHANGED')
    paths = git(root, 'ls-files', '--cached', '--others', '--exclude-standard', '-z').split('\0')
    result = {}; total_bytes = 0
    for name in sorted(set(filter(None, paths))):
        path = Path(name)
        require(not path.is_absolute() and '..' not in path.parts and '\n' not in name and '\r' not in name,
                'SOURCE_PATH_UNQUALIFIED')
        absolute = root / path
        if absolute in allowed:
            continue
        if extracted is not None and name.startswith('release-smoke/'):
            member = name.removeprefix('release-smoke/')
            require(member in extracted and digest(absolute) == extracted[member], 'EXTRACTED_PAYLOAD_CHANGED')
            continue
        require(len(result) < 20_000, 'SOURCE_SIZE_LIMIT')
        total_bytes += regular(absolute, 64 * 1024 * 1024).stat().st_size
        require(total_bytes <= 512 * 1024 * 1024, 'SOURCE_SIZE_LIMIT')
        result[name] = digest(absolute)
    return result


def toolchain_inventory(root):
    root = Path(root)
    require(root.is_absolute() and root.is_dir() and root.resolve() == root, 'TOOLCHAIN_UNAVAILABLE')
    result = {}
    for name, expected in PINS.items():
        result[name] = digest(root / name)
        require(result[name] == expected, 'TOOLCHAIN_IDENTITY_MISMATCH')
    resources = root / 'usr/lib/clang/22'
    require(resources.is_dir() and not resources.is_symlink(), 'TOOLCHAIN_RESOURCES_UNAVAILABLE')
    total = 0
    for path in sorted(resources.rglob('*')):
        require(not path.is_symlink(), 'TOOLCHAIN_SYMLINK')
        if path.is_dir():
            continue
        require(len(result) < 16_384, 'TOOLCHAIN_SIZE_LIMIT')
        total += regular(path, 32 * 1024 * 1024).stat().st_size
        require(total <= 128 * 1024 * 1024, 'TOOLCHAIN_SIZE_LIMIT')
        result[path.relative_to(root).as_posix()] = digest(path)
    require(len(result) > len(PINS), 'TOOLCHAIN_RESOURCES_UNAVAILABLE')
    license_path = 'usr/include/llvm/Support/LICENSE.TXT'
    result[license_path] = digest(root / license_path)
    return result


def atomic_record(path, value):
    path = Path(path)
    require(not path.exists() and not path.is_symlink(), 'OUTPUT_EXISTS')
    require(all(not parent.is_symlink() for parent in path.absolute().parents), 'OUTPUT_SYMLINK_ANCESTOR')
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix='.' + path.name + '-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'w') as stream:
            json.dump(value, stream, indent=2); stream.write('\n'); stream.flush(); os.fsync(stream.fileno())
        # Hard-link publication refuses an output concurrently created after validation.
        os.link(temporary, path)
    finally:
        os.unlink(temporary)


def scenario_locations(path):
    """Map each source scenario or Examples row to its declared title."""
    declared = {}
    outline = None
    in_examples = False
    header = False
    quote = None
    for number, raw in enumerate(regular(path).read_text().splitlines(), start=1):
        line = raw.strip()
        if line.startswith(('"""', '```')):
            quote = None if quote else line[:3]
            continue
        if quote or not line or line.startswith('#'):
            if line.startswith('# language:'):
                require(line.split(':', 1)[1].strip() == 'en', 'FEATURE_LANGUAGE_UNQUALIFIED')
            continue
        match = re.fullmatch(r'Scenario( Outline)?:\s*(.+)', line)
        if match:
            title = match[2]
            require(title not in declared, 'FEATURE_SCENARIO_AMBIGUOUS')
            outline = title if match[1] else None
            declared[title] = [] if outline else [number]
            in_examples = False
        elif outline and line.startswith('Examples:'):
            in_examples = True
            header = True
        elif in_examples and line.startswith('|'):
            if header:
                header = False
            else:
                declared[outline].append(number)
    require(declared and all(declared.values()), 'FEATURE_SCENARIO_UNAVAILABLE')
    return {number: title for title, locations in declared.items() for number in locations}

def native_reports(root, started_ns, source):
    retained = {}; totals = {}; coverage = {}
    read_count = 0; read_bytes = 0
    def fresh(path, limit=MAX_NATIVE_REPORT_BYTES):
        nonlocal read_count, read_bytes
        require(regular(path, limit).stat().st_mtime_ns >= started_ns, 'REPORTS_STALE')
        read_count += 1; read_bytes += path.stat().st_size
        require(read_count <= 8192 and read_bytes <= 512 * 1024 * 1024, 'REPORT_SIZE_LIMIT')
        return path
    for path in sorted(root.glob('modules/*/build/test-results/test/TEST-*.xml')):
        text = fresh(path).read_text()
        require('<!DOCTYPE' not in text and '<!ENTITY' not in text and '\0' not in text, 'XML_UNQUALIFIED')
        suite = ET.fromstring(text)
        counts = {name: int(suite.get(name, '0')) for name in ('tests', 'failures', 'errors', 'skipped')}
        require(all(n >= 0 for n in counts.values()) and counts['tests'] == len(suite.findall('testcase')), 'TEST_REPORT_INVALID')
        require(counts['failures'] == counts['errors'] == 0 and counts['skipped'] <= counts['tests'], 'TEST_GATE_FAILED')
        module = path.relative_to(root).parts[1]
        total = totals.setdefault(module, {key: 0 for key in counts})
        for key in counts:
            total[key] += counts[key]
        retained[path.relative_to(root).as_posix()] = digest(path)
    for name in ('refactorkit-c', 'refactorkit-cli', 'refactorkit-lsp', 'refactorkit-testkit'):
        require(totals.get(name, {}).get('tests', 0) > totals.get(name, {}).get('skipped', 0), 'TEST_REPORTS_MISSING')
    require(totals['refactorkit-c']['skipped'] == 0, 'TEST_GATE_FAILED')
    for path in sorted(root.glob('modules/*/build/reports/cucumber/*.json')):
        report = fresh(path, MAX_CUCUMBER_REPORT_BYTES)
        for feature in read_json(report, limit=MAX_CUCUMBER_REPORT_BYTES):
            name = unquote(feature.get('uri', '').removeprefix('classpath:')).replace('\\', '/').split('/')[-1]
            if not name.startswith('c-') or not name.endswith('.feature'):
                continue
            cases = coverage.setdefault(name, {})
            for case in feature.get('elements', []):
                if case.get('type') != 'scenario':
                    continue
                steps = case.get('steps', [])
                require(steps and all(s.get('result', {}).get('status') == 'passed' for s in steps), 'NATIVE_SCENARIO_FAILED')
                location = case.get('line')
                require(type(location) is int and location > 0, 'NATIVE_SCENARIO_LOCATION_INVALID')
                require(location not in cases, 'NATIVE_SCENARIO_RESULT_DUPLICATE')
                cases[location] = case.get('name')
        retained[path.relative_to(root).as_posix()] = digest(path)
    expected_features = {Path(p).name: p for p in source if p.startswith('features/c-') and p.endswith('.feature')}
    require(expected_features, 'NATIVE_FEATURES_MISSING')
    for name, relative in expected_features.items():
        declared = scenario_locations(root / relative)
        observed = coverage.get(name, {})
        require(not observed.keys() - declared.keys(), 'NATIVE_SCENARIO_COVERAGE_AMBIGUOUS')
        require(observed.keys() == declared.keys(), 'NATIVE_SCENARIO_COVERAGE_MISSING')
        for location, title in declared.items():
            actual = observed[location]
            pattern = re.sub(r'<[^<>]+>', '.*', re.escape(title))
            require(isinstance(actual, str) and re.fullmatch(pattern, actual), 'NATIVE_SCENARIO_COVERAGE_MISMATCH')
    native = {}
    for area in AREAS:
        folder = root / 'modules/refactorkit-cli/build/reports/c-qualification/test' / area
        paths = sorted(folder.rglob('*.json'))
        require(paths, 'NATIVE_EVIDENCE_MISSING')
        native[area] = {p.relative_to(root).as_posix(): digest(fresh(p)) for p in paths}
        for path in paths:
            require(isinstance(read_json(path), dict), 'NATIVE_EVIDENCE_INVALID')
    return totals, retained, native, {name: len(cases) for name, cases in coverage.items()}


def packaged_c_recovery(root, receipt_path, started_ns, source, archive_sha, toolchain):
    script = 'scripts/smoke-packaged-c-recovery.py'
    if script not in source:
        require(receipt_path is None, 'C_RECOVERY_SOURCE_UNAVAILABLE')
        return {}
    require(receipt_path is not None, 'C_RECOVERY_EVIDENCE_MISSING')
    path = regular(receipt_path)
    require(path.stat().st_mtime_ns >= started_ns, 'C_RECOVERY_EVIDENCE_STALE')
    resolved = path.resolve(strict=True)
    require(resolved.is_relative_to(root), 'C_RECOVERY_EVIDENCE_OUTSIDE_SOURCE_ROOT')
    receipt = read_json(path)
    require(receipt['schemaVersion'] == 1 and receipt['status'] == 'PASS_PACKAGED_C_RELOCATION_CRASH_RECOVERY'
            and receipt['archiveSha256'] == archive_sha and receipt['smokeSourceSha256'] == source[script],
            'C_RECOVERY_IDENTITY_MISMATCH')
    expected_tools = {name: toolchain[relative] for name, relative in (
        ('clang', 'usr/bin/clang-22'), ('clangd', 'usr/bin/clangd'), ('clangFormat', 'usr/bin/clang-format'))}
    require(receipt['toolchainSha256'] == expected_tools, 'C_RECOVERY_TOOLCHAIN_MISMATCH')
    rows = receipt['cases']
    require(isinstance(rows, list) and len(rows) == 2 and {row['case'] for row in rows} == {'c17', 'c11-conflict'},
            'C_RECOVERY_CASES_INCOMPLETE')
    for row in rows:
        conflict = row['case'] == 'c11-conflict'
        require(row['dialect'] == ('c11' if conflict else 'c17') and row['signal'] == 'SIGKILL' and
                row['firstObservedReplacement'] == 'component/include/api.h' and
                row['beforeRecoveryState'] == 'APPLYING' and row['afterRecoveryState'] == 'ROLLED_BACK' and
                row['explicitRecoveryRequired'] is True and row['foreignEditRefusedAndPreserved'] is conflict and
                row['originalWorkspaceAndSnapshotRestored'] is True and
                isinstance(row['uncommittedImagesAtKill'], list) and (not conflict or row['uncommittedImagesAtKill']),
                'C_RECOVERY_CASE_UNQUALIFIED')
    return {resolved.relative_to(root).as_posix(): digest(path)}


def runtime_members(verifier, archive):
    try:
        with zipfile.ZipFile(archive) as payload:
            entries = verifier.safe_entries(payload)
            verifier.require_layout(entries, 'linux-x86_64')
            verifier.verify_modules(payload)
            verifier.verify_architecture(payload, 'linux-x86_64')
            members = {}
            for name, item in entries.items():
                if not item.is_dir():
                    with payload.open(item) as stream:
                        members[name] = hashlib.file_digest(stream, 'sha256').hexdigest()
            return members
    except (AssertionError, zipfile.BadZipFile, UnicodeError, EOFError):
        raise Refused('ASSET_LAYOUT_INVALID') from None


def producer_service(source):
    helper = Path(__file__).with_name('qualify-sbom-producer.py')
    require(digest(helper) == source.get('scripts/qualify-sbom-producer.py'), 'SBOM_PRODUCER_SOURCE_MISMATCH')
    spec = importlib.util.spec_from_file_location('rk_sbom_producer', helper)
    service = importlib.util.module_from_spec(spec); spec.loader.exec_module(service)
    return service


def producer_identity(service, executable):
    try:
        return service.identity(executable)
    except service.Refused as failure:
        raise Refused(str(failure)) from None


def verified_producer(service, args, start, members):
    try:
        return service.verify(args.sbom_producer_receipt, args.archive, args.upstream_spdx,
                              start.get('sbomProducer', {}), members, start['startedEpochNs'])
    except service.Refused as failure:
        raise Refused(str(failure)) from None


def verify_runtime_components(root, source, supplied, started_ns, members):
    marker = 'scripts/runtime-component-manifest.gradle'
    if supplied is None and marker not in source:
        return None
    require(supplied is not None, 'RUNTIME_COMPONENTS_REQUIRED')
    require(all(source.get(name) == digest(root / name) for name in COMPONENT_BUILD_INPUTS),
            'RUNTIME_COMPONENTS_SOURCE_MISMATCH')
    manifest = regular(Path(supplied).absolute(), 1024 * 1024)
    require(manifest.is_relative_to(root) and '..' not in manifest.parts and
            str(manifest.relative_to(root)) not in source, 'RUNTIME_COMPONENTS_PATH_UNAVAILABLE')
    require(manifest.stat().st_mtime_ns >= started_ns, 'RUNTIME_COMPONENTS_STALE')
    expected = digest(manifest)
    with tempfile.TemporaryDirectory(prefix='replay-runtime-components-', dir=manifest.parent) as temporary:
        output = Path(temporary) / 'components.json'
        command = [str(root / 'gradlew'), '--offline', '--no-daemon', '--no-watch-fs',
                   '--max-workers=2', '--console=plain', ':modules:refactorkit-cli:writeRuntimeComponentManifest',
                   '-Prefactorkit.runtime.components.output=' + str(output)]
        with tempfile.TemporaryFile() as log:
            try:
                result = subprocess.run(command, cwd=root, stdin=subprocess.DEVNULL, stdout=log,
                                        stderr=subprocess.STDOUT, timeout=120)
            except (OSError, subprocess.TimeoutExpired):
                raise Refused('RUNTIME_COMPONENTS_REPLAY_UNAVAILABLE') from None
            require(result.returncode == 0 and output.is_file(), 'RUNTIME_COMPONENTS_REPLAY_UNAVAILABLE')
        require(digest(output, 1024 * 1024) == expected, 'RUNTIME_COMPONENTS_REPLAY_MISMATCH')
        replayed = read_json(output)
    require(digest(manifest) == expected, 'RUNTIME_COMPONENTS_REPLAY_MISMATCH')
    packaged = {name: sha for name, sha in members.items() if re.fullmatch(r'refactorkit/lib/[^/]+\.jar', name)}
    observed = {'refactorkit/' + row['path']: row['sha256'] for row in replayed['components']}
    require(packaged.keys() == observed.keys(), 'SPDX_RUNTIME_COMPONENTS_INCOMPLETE')
    require(packaged == observed, 'SPDX_RUNTIME_COMPONENT_DIGEST_MISMATCH')
    return {'basis': 'SOURCE_OWNED_GRADLE_REPLAY', 'manifestSha256': expected,
            'componentCount': len(observed)}


class ReleaseEvidenceBinding:
    def begin(self, args):
        require(sys.flags.optimize == 0, 'OPTIMIZED_PYTHON_UNQUALIFIED')
        require(platform.system() == 'Linux' and platform.machine().lower() in ('x86_64', 'amd64'), 'HOST_UNQUALIFIED')
        root = Path(args.root).resolve(strict=True)
        require(re.fullmatch(r'v0\.8\.\d+', args.tag) is not None, 'TAG_UNQUALIFIED')
        require(re.fullmatch(r'[0-9a-f]{40,64}', args.revision) is not None, 'REVISION_INVALID')
        require(git(root, 'rev-parse', '--show-toplevel').strip() == str(root), 'SOURCE_ROOT_MISMATCH')
        require(git(root, 'rev-parse', 'HEAD').strip() == args.revision, 'SOURCE_REVISION_MISMATCH')
        require(not git(root, 'status', '--porcelain=v1', '--untracked-files=normal').strip(), 'SOURCE_CHANGED')
        source = source_inventory(root)
        version = args.tag[1:]
        gradle = (root / 'build.gradle.kts').read_text()
        core = (root / 'modules/refactorkit-core/src/main/kotlin/org/refactorkit/core/RefactorKitVersion.kt').read_text()
        require(re.findall(r'\bversion\s*=\s*"([^"]+)"', gradle) == [version], 'SOURCE_VERSION_MISMATCH')
        require(re.findall(r'const val VERSION\s*=\s*"([^"]+)"', core) == [version] and
                re.findall(r'const val API_VERSION\s*=\s*"([^"]+)"', core) == ['0.2'], 'SOURCE_VERSION_MISMATCH')
        own = Path(__file__).resolve()
        require(source.get('scripts/' + own.name) == digest(own), 'BINDER_SOURCE_MISMATCH')
        profile = Path(args.profile).resolve(strict=True)
        data = read_json(profile)
        require(data.get('status') == 'VERIFIED_FIXTURE_INPUTS' and data.get('selectors') and data.get('artifacts'), 'FIXTURE_PROFILE_UNAVAILABLE')
        tools = toolchain_inventory(Path(args.toolchain))
        service = producer_service(source)
        producer = producer_identity(service, args.sbom_producer)
        value = dict(schemaVersion=1, kind='v080-source-native-start', tag=args.tag, version=version, apiVersion='0.2',
                     platform='linux-x86_64', revision=args.revision, tree=git(root, 'rev-parse', 'HEAD^{tree}').strip(),
                     sourceSha256=source, toolchain={'root': args.toolchain, 'version': '22.1.8', 'files': tools},
                     fixtureProfile={'path': str(profile), 'sha256': digest(profile)},
                     sbomProducer=producer, startedEpochNs=time.time_ns())
        require(source_inventory(root) == source and toolchain_inventory(Path(args.toolchain)) == tools and
                digest(profile) == value['fixtureProfile']['sha256'] and
                producer_identity(service, args.sbom_producer) == producer, 'INPUTS_CHANGED_DURING_CAPTURE')
        atomic_record(args.output, value)
        return {'status': 'SOURCE_CAPTURED', 'revision': args.revision, 'sourceFiles': len(source)}

    def finish(self, args):
        require(sys.flags.optimize == 0, 'OPTIMIZED_PYTHON_UNQUALIFIED')
        root = Path(args.root).resolve(strict=True)
        start_path = Path(args.start).resolve(strict=True)
        start = read_json(start_path)
        require(start.get('schemaVersion') == 1 and start.get('kind') == 'v080-source-native-start' and
                start.get('platform') == 'linux-x86_64', 'START_RECEIPT_INVALID')
        require(platform.system() == 'Linux' and platform.machine().lower() in ('x86_64', 'amd64'), 'HOST_UNQUALIFIED')
        require(git(root, 'rev-parse', 'HEAD').strip() == start['revision'] and
                git(root, 'rev-parse', 'HEAD^{tree}').strip() == start['tree'], 'SOURCE_CHANGED')
        helper_path = Path(__file__).with_name('verify-runtime-archive.py')
        require(digest(helper_path) == start['sourceSha256'].get('scripts/verify-runtime-archive.py'), 'BINDER_SOURCE_MISMATCH')
        spec = importlib.util.spec_from_file_location('rk_archive_verifier', helper_path)
        verifier = importlib.util.module_from_spec(spec); spec.loader.exec_module(verifier)
        archive = Path(args.archive).resolve(strict=True); checksum = Path(args.checksum).resolve(strict=True)
        spdx_path = Path(args.spdx).resolve(strict=True)
        require(archive.name == 'refactorkit-runtime-' + start['version'] + '-linux-x86_64.zip', 'ASSET_VERSION_MISMATCH')
        try:
            verifier.verify_checksum(archive, checksum)
        except AssertionError:
            raise Refused('ASSET_CHECKSUM_MISMATCH') from None
        members = runtime_members(verifier, archive)
        require('refactorkit/lib/refactorkit-core-' + start['version'] + '.jar' in members, 'ASSET_VERSION_MISMATCH')
        source = source_inventory(root, {archive, checksum, spdx_path}, members)
        require(source == start['sourceSha256'], 'SOURCE_CHANGED')
        try:
            current = toolchain_inventory(Path(start['toolchain']['root']))
        except Refused:
            raise Refused('TOOLCHAIN_CHANGED') from None
        require(current == start['toolchain']['files'], 'TOOLCHAIN_CHANGED')
        require(digest(start['fixtureProfile']['path']) == start['fixtureProfile']['sha256'], 'FIXTURE_PROFILE_CHANGED')
        totals, reports, native, coverage = native_reports(root, start['startedEpochNs'], source)
        archive_sha = digest(archive)
        recovery = packaged_c_recovery(root, args.c_recovery_evidence, start['startedEpochNs'], source, archive_sha, current)
        if recovery:
            native['packaged-c-recovery'] = recovery
        log = regular(args.build_log)
        require(log.stat().st_mtime_ns >= start['startedEpochNs'], 'REPORTS_STALE')
        text = log.read_text()
        require(re.search(r'^BUILD SUCCESSFUL in .+$', text, re.M) and 'BUILD FAILED' not in text and
                re.search(r'^> Task :goldenTest(?:\s|$)', text, re.M), 'BUILD_GATE_UNAVAILABLE')
        spdx = read_json(spdx_path)
        require(spdx.get('spdxVersion') == 'SPDX-2.3' and spdx.get('SPDXID') == 'SPDXRef-DOCUMENT' and
                spdx.get('dataLicense') == 'CC0-1.0' and spdx.get('documentNamespace') and
                spdx.get('creationInfo', {}).get('creators') and spdx.get('packages'), 'SPDX_INVALID')
        require(Path(args.spdx_receipt).is_file(), 'SPDX_QUALIFICATION_MISSING')
        qualification_path = regular(args.spdx_receipt)
        require(qualification_path.stat().st_mtime_ns >= start['startedEpochNs'] and
                spdx_path.stat().st_mtime_ns >= start['startedEpochNs'], 'SPDX_QUALIFICATION_STALE')
        qualification = read_json(qualification_path)
        qualification_sha = digest(qualification_path)
        spdx_helper = Path(__file__).with_name('qualify-release-spdx.py')
        require(digest(spdx_helper) == start['sourceSha256'].get('scripts/qualify-release-spdx.py') and
                digest(root / 'qualification/spdx-2.3/spdx-schema.json') == start['sourceSha256'].get('qualification/spdx-2.3/spdx-schema.json') and
                digest(root / 'qualification/spdx-2.3/provenance.json') == start['sourceSha256'].get('qualification/spdx-2.3/provenance.json'),
                'SPDX_QUALIFIER_SOURCE_MISMATCH')
        # Tool selectors are explicit inputs captured before the build, never executable paths from an SPDX proposal/receipt.
        profile = read_json(start['fixtureProfile']['path'])
        node = profile['selectors']['REFACTORKIT_SCHEMA_NODE']
        ajv = profile['selectors']['REFACTORKIT_SCHEMA_AJV_MODULE']
        require(digest(node) == profile['artifacts']['node']['sha256'] and
                digest(Path(ajv) / 'package.json') == profile['artifacts']['ajv']['packageSha256'], 'SPDX_VALIDATOR_CHANGED')
        spec = importlib.util.spec_from_file_location('rk_spdx_qualifier', spdx_helper)
        qualifier = importlib.util.module_from_spec(spec); spec.loader.exec_module(qualifier)
        component_binding = verify_runtime_components(root, source, args.runtime_components,
                                                      start['startedEpochNs'], members)
        try:
            verified = qualifier.qualify(archive, spdx_path, start['version'], node, ajv, args.upstream_spdx,
                                         args.runtime_components)
        except qualifier.Refused as failure:
            raise Refused(str(failure)) from None
        require(verified == qualification and verified['runtimeMembersSha256'] == members and
                verified['schemaSha256'] == start['sourceSha256']['qualification/spdx-2.3/spdx-schema.json'] and
                verified['schemaProvenanceSha256'] == start['sourceSha256']['qualification/spdx-2.3/provenance.json'],
                'SPDX_QUALIFICATION_MISMATCH')
        service = producer_service(source)
        producer_binding = verified_producer(service, args, start, members)
        value = dict(schemaVersion=1, status='BOUND_EVIDENCE', kind='refactorkit.v080-release-binding/v1',
                     tag=start['tag'], version=start['version'], apiVersion='0.2', platform='linux-x86_64',
                     sourceCommit=start['revision'], sourceTree=start['tree'], sourceSha256=source,
                     sourceStartSha256=digest(start_path), asset={'name': archive.name, 'sha256': archive_sha, 'bytes': archive.stat().st_size},
                     runtimeMembersSha256=members, spdx={'name': spdx_path.name, 'sha256': digest(spdx_path),
                         'validation': 'FULL_SCHEMA_AND_EXACT_FILE_INVENTORY_NOT_LICENSE_REVIEW',
                         'qualificationSha256': qualification_sha, 'schemaSha256': verified['schemaSha256'],
                         'upstreamSpdxSha256': verified['upstreamSpdxSha256'],
                         'producer': producer_binding,
                         'fileCount': verified['fileCount'], 'licenseReview': 'PENDING'},
                     toolchain=start['toolchain'], fixtureProfile=start['fixtureProfile'], testTotals=totals,
                     reportSha256=reports, nativeEvidence=native, cScenarioCoverage=coverage, buildLogSha256=digest(log),
                     wholeReleaseQualified=False, independentReview='PENDING', downloadedArtifactVerification='PENDING',
                     runtimeExecution='SEPARATE_WORKFLOW_ARCHIVE_VERIFICATION', otherHosts='NOT_VERIFIED',
                     workflowRun=os.environ.get('GITHUB_RUN_ID'), workflowAttempt=os.environ.get('GITHUB_RUN_ATTEMPT'))
        if component_binding is not None:
            component_receipt = verified['runtimeComponents']
            require(all(component_binding[key] == component_receipt[key] for key in ('manifestSha256', 'componentCount')),
                    'RUNTIME_COMPONENTS_REPLAY_MISMATCH')
            require(digest(args.runtime_components) == component_binding['manifestSha256'], 'INPUTS_CHANGED_DURING_BINDING')
            value['spdx']['runtimeComponents'] = component_binding
        require(source_inventory(root, {archive, checksum, spdx_path}, members) == source and
                toolchain_inventory(Path(start['toolchain']['root'])) == current and
                digest(start['fixtureProfile']['path']) == start['fixtureProfile']['sha256'], 'INPUTS_CHANGED_DURING_BINDING')
        require(digest(start_path) == value['sourceStartSha256'] and digest(archive) == value['asset']['sha256'] and
                digest(spdx_path) == value['spdx']['sha256'] and digest(log) == value['buildLogSha256'] and
                digest(qualification_path) == qualification_sha and
                digest(args.upstream_spdx) == verified['upstreamSpdxSha256'] and
                verified_producer(service, args, start, members) == producer_binding and
                all(digest(root / name) == image for name, image in reports.items()) and
                all(digest(root / name) == image for group in native.values() for name, image in group.items()),
                'INPUTS_CHANGED_DURING_BINDING')
        atomic_record(args.output, value)
        return {'status': 'BOUND_EVIDENCE', 'assetSha256': value['asset']['sha256'], 'wholeReleaseQualified': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='mode', required=True)
    begin = commands.add_parser('begin')
    finish = commands.add_parser('finish')
    for command in (begin, finish):
        command.add_argument('--root', required=True)
        command.add_argument('--output', required=True)
    for name in ['revision', 'tag', 'toolchain', 'profile']:
        begin.add_argument('--' + name, required=True)
    begin.add_argument('--sbom-producer')
    finish.add_argument('--sbom-producer-receipt')
    finish.add_argument('--runtime-components')
    finish.add_argument('--c-recovery-evidence')
    for name in ['start', 'archive', 'checksum', 'spdx', 'spdx-receipt', 'upstream-spdx', 'build-log']:
        finish.add_argument('--' + name, required=True)
    args = parser.parse_args()
    try:
        require(sys.version_info >= (3, 11), 'PYTHON_VERSION_UNQUALIFIED')
        result = getattr(ReleaseEvidenceBinding(), args.mode)(args)
        print(json.dumps(result))
        return 0
    except (Refused, OSError, ValueError, KeyError, TypeError, ET.ParseError, zipfile.BadZipFile,
            subprocess.SubprocessError, AssertionError) as failure:
        code = str(failure) if isinstance(failure, Refused) else 'BINDING_INPUT_UNAVAILABLE'
        print(json.dumps({'status': 'REFUSED', 'code': code}), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
