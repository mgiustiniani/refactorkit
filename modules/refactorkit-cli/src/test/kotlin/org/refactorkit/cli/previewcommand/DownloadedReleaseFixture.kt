package org.refactorkit.cli.previewcommand

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Exercise the production verifier with an authored attestation oracle and tiny executable archive. */
internal class DownloadedReleaseFixture(private val condition: String) : AutoCloseable {
    private val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
    private val root = Files.createTempDirectory("rk-download-fixture-")
    private val assets = Files.createDirectory(root.resolve("assets"))
    private val receipt = root.resolve("receipt.json")
    private val nativeMarker = root.resolve("ran")
    private val attestationLog = root.resolve("attestation-calls")
    private val gh = root.resolve("attestation-oracle")
    private val result = root.resolve("result.log")
    private val revision = "a".repeat(40)
    private lateinit var images: Map<String, String>
    private var exit = -1

    fun execute() {
        val create = root.resolve("create.py")
        Files.writeString(create, archiveFixture)
        assertEquals(0, run(listOf("python3", create.toString(), repository.toString(), root.toString(),
            condition, revision)), Files.readString(result))
        Files.writeString(gh, attestationOracle)
        assertTrue(gh.toFile().setExecutable(true))
        images = image()
        exit = run(listOf("python3", repository.resolve("scripts/verify-v080-downloaded-release.py").toString(),
            "--asset-dir", assets.toString(), "--tag", "v0.8.0", "--revision", revision,
            "--repo", "example/refactorkit", "--gh", gh.toString(), "--receipt", receipt.toString()))
    }

    fun verify(expected: String) {
        val text = Files.readString(result).trim()
        if (expected != "verified asset set") {
            assertEquals(1, exit, text)
            assertEquals(expected, Json.parseToJsonElement(text).jsonObject["code"]?.jsonPrimitive?.content)
            return
        }
        assertEquals(0, exit, text)
        val verified = Json.parseToJsonElement(Files.readString(receipt)).jsonObject
        assertEquals("VERIFIED_DOWNLOADED_ASSET_SET", verified.getValue("status").jsonPrimitive.content)
        assertEquals(revision, verified.getValue("sourceCommit").jsonPrimitive.content)
        assertEquals("CLI_VERSION_AND_MCP_STDIO", verified.getValue("runtimeSmoke").jsonPrimitive.content)
        assertEquals(6, verified.getValue("runtimeFilesVerified").jsonPrimitive.int)
        assertEquals(images, verified.getValue("sha256").jsonObject.mapValues { it.value.jsonPrimitive.content })
        assertFalse(verified.getValue("wholeReleaseQualified").jsonPrimitive.boolean)
    }

    fun verifySideEffects() {
        val accepted = condition == "matched source and signed asset set"
        val runtimeStarted = accepted || condition == "runtime smoke refused"
        assertEquals(accepted, Files.isRegularFile(receipt))
        assertEquals(runtimeStarted, Files.isRegularFile(nativeMarker))
        assertEquals(images, image())
        val calls = if (Files.isRegularFile(attestationLog)) Files.readAllLines(attestationLog) else emptyList()
        val required = when (condition) {
            "wrong archive checksum", "release evidence binds another revision" -> 0
            "provenance verification refused" -> 1
            else -> 3
        }
        assertEquals(required, calls.size)
        for (raw in calls) {
            val argv = Json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf("attestation", "verify"), argv.take(2))
            assertEquals("example/refactorkit", argv[argv.indexOf("--repo") + 1])
            assertEquals("example/refactorkit/.github/workflows/release.yml", argv[argv.indexOf("--signer-workflow") + 1])
            assertEquals(revision, argv[argv.indexOf("--source-digest") + 1])
            assertEquals("refs/tags/v0.8.0", argv[argv.indexOf("--source-ref") + 1])
            assertEquals("github.com", argv[argv.indexOf("--hostname") + 1])
            assertTrue("--deny-self-hosted-runners" in argv)
        }
        if (required == 3) {
            val argv = calls.map { raw -> Json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content } }
            assertEquals(listOf("https://slsa.dev/provenance/v1", "https://slsa.dev/provenance/v1", "https://spdx.dev/Document/v2.3"),
                argv.map { it[it.indexOf("--predicate-type") + 1] })
            assertTrue(argv[0][2].endsWith(".zip"))
            assertTrue(argv[1][2].endsWith(".release.json"))
            assertEquals(argv[0][2], argv[2][2])
        }
        if (accepted) {
            verifyPublicationHandoff()
            verifyWorkflowRouting()
        }
    }

    private fun verifyPublicationHandoff() {
        val candidate = Files.createDirectory(root.resolve("candidate"))
        Files.list(assets).use { paths -> paths.forEach { Files.copy(it, candidate.resolve(it.fileName)) } }
        val command = listOf("python3", repository.resolve("scripts/verify-v080-downloaded-release.py").toString(),
            "--asset-dir", candidate.toString(), "--tag", "v0.8.0", "--revision", revision,
            "--repo", "example/refactorkit", "--against-receipt", receipt.toString())
        assertEquals(0, run(command), Files.readString(result))
        val sbom = candidate.resolve("refactorkit-runtime-linux-x86_64.spdx.json")
        Files.writeString(sbom, Files.readString(sbom) + "\n")
        assertEquals(1, run(command))
        assertContains(Files.readString(result), "DOWNLOAD_REVIEW_RECEIPT_MISMATCH")
    }

    private fun verifyWorkflowRouting() {
        val yaml = Class.forName("org.yaml.snakeyaml.Yaml").getConstructor().newInstance()
        val text = Files.readString(repository.resolve(".github/workflows/release.yml"))
        val workflow = yaml.javaClass.getMethod("load", String::class.java).invoke(yaml, text) as Map<*, *>
        val jobs = workflow["jobs"] as Map<*, *>
        val review = jobs["review-v080-download"] as Map<*, *>
        assertEquals(mapOf("contents" to "read", "attestations" to "read"), review["permissions"])
        assertEquals("build-runtime", review["needs"])
        assertEquals("startsWith(github.ref_name, 'v0.8.')", review["if"])
        val publish = jobs["publish"] as Map<*, *>
        assertEquals(listOf("build-runtime", "review-v080-download"), publish["needs"])
        assertContains(publish["if"].toString(), "needs.review-v080-download.result == 'success'")
        val steps = (publish["steps"] as List<*>).map { it as Map<*, *> }
        val compare = steps.indexOfFirst { it["run"].toString().contains("--against-receipt") }
        val draft = steps.indexOfFirst { it["uses"] == "softprops/action-gh-release@v2" }
        assertTrue(compare in 0 until draft)
    }

    private fun run(command: List<String>): Int {
        val child = ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
            .redirectOutput(result.toFile()).apply { environment()["GH_TOKEN"] = "synthetic-test-token" }.start()
        if (!child.waitFor(60, TimeUnit.SECONDS)) {
            child.toHandle().descendants().use { it.forEach { process -> process.destroyForcibly() } }
            child.destroyForcibly()
            child.waitFor(10, TimeUnit.SECONDS)
            error("Downloaded release fixture timed out")
        }
        return child.exitValue()
    }

    private fun image(): Map<String, String> = Files.list(assets).use { paths ->
        paths.toList().associate { path ->
            path.fileName.toString() to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
                .joinToString("") { "%02x".format(it) }
        }
    }

    override fun close() { root.toFile().deleteRecursively() }

    companion object {
        private val archiveFixture = """
            import hashlib, importlib.util, json, shlex, stat, sys, zipfile
            from pathlib import Path
            repository, directory, mode, revision = sys.argv[1:]
            directory = Path(directory)
            assets = directory / 'assets'
            name = 'refactorkit-runtime-0.8.0-linux-x86_64'
            archive = assets / (name + '.zip')
            launcher_version = '0.7.0' if mode == 'runtime smoke refused' else '0.8.0'
            marker = shlex.quote(str(directory / 'ran'))
            cli = '#!/bin/sh\n[ -z "${'$'}GH_TOKEN" ] || exit 9\n[ "${'$'}1" = version ] || exit 11\n' + f'touch {marker}\necho "RefactorKit {launcher_version} (API 0.2)"\n'
            mcp = '''#!/usr/bin/python3
            import json, sys
            for line in sys.stdin:
                request = json.loads(line)
                if 'id' not in request:
                    continue
                if request['method'] == 'initialize':
                    result = {'protocolVersion': '2024-11-05', 'serverInfo': {'name': 'refactorkit'}}
                else:
                    result = {'tools': [{'name': n} for n in ('project_scan','preview_refactoring','apply_refactoring','rollback_refactoring')]}
                print(json.dumps({'jsonrpc':'2.0','id':request['id'],'result':result}), flush=True)
            '''
            elf = bytearray(64)
            elf[:6] = b'\x7fELF\x02\x01'
            elf[18] = 62
            members = {
                'bin/refactorkit': cli.encode(),
                'bin/refactorkit-daemon': b'#!/bin/sh\nexit 1\n',
                'bin/refactorkit-mcp': mcp.encode(),
                'lib/refactorkit-core-0.8.0.jar': b'fixture',
                'runtime/bin/java': bytes(elf),
                'runtime/release': b'MODULES="java.base java.compiler java.logging java.xml jdk.unsupported jdk.zipfs"\n',
            }
            with zipfile.ZipFile(archive, 'w') as package:
                for path, data in members.items():
                    entry = zipfile.ZipInfo('refactorkit/' + path, (1980, 2, 1, 0, 0, 0))
                    entry.external_attr = (stat.S_IFREG | (0o755 if 'bin/' in path else 0o644)) << 16
                    package.writestr(entry, data)
            sha = hashlib.sha256(archive.read_bytes()).hexdigest()
            published_checksum = '0' * 64 if mode == 'wrong archive checksum' else sha
            (assets / (name + '.zip.sha256')).write_text(published_checksum + '  ' + archive.name + '\n')
            spec = importlib.util.spec_from_file_location('spdx', Path(repository) / 'scripts/qualify-release-spdx.py')
            spdx = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(spdx)
            inventory = spdx.archive_inventory(archive)
            raw = {'SPDXID':'SPDXRef-DOCUMENT','spdxVersion':'SPDX-2.3','dataLicense':'CC0-1.0',
                   'name':'Fixture SPDX','documentNamespace':'https://example.invalid/spdx',
                   'creationInfo':{'created':'2026-11-19T00:00:00Z','creators':['Tool: fixture']},
                   'packages':[{'SPDXID':'SPDXRef-Fixture','name':'fixture','downloadLocation':'NOASSERTION'}]}
            document = spdx.assemble(raw, inventory, archive, '0.8.0', sha, 'b'*64)
            spdx_path = assets / 'refactorkit-runtime-linux-x86_64.spdx.json'
            spdx_path.write_text(json.dumps(document))
            binding = {'kind':'refactorkit.v080-release-binding/v1','status':'BOUND_EVIDENCE',
                       'tag':'v0.8.0','version':'0.8.0','platform':'linux-x86_64','apiVersion':'0.2',
                       'sourceCommit': ('b'*40 if mode == 'release evidence binds another revision' else revision),
                       'asset': {'name':archive.name,'sha256':sha,'bytes':archive.stat().st_size},
                       'spdx': {'name':spdx_path.name,'sha256':hashlib.sha256(spdx_path.read_bytes()).hexdigest(),
                                'fileCount':len(members),'runtimeComponents':{'basis':'SOURCE_OWNED_GRADLE_REPLAY'}},
                       'runtimeMembersSha256':{name: hashes['SHA256'] for name, hashes in inventory.items()}}
            (assets / (name + '.release.json')).write_text(json.dumps(binding))
            (directory / 'mode').write_text(mode)
        """.trimIndent()

        private val attestationOracle = """
            #!/usr/bin/env python3
            import hashlib, json, os, sys
            from pathlib import Path
            assert os.environ.get('GH_TOKEN') == 'synthetic-test-token'
            directory = Path(__file__).parent
            args = sys.argv[1:]
            with (directory / 'attestation-calls').open('a') as log:
                log.write(json.dumps(args) + '\n')
            mode = (directory / 'mode').read_text()
            if mode == 'provenance verification refused':
                sys.exit(1)
            subject = Path(args[2])
            kind = args[args.index('--predicate-type') + 1]
            predicate = {}
            if kind == 'https://spdx.dev/Document/v2.3':
                predicate = json.loads((subject.parent / 'refactorkit-runtime-linux-x86_64.spdx.json').read_text())
                if mode == 'different signed SPDX predicate':
                    predicate['name'] = 'another sbom'
            statement = {'predicateType':kind,'predicate':predicate,
                         'subject':[{'name':subject.name,'digest':{'sha256':hashlib.sha256(subject.read_bytes()).hexdigest()}}]}
            print(json.dumps([{'verificationResult':{'statement':statement}}]))
        """.trimIndent()
    }
}
