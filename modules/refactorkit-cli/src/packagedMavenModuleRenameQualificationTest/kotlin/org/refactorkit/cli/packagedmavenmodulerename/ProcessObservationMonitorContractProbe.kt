package org.refactorkit.cli.packagedmavenmodulerename

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class ProcessObservationMonitorProbeEvidence(
    val cleanPathAccepted: Boolean,
    val liveDescendantRejected: Boolean,
    val liveDescendantIdentityRecorded: Boolean,
    val rootTerminated: Boolean,
    val descendantTerminated: Boolean,
    val survivorCount: Int,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("cleanPathAccepted", cleanPathAccepted)
        put("liveDescendantRejected", liveDescendantRejected)
        put("liveDescendantIdentityRecorded", liveDescendantIdentityRecorded)
        put("rootTerminated", rootTerminated)
        put("descendantTerminated", descendantTerminated)
        put("survivorCount", survivorCount)
    }
}

internal object ProcessObservationMonitorContractProbe {
    @Volatile private var retainedEvidence: ProcessObservationMonitorProbeEvidence? = null

    @Synchronized
    fun verify(): ProcessObservationMonitorProbeEvidence {
        retainedEvidence?.let { return it }
        verifyCleanFinish()
        val leak = verifyLeakedDescendantFinish()
        return ProcessObservationMonitorProbeEvidence(
            cleanPathAccepted = true,
            liveDescendantRejected = true,
            liveDescendantIdentityRecorded = leak.identityRecorded,
            rootTerminated = leak.rootTerminated,
            descendantTerminated = leak.descendantTerminated,
            survivorCount = leak.survivorCount,
        ).also { evidence -> retainedEvidence = evidence }
    }

    private fun verifyCleanFinish() {
        val process = startProbe("clean")
        val monitor = ProcessObservationMonitor(process, currentJavaExecutable())
        try {
            check(process.waitFor(PROBE_START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                "Clean process observation probe exceeded its bounded lifetime"
            }
            check(process.exitValue() == 0)
            val evidence = monitor.finish(fakeGuardEvidence(process.pid()))
            check(evidence.embeddedJavaObserved)
            check(evidence.guardProcessMatchedEmbeddedJava)
            check(evidence.monitorFailure == null)
            check(!process.isAlive)
        } finally {
            monitor.close()
            stopRetainedHandles(listOf(process.toHandle()))
        }
    }

    private fun verifyLeakedDescendantFinish(): LeakProbeResult {
        val process = startProbe("leak")
        val monitor = ProcessObservationMonitor(process, currentJavaExecutable())
        var leaf: ProcessHandle? = null
        try {
            val leafPid = readLeafPidBoundedly(process)
            leaf = ProcessHandle.of(leafPid).orElse(null)
                ?: error("Probe leaf process identity disappeared before finish")
            awaitCondition(PROBE_START_TIMEOUT) {
                process.isAlive && leaf.isAlive && process.toHandle().descendants().use { descendants ->
                    descendants.anyMatch { handle -> handle.pid() == leafPid }
                }
            }
            val failure = try {
                monitor.finish(fakeGuardEvidence(process.pid()))
                error("ProcessObservationMonitor.finish accepted a live descendant")
            } catch (expected: LiveDescendantAtFinish) {
                expected
            }
            val identityRecorded = failure.liveDescendantIdentities.any { identity ->
                identity.pid == leafPid &&
                    identity.commandName.isNotBlank() &&
                    identity.commandSha256.matches(SHA256) &&
                    identity.argumentsSha256.matches(SHA256) &&
                    identity.identitySha256.matches(SHA256)
            }
            check(identityRecorded) { "Live descendant failure omitted its exact process identity" }
            check(failure.survivorIdentities.isEmpty())
            check(!process.isAlive) { "Leaked-descendant cleanup left the supervised root alive" }
            check(!leaf.isAlive) { "Leaked-descendant cleanup left the recorded descendant alive" }
            return LeakProbeResult(
                identityRecorded = identityRecorded,
                rootTerminated = !process.isAlive,
                descendantTerminated = !leaf.isAlive,
                survivorCount = failure.survivorIdentities.size,
            )
        } finally {
            monitor.close()
            stopRetainedHandles(listOfNotNull(leaf, process.toHandle()))
        }
    }

    private fun startProbe(mode: String): Process {
        val classpath = System.getProperty("java.class.path")?.takeIf(String::isNotBlank)
            ?: error("Process observation probe classpath identity is missing")
        return ProcessBuilder(
            currentJavaExecutable().toString(),
            "-cp",
            classpath,
            ProcessObservationProbeChild::class.java.name,
            mode,
        ).redirectErrorStream(false).start()
    }

    private fun readLeafPidBoundedly(process: Process): Long {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "process-observation-probe-readiness").apply { isDaemon = true }
        }
        return try {
            val line = executor.submit(Callable {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readLine() }
            }).get(PROBE_START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            val match = Regex("LEAF_PID=(\\d+)").matchEntire(line.orEmpty())
                ?: error("Leaked-descendant probe omitted its bounded leaf identity")
            match.groupValues[1].toLong().also { pid -> check(pid > 0) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun fakeGuardEvidence(pid: Long): ChildGuardEvidence {
        val hash = "0".repeat(64)
        return ChildGuardEvidence(
            policyVersion = SubjectSandbox.CHILD_GUARD_POLICY_VERSION,
            guardClass = SubjectSandbox.CHILD_GUARD_CLASS_NAME + "\$FailClosedSecurityManager",
            agentSha256 = hash,
            attestationSha256 = hash,
            tokenSha256 = hash,
            processId = pid,
            javaFeature = 21,
            launcherBoundaryAlreadyStarted = true,
            socketConnectDenied = true,
            socketListenDenied = true,
            socketAcceptDenied = true,
            socketMulticastDenied = true,
            processExecDenied = true,
            outsideWriteDenied = true,
            readOnlyCandidateWriteDenied = true,
            readOnlyCandidateReportsNotWritable = true,
            installedRuntimeReadDenied = true,
            symbolicAndHardLinksDenied = true,
            expectedSecurityManagerWarningsObserved = true,
            completeStderrSha256 = hash,
            securityManagerWarningLinesSha256 = hash,
        )
    }

    private data class LeakProbeResult(
        val identityRecorded: Boolean,
        val rootTerminated: Boolean,
        val descendantTerminated: Boolean,
        val survivorCount: Int,
    )

    private val PROBE_START_TIMEOUT: Duration = Duration.ofSeconds(10)
    private val SHA256 = Regex("[0-9a-f]{64}")
}

internal data class CandidateArchiveIdentityProbeEvidence(
    val archiveSha256: String,
    val checksumSha256: String,
    val verifierRecordSha256: String,
    val verifierCompleteLogSha256: String,
    val buildJdkIdentitySha256: String,
    val candidateRuntimeIdentitySha256: String,
    val missingChecksumRejected: Boolean,
    val missingVerifierRejected: Boolean,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("archiveSha256", archiveSha256)
        put("checksumSha256", checksumSha256)
        put("verifierRecordSha256", verifierRecordSha256)
        put("verifierCompleteLogSha256", verifierCompleteLogSha256)
        put("buildJdkIdentitySha256", buildJdkIdentitySha256)
        put("candidateRuntimeIdentitySha256", candidateRuntimeIdentitySha256)
        put("missingChecksumRejected", missingChecksumRejected)
        put("missingVerifierRejected", missingVerifierRejected)
    }
}

internal object CandidateArchiveIdentityContractProbe {
    @Volatile private var retainedEvidence: CandidateArchiveIdentityProbeEvidence? = null

    @Synchronized
    fun verify(
        archive: Path,
        checksum: Path,
        candidateRoot: Path,
        verifierPlatform: String,
    ): CandidateArchiveIdentityProbeEvidence {
        retainedEvidence?.let { retained ->
            check(retained.archiveSha256 == sha256(archive))
            check(retained.checksumSha256 == sha256(checksum))
            return retained
        }
        val identity = observeCandidateArchiveIdentity(archive, checksum, verifierPlatform)
        val buildJdk = observeBuildJdkIdentity()
        val embeddedJava = candidateRoot.resolve(
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                "runtime/bin/java.exe"
            } else {
                "runtime/bin/java"
            },
        )
        val candidateRuntime = observeCandidateRuntimeIdentity(candidateRoot, embeddedJava, buildJdk)
        check(identity.archiveSha256 == sha256(archive))
        check(identity.checksumSha256 == sha256(checksum))
        check(buildJdk.vendor.isNotBlank())
        check(buildJdk.version == candidateRuntime.javaVersion)
        check(candidateRuntime.javaExecutableSha256 == sha256(embeddedJava))

        val missingIdentity = verifyMissingIdentityRejection(archive, verifierPlatform)
        return CandidateArchiveIdentityProbeEvidence(
            archiveSha256 = identity.archiveSha256,
            checksumSha256 = identity.checksumSha256,
            verifierRecordSha256 = identity.verifierRecordSha256,
            verifierCompleteLogSha256 = identity.verifierCompleteLogSha256,
            buildJdkIdentitySha256 = canonicalJsonSha256(buildJdk.toJson()),
            candidateRuntimeIdentitySha256 = canonicalJsonSha256(candidateRuntime.toJson()),
            missingChecksumRejected = missingIdentity.first,
            missingVerifierRejected = missingIdentity.second,
        ).also { evidence -> retainedEvidence = evidence }
    }

    private fun verifyMissingIdentityRejection(archive: Path, verifierPlatform: String): Pair<Boolean, Boolean> {
        val root = Files.createTempDirectory("candidate-archive-identity-probe-")
        try {
            val missingChecksum = root.resolve("candidate/missing.sha256")
            val missingChecksumRejected = runCatching {
                observeCandidateArchiveIdentity(archive, missingChecksum, verifierPlatform)
            }.exceptionOrNull()?.message.orEmpty().contains("checksum is missing")
            check(missingChecksumRejected) { "Candidate archive identity accepted a missing checksum" }

            val checksum = root.resolve("candidate/refactorkit-runtime.zip.sha256")
            Files.createDirectories(checksum.parent)
            Files.writeString(
                checksum,
                "${sha256(archive)}  ${archive.fileName}\n",
                Charsets.UTF_8,
            )
            val missingVerifierRejected = runCatching {
                observeCandidateArchiveIdentity(archive, checksum, verifierPlatform)
            }.exceptionOrNull()?.message.orEmpty().contains("verifier record is missing")
            check(missingVerifierRejected) { "Candidate archive identity accepted a missing verifier record" }
            return missingChecksumRejected to missingVerifierRejected
        } finally {
            deleteTreeNoFollow(root)
        }
    }
}

internal object QualificationContractProbeMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 4) {
            "Expected candidate archive, checksum, extracted root, and verifier platform"
        }
        val processEvidence = ProcessObservationMonitorContractProbe.verify()
        val archiveEvidence = CandidateArchiveIdentityContractProbe.verify(
            Path.of(arguments[0]).toAbsolutePath().normalize(),
            Path.of(arguments[1]).toAbsolutePath().normalize(),
            Path.of(arguments[2]).toAbsolutePath().normalize(),
            arguments[3],
        )
        println("processObservationProbeSha256=${canonicalJsonSha256(processEvidence.toJson())}")
        println("archiveIdentityProbeSha256=${canonicalJsonSha256(archiveEvidence.toJson())}")
        println("liveDescendantRejected=${processEvidence.liveDescendantRejected}")
        println("missingChecksumRejected=${archiveEvidence.missingChecksumRejected}")
        println("missingVerifierRejected=${archiveEvidence.missingVerifierRejected}")
    }
}

internal object ProcessObservationProbeChild {
    @JvmStatic
    fun main(arguments: Array<String>) {
        when (arguments.singleOrNull()) {
            "clean" -> Thread.sleep(500)
            "leak" -> {
                val child = ProcessBuilder(probeCommand("leaf")).start()
                println("LEAF_PID=${child.pid()}")
                System.out.flush()
                Thread.sleep(PROBE_CHILD_LIFETIME.toMillis())
            }
            "leaf" -> Thread.sleep(PROBE_CHILD_LIFETIME.toMillis())
            else -> error("Unknown process observation probe mode")
        }
    }

    private fun probeCommand(mode: String): List<String> = listOf(
        currentJavaExecutable().toString(),
        "-cp",
        System.getProperty("java.class.path"),
        ProcessObservationProbeChild::class.java.name,
        mode,
    )

    private val PROBE_CHILD_LIFETIME: Duration = Duration.ofSeconds(60)
}

private fun currentJavaExecutable(): Path {
    val fileName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "java.exe"
    } else {
        "java"
    }
    val executable = Path.of(System.getProperty("java.home"), "bin", fileName).toRealPath()
    require(Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(executable))
    return executable
}

private fun awaitCondition(timeout: Duration, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (System.nanoTime() < deadline) {
        if (condition()) return
        Thread.sleep(10)
    }
    error("Process observation probe did not reach its bounded descendant state")
}

private fun stopRetainedHandles(handles: List<ProcessHandle>) {
    val distinct = handles.distinctBy(ProcessHandle::pid)
    distinct.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy)
    awaitStopped(distinct, Duration.ofSeconds(1))
    distinct.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly)
    awaitStopped(distinct, Duration.ofSeconds(3))
    check(distinct.none(ProcessHandle::isAlive)) { "Process observation probe cleanup left a process alive" }
}

private fun awaitStopped(handles: List<ProcessHandle>, timeout: Duration) {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (handles.any(ProcessHandle::isAlive) && System.nanoTime() < deadline) Thread.sleep(10)
}
