#!/usr/bin/env python3
"""Assemble and independently verify SPDX2.3 schema and exact distribution inventory.

This is release metadata qualification, not native-runtime execution or a license review.
"""
import argparse
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys
import tempfile
from urllib.parse import quote
import zipfile

SCHEMA_SHA256 = '239208b7ac287b3cf5d9a9af23f9d69863971102a5e1587a27a398b43490b89b'
SCHEMA_COMMIT = 'aadf3b0b8dbbabdb4d880b0fc714255fea436ff7'
ROOT_PREFIX = 'SPDXRef-RefactorKitRuntime-'
INVENTORY_KIND = 'refactorkit.archive-inventory/v1'
MAX_JSON = 32 * 1024 * 1024


class Refused(RuntimeError):
    pass


def require(value, code):
    if not value:
        raise Refused(code)


def regular(path, limit=MAX_JSON):
    path = Path(path).absolute()
    require(path.is_file() and not path.is_symlink() and
            all(not p.is_symlink() for p in path.parents), 'INPUT_FILE_UNAVAILABLE')
    require(path.stat().st_size <= limit, 'INPUT_SIZE_LIMIT')
    return path


def digest(path, limit=1024 * 1024 * 1024):
    with regular(path, limit).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def read_json(path):
    return read_json_bytes(regular(path).read_bytes())


def read_json_bytes(data):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'DUPLICATE_JSON_FIELD')
            result[key] = value
        return result
    return json.loads(data, object_pairs_hook=unique,
                      parse_constant=lambda _: (_ for _ in ()).throw(Refused('INVALID_JSON_NUMBER')))


def atomic_record(path, value):
    path = Path(path).absolute()
    require(not path.exists() and not path.is_symlink(), 'OUTPUT_EXISTS')
    require(all(not p.is_symlink() for p in path.parents), 'OUTPUT_SYMLINK_ANCESTOR')
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix='.' + path.name + '-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'w') as output:
            json.dump(value, output, indent=2); output.write('\n'); output.flush(); os.fsync(output.fileno())
        os.link(temporary, path)
    finally:
        os.unlink(temporary)


def schema_inputs():
    root = Path(__file__).resolve().parent.parent / 'qualification/spdx-2.3'
    schema = regular(root / 'spdx-schema.json', 128 * 1024)
    provenance = read_json(root / 'provenance.json')
    require(digest(schema) == SCHEMA_SHA256 and provenance.get('commit') == SCHEMA_COMMIT and
            provenance.get('schemaSha256') == SCHEMA_SHA256 and provenance.get('license') == 'CC-BY-3.0' and
            provenance.get('modified') is False and provenance.get('attribution') and
            hashlib.sha256(provenance['licenseText'].encode()).hexdigest() ==
                '017e38491cccbd2bdb6da0a32a33db9ec245b5dab30fdcd09f2c742c975e5b35', 'SPDX_SCHEMA_PROVENANCE_INVALID')
    return schema, digest(root / 'provenance.json')


def tool_facts(node, ajv_module):
    executable = regular(Path(node).resolve(strict=True), 256 * 1024 * 1024)
    module = Path(ajv_module).resolve(strict=True)
    package = read_json(module / 'package.json')
    require(package.get('name') == 'ajv' and package.get('version') == '6.15.0', 'SPDX_VALIDATOR_UNQUALIFIED')
    # Bind the installed package and its declared dependency closure, without npm/network execution.
    pending = [module]; images = {}; visited = set()
    while pending:
        current = pending.pop()
        if current in visited:
            continue
        visited.add(current); require(len(visited) <= 32, 'SPDX_VALIDATOR_LIMIT')
        metadata = read_json(current / 'package.json')
        for dependency in metadata.get('dependencies', {}):
            require(re.fullmatch(r'(?:@[A-Za-z0-9_.-]+/)?[A-Za-z0-9_.-]+', dependency), 'SPDX_VALIDATOR_UNQUALIFIED')
            pending.append(module.parent / dependency)
        for path in sorted(current.rglob('*')):
            require(not path.is_symlink(), 'SPDX_VALIDATOR_UNQUALIFIED')
            if path.is_file():
                require(len(images) < 4096, 'SPDX_VALIDATOR_LIMIT')
                images[str(path)] = digest(path, 16 * 1024 * 1024)
    return {'node': {'path': str(executable), 'sha256': digest(executable)},
            'ajv': {'path': str(module), 'version': '6.15.0', 'packageSha256': digest(module / 'package.json'),
                    'closureSha256': hashlib.sha256(json.dumps(images, sort_keys=True).encode()).hexdigest()}}


NODE_VALIDATOR = r"""
const fs=require('fs');
try {
 const Ajv=require(process.argv[1]);
 const version=require(process.argv[1]+'/package.json').version;
 if(version!=='6.15.0'||process.version!=='v22.18.0') throw Error('unqualified validator');
 const ajv=new Ajv({allErrors:true,jsonPointers:true,schemaId:'auto',coerceTypes:false,useDefaults:false,removeAdditional:false});
 const schema=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
 if(schema.$schema!=='http://json-schema.org/draft-07/schema#'||!ajv.validateSchema(schema)) throw Error('schema');
 const control=ajv.compile({if:{properties:{gate:{const:true}},required:['gate']},then:{required:['proof']}});
 if(control({gate:true})||!control({gate:true,proof:true})) throw Error('Draft7 control');
 const validate=ajv.compile(schema);
 const valid=validate(JSON.parse(fs.readFileSync(process.argv[3],'utf8')));
 process.stdout.write(JSON.stringify({valid,draft:'draft-07',node:process.version,ajv:version}));
} catch(error) { process.stdout.write(JSON.stringify({valid:false,unavailable:true})); process.exitCode=2; }
"""


def validate_schema(document, schema, tools):
    with tempfile.TemporaryDirectory(prefix='refactorkit-spdx-schema-') as temporary:
        candidate = Path(temporary) / 'document.json'
        candidate.write_text(json.dumps(document))
        with tempfile.TemporaryFile() as output, tempfile.TemporaryFile() as error:
            try:
                result = subprocess.run([tools['node']['path'], '--max-old-space-size=256', '-e', NODE_VALIDATOR,
                    tools['ajv']['path'], str(schema), str(candidate)], cwd=temporary,
                    env={'PATH': '', 'LANG': 'C.UTF-8', 'LC_ALL': 'C.UTF-8'},
                    stdout=output, stderr=error, timeout=30)
            except subprocess.TimeoutExpired:
                raise Refused('SPDX_VALIDATOR_TIMEOUT') from None
            require(output.tell() <= 4096 and error.tell() <= 16384, 'SPDX_VALIDATOR_OUTPUT_LIMIT')
            output.seek(0); value = json.loads(output.read())
            require(result.returncode == 0 and not value.get('unavailable'), 'SPDX_VALIDATOR_UNAVAILABLE')
            require(value.get('valid') is True and value.get('draft') == 'draft-07', 'SPDX_SCHEMA_INVALID')


def archive_inventory(path):
    regular(path, 1024 * 1024 * 1024)
    helper = Path(__file__).with_name('verify-runtime-archive.py')
    spec = importlib.util.spec_from_file_location('rk_spdx_archive', helper)
    verifier = importlib.util.module_from_spec(spec); spec.loader.exec_module(verifier)
    result = {}
    try:
        with zipfile.ZipFile(path) as archive:
            entries = verifier.safe_entries(archive)
            for name, item in sorted(entries.items()):
                require(PurePosixPath(name).as_posix() == name.rstrip('/'), 'SPDX_ARCHIVE_PATH_INVALID')
                mode = stat.S_IFMT(item.external_attr >> 16)
                require(mode in (0, stat.S_IFDIR if item.is_dir() else stat.S_IFREG), 'SPDX_ARCHIVE_PATH_INVALID')
                if item.is_dir():
                    continue
                sha256 = hashlib.sha256(); sha1 = hashlib.sha1()
                with archive.open(item) as source:
                    for block in iter(lambda: source.read(65536), b''):
                        sha256.update(block); sha1.update(block)
                result[name] = {'SHA256': sha256.hexdigest(), 'SHA1': sha1.hexdigest()}
        require(result, 'SPDX_INVENTORY_INCOMPLETE')
        return result
    except (AssertionError, zipfile.BadZipFile, UnicodeError, EOFError):
        raise Refused('SPDX_ARCHIVE_INVALID') from None


def graph(document):
    require(document.get('SPDXID') == 'SPDXRef-DOCUMENT' and document.get('spdxVersion') == 'SPDX-2.3' and
            document.get('dataLicense') == 'CC0-1.0', 'SPDX_SCHEMA_INVALID')
    elements = {'SPDXRef-DOCUMENT': document}
    for kind in ('packages', 'files', 'snippets'):
        for item in document.get(kind, []):
            identity = item['SPDXID']
            require(re.fullmatch(r'SPDXRef-[A-Za-z0-9.-]+', identity), 'SPDX_ID_INVALID')
            require(identity not in elements, 'SPDX_ID_DUPLICATE')
            require(len(elements) < 100000, 'SPDX_GRAPH_LIMIT')
            elements[identity] = item
    def reference(identity):
        require(identity in elements or identity in ('NONE', 'NOASSERTION'), 'SPDX_REFERENCE_UNAVAILABLE')
    for identity in document.get('documentDescribes', []):
        reference(identity)
    for relation in document.get('relationships', []):
        reference(relation['spdxElementId']); reference(relation['relatedSpdxElement'])
    for item in document.get('packages', []):
        for identity in item.get('hasFiles', []):
            reference(identity)
    for item in document.get('files', []):
        for identity in item.get('fileDependencies', []):
            reference(identity)
    for item in document.get('snippets', []):
        reference(item['snippetFromFile'])
    return elements


def verification_code(inventory):
    return hashlib.sha1(''.join(sorted(item['SHA1'] for item in inventory.values())).encode('ascii')).hexdigest()


def root_id(archive_sha):
    return ROOT_PREFIX + archive_sha


def resolved_components(manifest, inventory, version):
    invalid = 'SPDX_RUNTIME_COMPONENTS_INVALID'
    require(isinstance(manifest, dict) and
            set(manifest) == {'kind', 'sourceVersion', 'configuration', 'components'} and
            manifest['kind'] == 'refactorkit.resolved-runtime-components/v1' and
            manifest['sourceVersion'] == version and
            manifest['configuration'] == ':modules:refactorkit-cli:runtimeClasspath', invalid)
    rows = manifest['components']
    require(isinstance(rows, list) and 0 < len(rows) <= 1024, invalid)
    application_jars = {name.removeprefix('refactorkit/'): hashes for name, hashes in inventory.items()
                        if re.fullmatch(r'refactorkit/lib/[^/]+\.jar', name)}
    by_path = {}
    for row in rows:
        require(isinstance(row, dict) and set(row) == {'path', 'coordinates', 'sha256', 'sha1'}, invalid)
        path = row['path']
        require(isinstance(path, str) and re.fullmatch(r'lib/[A-Za-z0-9_.+-]+\.jar', path) and
                path not in by_path, invalid)
        coords = row['coordinates']
        require(isinstance(coords, dict) and coords.get('kind') in ('module', 'project'), invalid)
        fields = {'kind', 'group', 'artifact', 'version'}
        if coords['kind'] == 'project':
            fields.add('projectPath')
        require(set(coords) == fields and all(
            isinstance(coords[key], str) and re.fullmatch(r'[A-Za-z0-9_.+-]{1,512}', coords[key])
            for key in ('group', 'artifact', 'version')), invalid)
        if coords['kind'] == 'project':
            require(coords['version'] == version and isinstance(coords['projectPath'], str) and
                    re.fullmatch(r'(?::[A-Za-z0-9_.-]+){1,32}', coords['projectPath']), invalid)
        by_path[path] = row
    require(by_path.keys() == application_jars.keys(), 'SPDX_RUNTIME_COMPONENTS_INCOMPLETE')
    for path, row in by_path.items():
        hashes = application_jars[path]
        require(row['sha256'] == hashes['SHA256'] and row['sha1'] == hashes['SHA1'],
                'SPDX_RUNTIME_COMPONENT_DIGEST_MISMATCH')
    return by_path


def add_runtime_coordinates(result, inventory, manifest, version, distribution):
    by_path = resolved_components(manifest, inventory, version)
    packages = result['packages']
    detected = {}
    for package in packages:
        source = package.get('sourceInfo', '')
        for checksum in package.get('checksums', []):
            if checksum['algorithm'] == 'SHA1':
                key = (source, checksum['checksumValue'])
                detected.setdefault(key, []).append(package)

    for path, row in sorted(by_path.items()):
        coords = row['coordinates']
        group, artifact, component_version = (quote(coords[key], safe='') for key in ('group', 'artifact', 'version'))
        purl = {'referenceCategory': 'PACKAGE-MANAGER', 'referenceType': 'purl',
                'referenceLocator': f'pkg:maven/{group}/{artifact}@{component_version}'}
        source = 'acquired package info from installed java archive: /' + path
        matches = detected.get((source, row['sha1']), [])
        require(len(matches) <= 1, 'SPDX_RUNTIME_COMPONENT_AMBIGUOUS')
        if matches:
            package = matches[0]
            require(all(check['algorithm'] not in ('SHA1', 'SHA256') or
                        check['checksumValue'] == row[check['algorithm'].lower()]
                        for check in package['checksums']), 'SPDX_RUNTIME_COMPONENT_DIGEST_MISMATCH')
            refs = package.get('externalRefs', [])
            package['externalRefs'] = [ref for ref in refs if ref['referenceType'] != 'purl'] + [purl]
        else:
            identity = 'SPDXRef-RefactorKitResolvedJar-' + hashlib.sha256(path.encode()).hexdigest()
            package = {'SPDXID': identity, 'name': coords['artifact'], 'versionInfo': coords['version'],
                       'packageFileName': path, 'downloadLocation': 'NOASSERTION', 'filesAnalyzed': False,
                       'licenseConcluded': 'NOASSERTION', 'licenseDeclared': 'NOASSERTION',
                       'copyrightText': 'NOASSERTION', 'sourceInfo': 'Gradle runtime resolution: ' + path,
                       'checksums': [{'algorithm': algorithm, 'checksumValue': row[algorithm.lower()]}
                                     for algorithm in ('SHA256', 'SHA1')], 'externalRefs': [purl]}
            packages.append(package)
        relation = {'spdxElementId': distribution, 'relatedSpdxElement': package['SPDXID'],
                    'relationshipType': 'CONTAINS'}
        result['relationships'].append(relation)
    result['creationInfo']['creators'].append('Tool: RefactorKit Gradle component reconciliation v1')


def component_input(path):
    if path is None:
        return None
    data = regular(path, 1024 * 1024).read_bytes()
    return read_json_bytes(data), hashlib.sha256(data).hexdigest()


def assemble(document, inventory, archive, version, archive_sha, upstream_sha, components=None):
    graph(document)
    require(document.get('packages'), 'SPDX_COMPONENT_METADATA_MISSING')
    require(not any(p['SPDXID'].startswith(ROOT_PREFIX) for p in document.get('packages', [])), 'SPDX_ALREADY_HAS_INVENTORY')
    result = copy.deepcopy(document); identity = root_id(archive_sha); files = []
    for name, hashes in inventory.items():
        files.append({'SPDXID': identity + '-File-' + hashlib.sha256(name.encode()).hexdigest(),
            'fileName': './' + name, 'checksums': [{'algorithm': k, 'checksumValue': v} for k, v in hashes.items()],
            'licenseConcluded': 'NOASSERTION', 'copyrightText': 'NOASSERTION'})
    package = {'SPDXID': identity, 'name': 'RefactorKit runtime', 'versionInfo': version,
        'packageFileName': archive.name, 'downloadLocation': 'NOASSERTION', 'filesAnalyzed': True,
        'checksums': [{'algorithm': 'SHA256', 'checksumValue': archive_sha}],
        'packageVerificationCode': {'packageVerificationCodeValue': verification_code(inventory)},
        'licenseConcluded': 'NOASSERTION', 'licenseDeclared': 'NOASSERTION',
        'sourceInfo': INVENTORY_KIND, 'comment': 'Upstream SPDX SHA256=' + upstream_sha,
        'hasFiles': [f['SPDXID'] for f in files]}
    result.setdefault('packages', []).append(package); result.setdefault('files', []).extend(files)
    result.setdefault('documentDescribes', []).append(identity)
    relations = result.setdefault('relationships', [])
    relations.append({'spdxElementId': 'SPDXRef-DOCUMENT', 'relatedSpdxElement': identity, 'relationshipType': 'DESCRIBES'})
    relations.extend({'spdxElementId': identity, 'relatedSpdxElement': f['SPDXID'], 'relationshipType': 'CONTAINS'} for f in files)
    creator = 'Tool: RefactorKit SPDX inventory qualifier v1'
    if creator not in result['creationInfo']['creators']:
        result['creationInfo']['creators'].append(creator)
    if components is not None:
        manifest, manifest_sha = components
        add_runtime_coordinates(result, inventory, manifest, version, identity)
        package['comment'] += '\nGradle component manifest SHA256=' + manifest_sha
    return result


def verify_inventory(document, inventory, archive, version, archive_sha):
    elements = graph(document); identity = root_id(archive_sha)
    packages = [p for p in document.get('packages', []) if p.get('sourceInfo') == INVENTORY_KIND]
    require(len(packages) == 1 and packages[0]['SPDXID'] == identity, 'SPDX_INVENTORY_INCOMPLETE')
    package = packages[0]
    require(package.get('versionInfo') == version and package.get('packageFileName') == archive.name and
            package.get('filesAnalyzed') is True and package.get('checksums') == [{'algorithm': 'SHA256', 'checksumValue': archive_sha}],
            'SPDX_ASSET_IDENTITY_MISMATCH')
    file_ids = package.get('hasFiles', [])
    require(len(file_ids) == len(set(file_ids)) == len(inventory), 'SPDX_INVENTORY_INCOMPLETE')
    observed = {}
    for file_id in file_ids:
        item = elements[file_id]; name = item.get('fileName', '')
        require(name.startswith('./') and name[2:] in inventory and name[2:] not in observed, 'SPDX_INVENTORY_INCOMPLETE')
        name = name[2:]; checksums = item.get('checksums', [])
        require(len({c['algorithm'] for c in checksums}) == len(checksums), 'SPDX_FILE_DIGEST_MISMATCH')
        by_algorithm = {c['algorithm']: c['checksumValue'] for c in checksums}
        require(all(by_algorithm.get(k) == value for k, value in inventory[name].items()), 'SPDX_FILE_DIGEST_MISMATCH')
        observed[name] = inventory[name]['SHA256']
    require(set(observed) == set(inventory), 'SPDX_INVENTORY_INCOMPLETE')
    relations = {(r['spdxElementId'], r['relationshipType'], r['relatedSpdxElement']) for r in document.get('relationships', [])}
    require(identity in document.get('documentDescribes', []) and
            ('SPDXRef-DOCUMENT', 'DESCRIBES', identity) in relations and
            all((identity, 'CONTAINS', i) in relations for i in file_ids), 'SPDX_INVENTORY_INCOMPLETE')
    require(package.get('packageVerificationCode') == {'packageVerificationCodeValue': verification_code(inventory)},
            'SPDX_VERIFICATION_CODE_MISMATCH')
    return observed


def qualify(archive, spdx, version, node, ajv_module, upstream_spdx, runtime_components=None):
    require(sys.flags.optimize == 0, 'OPTIMIZED_PYTHON_UNQUALIFIED')
    require(re.fullmatch(r'\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?', version), 'SPDX_VERSION_INVALID')
    schema, provenance_sha = schema_inputs(); tools = tool_facts(node, ajv_module)
    archive = regular(archive, 1024 * 1024 * 1024); spdx = regular(spdx)
    archive_sha = digest(archive); spdx_sha = digest(spdx)
    upstream_sha = digest(upstream_spdx); upstream = read_json(upstream_spdx)
    validate_schema(upstream, schema, tools)
    document = read_json(spdx); validate_schema(document, schema, tools)
    inventory = archive_inventory(archive)
    observed = verify_inventory(document, inventory, archive, version, archive_sha)
    components = component_input(runtime_components)
    require(document == assemble(upstream, inventory, archive, version, archive_sha, upstream_sha, components),
            'SPDX_COMPONENT_METADATA_CHANGED')
    require(digest(archive) == archive_sha and digest(spdx) == spdx_sha and
            digest(upstream_spdx) == upstream_sha and
            schema_inputs() == (schema, provenance_sha) and tool_facts(node, ajv_module) == tools, 'SPDX_INPUTS_CHANGED')
    require(components == component_input(runtime_components), 'SPDX_INPUTS_CHANGED')
    receipt = {'schemaVersion': 1, 'status': 'VERIFIED_SPDX_SCHEMA_AND_FILE_INVENTORY',
        'kind': 'refactorkit.release-spdx-qualification/v1', 'assetName': archive.name,
        'assetSha256': archive_sha, 'spdxSha256': spdx_sha, 'upstreamSpdxSha256': upstream_sha,
        'runtimeMembersSha256': observed,
        'fileCount': len(observed), 'packageCount': len(document.get('packages', [])),
        'schemaSha256': SCHEMA_SHA256, 'schemaProvenanceSha256': provenance_sha,
        'producerSha256': digest(__file__), 'tools': tools, 'licenseReview': 'PENDING',
        'nativeRuntimeExecution': 'SEPARATE_GATE', 'wholeReleaseQualified': False}
    if components is not None:
        manifest, manifest_sha = components
        receipt['runtimeComponents'] = {'basis': 'SUPPLIED_GRADLE_COMPONENT_MANIFEST',
                                        'manifestSha256': manifest_sha,
                                        'componentCount': len(manifest['components'])}
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['assemble', 'verify'])
    for name in ['archive', 'spdx', 'version', 'node', 'ajv-module']:
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--output'); parser.add_argument('--receipt'); parser.add_argument('--upstream-spdx')
    parser.add_argument('--runtime-components', help='Resolve SPDX component metadata against a supplied Gradle manifest')
    args = parser.parse_args()
    try:
        if args.mode == 'assemble':
            require(args.output, 'OUTPUT_REQUIRED'); schema, provenance = schema_inputs()
            tools = tool_facts(args.node, args.ajv_module); archive = regular(args.archive, 1024 * 1024 * 1024)
            archive_sha = digest(archive); spdx_sha = digest(args.spdx); document = read_json(args.spdx)
            validate_schema(document, schema, tools)
            inventory = archive_inventory(archive)
            components = component_input(args.runtime_components)
            complete = assemble(document, inventory, archive, args.version, archive_sha, spdx_sha, components)
            validate_schema(complete, schema, tools)
            verify_inventory(complete, inventory, archive, args.version, archive_sha)
            require(digest(archive) == archive_sha and digest(args.spdx) == spdx_sha and
                    schema_inputs() == (schema, provenance) and tool_facts(args.node, args.ajv_module) == tools, 'SPDX_INPUTS_CHANGED')
            require(components == component_input(args.runtime_components), 'SPDX_INPUTS_CHANGED')
            atomic_record(args.output, complete)
            print(json.dumps({'status': 'ASSEMBLED_SPDX_INVENTORY', 'fileCount': len(inventory)}))
        else:
            require(args.receipt, 'OUTPUT_REQUIRED')
            require(args.upstream_spdx, 'UPSTREAM_SPDX_REQUIRED')
            result = qualify(args.archive, args.spdx, args.version, args.node, args.ajv_module,
                             args.upstream_spdx, args.runtime_components)
            atomic_record(args.receipt, result)
            print(json.dumps({'status': result['status'], 'fileCount': result['fileCount']}))
        return 0
    except Refused as failure:
        print(json.dumps({'code': str(failure)}), file=sys.stderr); return 1
    except (OSError, ValueError, KeyError, TypeError, RecursionError):
        print(json.dumps({'code': 'SPDX_QUALIFICATION_UNAVAILABLE'}), file=sys.stderr); return 1


if __name__ == '__main__':
    raise SystemExit(main())
