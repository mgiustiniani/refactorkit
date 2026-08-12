package org.refactorkit.cli.packagedmavenmodulerename

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.Collections
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.name
import kotlin.math.min

internal class SubjectStartRefused(
    val executable: Path,
    cause: IOException,
) : IOException("Direct subject execution was refused: ${cause.message}", cause)

internal data class EnvironmentAttestation(
    val inheritedEnvironmentCleared: Boolean,
    val inheritedJavaOptionsRemoved: Boolean,
    val controlledJavaOptionsInstalled: Boolean,
    val authorityVariablesAbsent: Boolean,
    val proxyVariablesAbsent: Boolean,
    val credentialVariablesAbsent: Boolean,
    val poisonPathFirst: Boolean,
    val isolatedHomeConfigured: Boolean,
    val isolatedTempConfigured: Boolean,
    val poisonExecutables: Set<String>,
    val settingsTrapHashes: Map<String, String>,
    val childSecurityGuardAgentSha256: String,
    val childSecurityGuardPolicyVersion: String,
    val installedTestRuntimePathSha256: String,
    val testRuntimeJavaFeature: Int,
)

internal data class ChildGuardEvidence(
    val policyVersion: String,
    val guardClass: String,
    val agentSha256: String,
    val attestationSha256: String,
    val tokenSha256: String,
    val processId: Long,
    val javaFeature: Int,
    val launcherBoundaryAlreadyStarted: Boolean,
    val socketConnectDenied: Boolean,
    val socketListenDenied: Boolean,
    val socketAcceptDenied: Boolean,
    val socketMulticastDenied: Boolean,
    val processExecDenied: Boolean,
    val outsideWriteDenied: Boolean,
    val readOnlyCandidateWriteDenied: Boolean,
    val readOnlyCandidateReportsNotWritable: Boolean,
    val installedRuntimeReadDenied: Boolean,
    val symbolicAndHardLinksDenied: Boolean,
    val expectedSecurityManagerWarningsObserved: Boolean,
    val completeStderrSha256: String,
    val securityManagerWarningLinesSha256: String,
) {
    val networkDenied: Boolean
        get() = socketConnectDenied && socketListenDenied && socketAcceptDenied && socketMulticastDenied

    fun assertClean() {
        check(policyVersion == SubjectSandbox.CHILD_GUARD_POLICY_VERSION)
        check(guardClass == SubjectSandbox.CHILD_GUARD_CLASS_NAME + "\$FailClosedSecurityManager")
        check(javaFeature == 21)
        check(launcherBoundaryAlreadyStarted)
        check(networkDenied)
        check(processExecDenied)
        check(outsideWriteDenied)
        check(readOnlyCandidateWriteDenied)
        check(readOnlyCandidateReportsNotWritable)
        check(installedRuntimeReadDenied)
        check(symbolicAndHardLinksDenied)
        check(expectedSecurityManagerWarningsObserved)
        check(agentSha256.matches(Regex("[0-9a-f]{64}")))
        check(attestationSha256.matches(Regex("[0-9a-f]{64}")))
        check(tokenSha256.matches(Regex("[0-9a-f]{64}")))
        check(completeStderrSha256.matches(Regex("[0-9a-f]{64}")))
        check(securityManagerWarningLinesSha256.matches(Regex("[0-9a-f]{64}")))
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("policyVersion", policyVersion)
        put("guardClass", guardClass)
        put("agentSha256", agentSha256)
        put("attestationSha256", attestationSha256)
        put("tokenSha256", tokenSha256)
        put("processId", processId)
        put("javaFeature", javaFeature)
        put("launcherBoundaryAlreadyStarted", launcherBoundaryAlreadyStarted)
        put("socketConnectDenied", socketConnectDenied)
        put("socketListenDenied", socketListenDenied)
        put("socketAcceptDenied", socketAcceptDenied)
        put("socketMulticastDenied", socketMulticastDenied)
        put("networkDenied", networkDenied)
        put("processExecDenied", processExecDenied)
        put("outsideWriteDenied", outsideWriteDenied)
        put("readOnlyCandidateWriteDenied", readOnlyCandidateWriteDenied)
        put("readOnlyCandidateReportsNotWritable", readOnlyCandidateReportsNotWritable)
        put("installedRuntimeReadDenied", installedRuntimeReadDenied)
        put("symbolicAndHardLinksDenied", symbolicAndHardLinksDenied)
        put("expectedSecurityManagerWarningsObserved", expectedSecurityManagerWarningsObserved)
        put("completeStderrSha256", completeStderrSha256)
        put("securityManagerWarningLinesSha256", securityManagerWarningLinesSha256)
    }
}

internal data class GuardedChildLaunch(
    val environment: Map<String, String>,
    val attestation: Path,
    val tokenSha256: String,
    val agentSha256: String,
    val expectedWorkspacePathSha256: String,
    val expectedHomePathSha256: String,
    val expectedTemporaryPathSha256: String,
    val expectedInstalledRuntimePathSha256: String,
    val expectedReadOnlyCandidatePathSha256: String,
) {
    fun verify(stderr: ByteArray): ChildGuardEvidence {
        check(Files.isRegularFile(attestation, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(attestation)) {
            "Every subject process must install and attest the JDK 21 child security guard"
        }
        val properties = Properties().apply {
            Files.newInputStream(attestation, StandardOpenOption.READ).use(::load)
        }
        val requiredBooleanProperties = listOf(
            "launcherBoundaryAlreadyStarted",
            "socketConnectDenied",
            "socketListenDenied",
            "socketAcceptDenied",
            "socketMulticastDenied",
            "processExecDenied",
            "outsideWriteDenied",
            "readOnlyCandidateWriteDenied",
            "readOnlyCandidateReportsNotWritable",
            "installedRuntimeReadDenied",
            "symbolicAndHardLinksDenied",
        )
        requiredBooleanProperties.forEach { name ->
            check(properties.getProperty(name) == "true") { "Child security guard omitted denial $name" }
        }
        check(properties.getProperty("policyVersion") == SubjectSandbox.CHILD_GUARD_POLICY_VERSION)
        check(properties.getProperty("guardClass") == SubjectSandbox.CHILD_GUARD_CLASS_NAME + "\$FailClosedSecurityManager")
        check(properties.getProperty("javaFeature") == "21")
        check(properties.getProperty("tokenSha256") == tokenSha256)
        check(properties.getProperty("workspacePathSha256") == expectedWorkspacePathSha256)
        check(properties.getProperty("homePathSha256") == expectedHomePathSha256)
        check(properties.getProperty("temporaryPathSha256") == expectedTemporaryPathSha256)
        check(properties.getProperty("installedRuntimePathSha256") == expectedInstalledRuntimePathSha256)
        check(properties.getProperty("readOnlyCandidatePathSha256") == expectedReadOnlyCandidatePathSha256)
        val processId = properties.getProperty("processId")?.toLongOrNull()
            ?: error("Child security guard omitted its process ID")
        check(processId > 0) { "Child security guard attested an invalid process ID" }

        val stderrText = decodeUtf8Strict(stderr, "child security guard stderr")
        val warningFragments = listOf(
            "WARNING: A terminally deprecated method in java.lang.System has been called",
            "WARNING: System::setSecurityManager has been called by",
            "WARNING: Please consider reporting this to the maintainers of",
            "WARNING: System::setSecurityManager will be removed in a future release",
        )
        val warningsObserved = warningFragments.all(stderrText::contains)
        check(warningsObserved) { "Complete child stderr omitted an expected JDK 21 SecurityManager warning" }
        check(stderrText.contains("Picked up JAVA_TOOL_OPTIONS:")) {
            "Complete child stderr omitted controlled JVM startup-input evidence"
        }
        check(stderrText.contains(
            "${SubjectSandbox.CHILD_GUARD_INSTALLED_MARKER} " +
                "policyVersion=${SubjectSandbox.CHILD_GUARD_POLICY_VERSION} tokenSha256=$tokenSha256",
        )) { "Complete child stderr omitted the installed-guard marker" }
        val unexpectedDenials = stderrText.lines().filter {
            it.startsWith("REFRACTORKIT_QUALIFICATION_GUARD_UNEXPECTED_DENIAL")
        }
        check(unexpectedDenials.isEmpty()) {
            "The subject requested authority denied by its fail-closed child guard: " +
                unexpectedDenials.joinToString(" | ")
        }
        val warningLines = stderrText.lines().filter { it.startsWith("WARNING:") }.joinToString("\n")
        val evidence = ChildGuardEvidence(
            policyVersion = properties.getProperty("policyVersion"),
            guardClass = properties.getProperty("guardClass"),
            agentSha256 = agentSha256,
            attestationSha256 = sha256(attestation),
            tokenSha256 = tokenSha256,
            processId = processId,
            javaFeature = properties.getProperty("javaFeature").toInt(),
            launcherBoundaryAlreadyStarted = properties.getProperty("launcherBoundaryAlreadyStarted").toBooleanStrict(),
            socketConnectDenied = properties.getProperty("socketConnectDenied").toBooleanStrict(),
            socketListenDenied = properties.getProperty("socketListenDenied").toBooleanStrict(),
            socketAcceptDenied = properties.getProperty("socketAcceptDenied").toBooleanStrict(),
            socketMulticastDenied = properties.getProperty("socketMulticastDenied").toBooleanStrict(),
            processExecDenied = properties.getProperty("processExecDenied").toBooleanStrict(),
            outsideWriteDenied = properties.getProperty("outsideWriteDenied").toBooleanStrict(),
            readOnlyCandidateWriteDenied = properties.getProperty("readOnlyCandidateWriteDenied").toBooleanStrict(),
            readOnlyCandidateReportsNotWritable = properties.getProperty("readOnlyCandidateReportsNotWritable").toBooleanStrict(),
            installedRuntimeReadDenied = properties.getProperty("installedRuntimeReadDenied").toBooleanStrict(),
            symbolicAndHardLinksDenied = properties.getProperty("symbolicAndHardLinksDenied").toBooleanStrict(),
            expectedSecurityManagerWarningsObserved = warningsObserved,
            completeStderrSha256 = sha256(stderr),
            securityManagerWarningLinesSha256 = sha256(warningLines.toByteArray(StandardCharsets.UTF_8)),
        )
        evidence.assertClean()
        return evidence
    }
}

internal class SubjectSandbox private constructor(
    val root: Path,
    val home: Path,
    val temporary: Path,
    val poison: Path,
    val marker: Path,
    val environment: Map<String, String>,
    val attestation: EnvironmentAttestation,
    private val settingsTraps: Map<Path, String>,
    val windows: Boolean,
    val comspec: Path?,
    val installedTestRuntime: Path,
    private val childGuardAgent: Path,
    private val controlledJavaOptions: String,
    private val launchCounter: AtomicInteger,
) {
    fun prepareGuardedLaunch(
        workingDirectory: Path,
        readOnlyCandidateRoot: Path,
        label: String,
    ): GuardedChildLaunch {
        val workspace = workingDirectory.toAbsolutePath().normalize()
        require(Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(workspace)) {
            "Guarded subject workspace must be a no-follow directory"
        }
        val readOnlyCandidate = readOnlyCandidateRoot.toAbsolutePath().normalize()
        require(Files.isDirectory(readOnlyCandidate, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(readOnlyCandidate))
        require(!workspace.startsWith(readOnlyCandidate) && !readOnlyCandidate.startsWith(workspace))
        val ordinal = launchCounter.incrementAndGet()
        val safeLabel = label.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val token = sha256(
            "$safeLabel\u0000$ordinal\u0000${UUID.randomUUID()}".toByteArray(StandardCharsets.UTF_8),
        )
        val tokenSha256 = sha256(token.toByteArray(StandardCharsets.UTF_8))
        val guardAttestation = temporary.resolve(
            "child-guard-${ordinal.toString().padStart(2, '0')}-$safeLabel.properties",
        ).toAbsolutePath().normalize()
        require(guardAttestation.parent == temporary.toAbsolutePath().normalize())
        require(Files.notExists(guardAttestation, LinkOption.NOFOLLOW_LINKS))
        val dynamicOptions = listOf(
            "-D$CHILD_GUARD_PROPERTY_PREFIX.workspace=$workspace",
            "-D$CHILD_GUARD_PROPERTY_PREFIX.readOnlyCandidate=$readOnlyCandidate",
            "-D$CHILD_GUARD_PROPERTY_PREFIX.attestation=$guardAttestation",
            "-D$CHILD_GUARD_PROPERTY_PREFIX.token=$token",
        ).joinToString(" ") { javaOptionToken(it) }
        val guardedEnvironment = LinkedHashMap(environment).apply {
            put("JAVA_TOOL_OPTIONS", "$controlledJavaOptions $dynamicOptions")
        }
        return GuardedChildLaunch(
            environment = Collections.unmodifiableMap(guardedEnvironment),
            attestation = guardAttestation,
            tokenSha256 = tokenSha256,
            agentSha256 = sha256(childGuardAgent),
            expectedWorkspacePathSha256 = pathIdentity(workspace),
            expectedHomePathSha256 = pathIdentity(home.toRealPath()),
            expectedTemporaryPathSha256 = pathIdentity(temporary.toRealPath()),
            expectedInstalledRuntimePathSha256 = pathIdentity(installedTestRuntime.toRealPath()),
            expectedReadOnlyCandidatePathSha256 = pathIdentity(readOnlyCandidate.toRealPath()),
        )
    }

    fun assertTripwiresClean() {
        check(Files.notExists(marker, LinkOption.NOFOLLOW_LINKS)) {
            "A poison PATH executable was requested"
        }
        check(Files.isRegularFile(childGuardAgent, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(childGuardAgent))
        check(sha256(childGuardAgent) == attestation.childSecurityGuardAgentSha256) {
            "The test-only child security guard agent changed"
        }
        settingsTraps.forEach { (path, expectedHash) ->
            check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                "A settings or credential tripwire disappeared: ${path.fileName}"
            }
            val posix = Files.getFileAttributeView(
                path,
                PosixFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            val originalPermissions = posix?.readAttributes()?.permissions()
            if (originalPermissions != null && PosixFilePermission.OWNER_READ !in originalPermissions) {
                Files.setPosixFilePermissions(path, originalPermissions + PosixFilePermission.OWNER_READ)
            }
            try {
                check(sha256(path) == expectedHash) {
                    "A settings or credential tripwire changed: ${path.fileName}"
                }
            } finally {
                if (originalPermissions != null) Files.setPosixFilePermissions(path, originalPermissions)
            }
        }
    }

    fun cleanup() = deleteTreeNoFollow(root)

    companion object {
        internal const val CHILD_GUARD_POLICY_VERSION = "packaged-story-bdd-jdk21-v1"
        internal const val CHILD_GUARD_PROPERTY_PREFIX = "refactorkit.qualification.guard"
        internal const val CHILD_GUARD_CLASS_NAME =
            "org.refactorkit.cli.packagedmavenmodulerename.QualificationChildSecurityGuard"
        internal const val CHILD_GUARD_INSTALLED_MARKER =
            "REFRACTORKIT_QUALIFICATION_CHILD_GUARD_INSTALLED"

        private val AUTHORITY_VARIABLES = setOf(
            "JAVA_HOME",
            "JDK_JAVA_OPTIONS",
            "_JAVA_OPTIONS",
            "CLASSPATH",
            "MAVEN_HOME",
            "M2_HOME",
            "MAVEN_OPTS",
            "MAVEN_ARGS",
            "GRADLE_HOME",
            "GRADLE_USER_HOME",
            "GRADLE_OPTS",
        )
        private val PROXY_VARIABLES = setOf(
            "HTTP_PROXY",
            "HTTPS_PROXY",
            "FTP_PROXY",
            "ALL_PROXY",
            "NO_PROXY",
        )
        private val POISON_NAMES = setOf(
            "java",
            "mvn",
            "mvnw",
            "gradle",
            "gradlew",
            "git",
            "curl",
            "wget",
            "npm",
            "npx",
        )

        fun create(parent: Path, caseId: String): SubjectSandbox {
            check(Runtime.version().feature() == 21) {
                "The packaged Story-BDD subject guard must be prepared by JDK 21"
            }
            val root = parent.resolve("subject-sandbox-$caseId")
            require(Files.notExists(root, LinkOption.NOFOLLOW_LINKS))
            val home = root.resolve("home")
            val temporary = root.resolve("tmp")
            val poison = root.resolve("poison-path")
            val marker = root.resolve("poison-requested.txt")
            val childGuardAgent = root.resolve("qualification-child-security-guard-agent.jar")
            val installedTestRuntime = Path.of(System.getProperty("java.home")).toRealPath()
            listOf(home, temporary, poison).forEach(Files::createDirectories)
            listOf(
                home.resolve(".cache"),
                home.resolve(".config"),
                home.resolve(".local/share"),
                home.resolve(".m2"),
                home.resolve(".gradle"),
            ).forEach(Files::createDirectories)
            writeChildGuardAgent(childGuardAgent)
            val childGuardAgentHash = sha256(childGuardAgent)

            val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
            if (windows) installWindowsPoison(poison, marker) else installPosixPoison(poison, marker)
            val traps = installSettingsTraps(home, windows)
            val comspec = if (windows) absoluteParentEnvironmentPath("COMSPEC") else null
            val controlledOptions = listOf(
                "-javaagent:$childGuardAgent",
                "-Djava.security.manager=allow",
                "-Duser.home=${home.toAbsolutePath().normalize()}",
                "-Djava.io.tmpdir=${temporary.toAbsolutePath().normalize()}",
                "-Djava.net.useSystemProxies=false",
                "-Duser.language=en",
                "-Duser.country=US",
                "-D$CHILD_GUARD_PROPERTY_PREFIX.required=true",
                "-D$CHILD_GUARD_PROPERTY_PREFIX.policyVersion=$CHILD_GUARD_POLICY_VERSION",
                "-D$CHILD_GUARD_PROPERTY_PREFIX.home=${home.toAbsolutePath().normalize()}",
                "-D$CHILD_GUARD_PROPERTY_PREFIX.temporary=${temporary.toAbsolutePath().normalize()}",
                "-D$CHILD_GUARD_PROPERTY_PREFIX.installedRuntime=$installedTestRuntime",
            ).joinToString(" ") { javaOptionToken(it) }
            val environment = linkedMapOf<String, String>().apply {
                val normalizedHome = home.toAbsolutePath().normalize().toString()
                val normalizedTemp = temporary.toAbsolutePath().normalize().toString()
                put("HOME", normalizedHome)
                put("USERPROFILE", normalizedHome)
                put("TMPDIR", normalizedTemp)
                put("TMP", normalizedTemp)
                put("TEMP", normalizedTemp)
                put("XDG_CACHE_HOME", home.resolve(".cache").toAbsolutePath().normalize().toString())
                put("XDG_CONFIG_HOME", home.resolve(".config").toAbsolutePath().normalize().toString())
                put("XDG_DATA_HOME", home.resolve(".local/share").toAbsolutePath().normalize().toString())
                put("LANG", "C")
                put("LC_ALL", "C")
                put("JAVA_TOOL_OPTIONS", controlledOptions)
                put("PATH", minimalPath(poison, windows))
                put("REFACTOR_KIT_QUALIFICATION_NETWORK", "JDK21_GUARD_DENY")
                if (windows) {
                    val systemRoot = requireNotNull(parentEnvironmentValue("SystemRoot")) {
                        "Windows SystemRoot is required by the packaged qualification harness"
                    }
                    put("SystemRoot", systemRoot)
                    put("WINDIR", parentEnvironmentValue("WINDIR") ?: systemRoot)
                    put("COMSPEC", requireNotNull(comspec).toString())
                    parentEnvironmentValue("PATHEXT")?.let { put("PATHEXT", it) }
                    val absoluteHome = home.toAbsolutePath().normalize()
                    val drive = absoluteHome.root?.toString().orEmpty()
                    put("HOMEDRIVE", drive.removeSuffix("\\"))
                    put("HOMEPATH", "\\" + absoluteHome.toString().removePrefix(drive).trimStart('\\'))
                    put("APPDATA", home.resolve("AppData/Roaming").toAbsolutePath().normalize().toString())
                    put("LOCALAPPDATA", home.resolve("AppData/Local").toAbsolutePath().normalize().toString())
                }
            }
            val inheritedJavaToolOptions = parentEnvironmentValue("JAVA_TOOL_OPTIONS")
            val attestation = EnvironmentAttestation(
                inheritedEnvironmentCleared = true,
                inheritedJavaOptionsRemoved = inheritedJavaToolOptions == null ||
                    environment["JAVA_TOOL_OPTIONS"] != inheritedJavaToolOptions,
                controlledJavaOptionsInstalled = environment["JAVA_TOOL_OPTIONS"] == controlledOptions,
                authorityVariablesAbsent = AUTHORITY_VARIABLES
                    .filterNot { it == "JAVA_TOOL_OPTIONS" }
                    .all { name -> environment.keys.none { it.equals(name, ignoreCase = windows) } },
                proxyVariablesAbsent = environment.keys.none { key -> key.uppercase() in PROXY_VARIABLES },
                credentialVariablesAbsent = environment.keys.none(::looksCredentialBearing),
                poisonPathFirst = environment.getValue("PATH")
                    .split(java.io.File.pathSeparator)
                    .first() == poison.toAbsolutePath().normalize().toString(),
                isolatedHomeConfigured = environment["HOME"] == home.toAbsolutePath().normalize().toString() &&
                    environment["USERPROFILE"] == home.toAbsolutePath().normalize().toString(),
                isolatedTempConfigured = listOf("TMPDIR", "TMP", "TEMP").all { name ->
                    environment[name] == temporary.toAbsolutePath().normalize().toString()
                },
                poisonExecutables = POISON_NAMES,
                settingsTrapHashes = traps.entries.associate { (path, hash) ->
                    home.relativize(path).toString().replace('\\', '/') to hash
                },
                childSecurityGuardAgentSha256 = childGuardAgentHash,
                childSecurityGuardPolicyVersion = CHILD_GUARD_POLICY_VERSION,
                installedTestRuntimePathSha256 = pathIdentity(installedTestRuntime),
                testRuntimeJavaFeature = Runtime.version().feature(),
            )
            check(attestation.inheritedEnvironmentCleared)
            check(attestation.inheritedJavaOptionsRemoved)
            check(attestation.controlledJavaOptionsInstalled)
            check(attestation.authorityVariablesAbsent)
            check(attestation.proxyVariablesAbsent)
            check(attestation.credentialVariablesAbsent)
            check(attestation.poisonPathFirst)
            check(attestation.isolatedHomeConfigured)
            check(attestation.isolatedTempConfigured)
            check(attestation.childSecurityGuardAgentSha256 == sha256(childGuardAgent))
            check(attestation.testRuntimeJavaFeature == 21)
            return SubjectSandbox(
                root,
                home,
                temporary,
                poison,
                marker,
                Collections.unmodifiableMap(environment),
                attestation,
                traps,
                windows,
                comspec,
                installedTestRuntime,
                childGuardAgent,
                controlledOptions,
                AtomicInteger(0),
            )
        }

        private fun writeChildGuardAgent(path: Path) {
            val manifest = Manifest().apply {
                mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                mainAttributes[Attributes.Name("Premain-Class")] = CHILD_GUARD_CLASS_NAME
                mainAttributes[Attributes.Name("Can-Redefine-Classes")] = "false"
                mainAttributes[Attributes.Name("Can-Retransform-Classes")] = "false"
            }
            val manifestBytes = ByteArrayOutputStream().also { output -> manifest.write(output) }.toByteArray()
            val classResources = listOf(
                CHILD_GUARD_CLASS_NAME.replace('.', '/') + ".class",
                CHILD_GUARD_CLASS_NAME.replace('.', '/') + "\$CheckedAction.class",
                CHILD_GUARD_CLASS_NAME.replace('.', '/') + "\$Configuration.class",
                CHILD_GUARD_CLASS_NAME.replace('.', '/') + "\$FailClosedSecurityManager.class",
            )
            Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                JarOutputStream(output).use { jar ->
                    val manifestEntry = JarEntry("META-INF/MANIFEST.MF").apply { time = 0L }
                    jar.putNextEntry(manifestEntry)
                    jar.write(manifestBytes)
                    jar.closeEntry()
                    classResources.forEach { resource ->
                        val bytes = SubjectSandbox::class.java.classLoader.getResourceAsStream(resource)
                            ?.use { it.readAllBytes() }
                            ?: error("Compiled child security guard resource is missing: $resource")
                        val entry = JarEntry(resource).apply { time = 0L }
                        jar.putNextEntry(entry)
                        jar.write(bytes)
                        jar.closeEntry()
                    }
                }
            }
            check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
        }

        private fun installPosixPoison(poison: Path, marker: Path) {
            POISON_NAMES.forEach { name ->
                val executable = poison.resolve(name)
                Files.writeString(
                    executable,
                    "#!/bin/sh\nprintf '%s\\n' '$name' >> '${shellSingleQuoted(marker.toAbsolutePath().normalize().toString())}'\nexit 97\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                )
                Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwxr-xr-x"))
            }
        }

        private fun installWindowsPoison(poison: Path, marker: Path) {
            POISON_NAMES.forEach { name ->
                listOf("$name.bat", "$name.cmd").forEach { fileName ->
                    Files.writeString(
                        poison.resolve(fileName),
                        "@echo off\r\necho $name>>\"${marker.toAbsolutePath().normalize()}\"\r\nexit /b 97\r\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                    )
                }
            }
            Files.write(
                poison.resolve("java.exe"),
                "RefactorKit poison: deliberately invalid PE executable.\r\n".toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
        }

        private fun installSettingsTraps(home: Path, windows: Boolean): Map<Path, String> {
            val marker = "REFRACTORKIT-DENIED-SETTINGS-CREDENTIAL-MARKER\n".toByteArray(StandardCharsets.UTF_8)
            val paths = listOf(
                home.resolve(".m2/settings.xml"),
                home.resolve(".gradle/gradle.properties"),
                home.resolve(".git-credentials"),
                home.resolve(".config/git/credentials"),
            )
            Files.createDirectories(home.resolve(".config/git"))
            return paths.associateWith { path ->
                Files.write(path, marker, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                val contentHash = sha256(path)
                if (!windows && Files.getFileAttributeView(
                        path,
                        PosixFileAttributeView::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    ) != null
                ) {
                    Files.setPosixFilePermissions(path, emptySet())
                }
                contentHash
            }
        }

        private fun minimalPath(poison: Path, windows: Boolean): String {
            val first = poison.toAbsolutePath().normalize().toString()
            val platform = if (windows) {
                val root = Path.of(requireNotNull(parentEnvironmentValue("SystemRoot"))).toAbsolutePath().normalize()
                listOf(root.resolve("System32").toString(), root.toString())
            } else {
                listOf("/usr/bin", "/bin")
            }
            return (listOf(first) + platform).distinct().joinToString(java.io.File.pathSeparator)
        }

        private fun absoluteParentEnvironmentPath(name: String): Path {
            val value = requireNotNull(parentEnvironmentValue(name)) { "$name is required" }
            val path = Path.of(value)
            require(path.isAbsolute && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                "$name must identify an absolute regular file"
            }
            return path.toAbsolutePath().normalize()
        }

        private fun parentEnvironmentValue(name: String): String? = System.getenv().entries
            .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
            ?.value

        private fun looksCredentialBearing(name: String): Boolean {
            val upper = name.uppercase()
            return listOf("TOKEN", "PASSWORD", "PASSWD", "SECRET", "CREDENTIAL", "API_KEY", "AWS_")
                .any(upper::contains)
        }

        private fun javaOptionToken(value: String): String {
            require('"' !in value && '\n' !in value && '\r' !in value)
            return if (value.any(Char::isWhitespace)) "\"$value\"" else value
        }

        private fun pathIdentity(path: Path): String = sha256(
            path.toAbsolutePath().normalize().toString().toByteArray(StandardCharsets.UTF_8),
        )

        private fun shellSingleQuoted(value: String): String = value.replace("'", "'\\''")
    }
}

internal data class BoundedProcessResult(
    val label: String,
    val exitCode: Int?,
    val stdout: ByteArray,
    val stderr: ByteArray,
    val stdoutBytesObserved: Long,
    val stderrBytesObserved: Long,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val timedOut: Boolean,
    val durationMillis: Long,
    val descendantsTerminated: Int,
    val processEvidence: ProcessObservationEvidence,
) {
    fun stdoutText(): String = decodeUtf8Strict(stdout, "$label stdout")
    fun stderrText(): String = decodeUtf8Strict(stderr, "$label stderr")
}

internal data class ProcessObservationEvidence(
    val embeddedJavaObserved: Boolean,
    val embeddedJavaSha256: String,
    val observedCommands: Set<String>,
    val deniedCommands: Set<String>,
    val unexpectedDescendantCommands: Set<String>,
    val networkObservationMode: String,
    val networkObservationSucceeded: Boolean,
    val linuxSocketObservationSucceeded: Boolean,
    val socketInodes: Set<String>,
    val maximumResidentBytes: Long,
    val monitorFailure: String?,
    val childGuard: ChildGuardEvidence,
    val guardProcessMatchedEmbeddedJava: Boolean,
) {
    fun assertClean() {
        check(embeddedJavaObserved) { "The hash-bound embedded Java process was not observed" }
        check(deniedCommands.isEmpty()) { "Denied build/network executable observed: $deniedCommands" }
        check(unexpectedDescendantCommands.isEmpty()) {
            "Unexpected descendant executable observed: $unexpectedDescendantCommands"
        }
        childGuard.assertClean()
        check(guardProcessMatchedEmbeddedJava) {
            "Child guard attestation did not belong to the observed embedded Java process"
        }
        check(networkObservationMode == "jdk21-child-security-guard-fail-closed" ||
            networkObservationMode == "jdk21-child-security-guard-fail-closed+linux-proc-supplement") {
            "Subject network policy was not fail-closed on this host"
        }
        check(networkObservationSucceeded && childGuard.networkDenied) {
            "JDK 21 child guard did not deny every network authority"
        }
        check(!networkObservationMode.endsWith("linux-proc-supplement") || linuxSocketObservationSucceeded) {
            "Linux supplemental subject socket observation never completed"
        }
        check(socketInodes.isEmpty()) { "Subject network socket authority observed: $socketInodes" }
        check(maximumResidentBytes <= MAXIMUM_RESIDENT_BYTES) {
            "Subject exceeded the bounded resident-memory ceiling"
        }
        check(monitorFailure == null) { "Process monitor failed: $monitorFailure" }
    }

    companion object {
        const val MAXIMUM_RESIDENT_BYTES: Long = 1536L * 1024 * 1024
    }
}

internal data class TranscriptEntry(
    val label: String,
    val argv: List<String>,
    val stdout: ByteArray,
    val stderr: ByteArray,
    val exitCode: Int?,
    val durationMillis: Long,
    val stdoutBytesObserved: Long,
    val stderrBytesObserved: Long,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val timedOut: Boolean,
)

private data class StartedSubjectProcess(
    val process: Process,
    val guardLaunch: GuardedChildLaunch,
)

internal class PackagedSubjectProcessHarness(
    private val launcher: Path,
    private val embeddedJava: Path,
    private val candidateRoot: Path,
    private val sandbox: SubjectSandbox,
    private val absoluteDeadlineNanos: Long,
) : AutoCloseable {
    private var persistent: PersistentNdjsonProcess? = null
    private var closed = false
    private val mutableTranscripts = mutableListOf<TranscriptEntry>()
    private val mutableProcessEvidence = mutableListOf<ProcessObservationEvidence>()
    private val mutableStartFailures = mutableListOf<String>()

    val transcripts: List<TranscriptEntry> get() = mutableTranscripts.toList()
    val processEvidence: List<ProcessObservationEvidence> get() = mutableProcessEvidence.toList()
    val startFailures: List<String> get() = mutableStartFailures.toList()

    init {
        require(Files.isRegularFile(launcher, LinkOption.NOFOLLOW_LINKS)) {
            "Selected candidate launcher is not a regular extracted file"
        }
        require(Files.isRegularFile(embeddedJava, LinkOption.NOFOLLOW_LINKS)) {
            "Candidate embedded Java is not a regular extracted file"
        }
        require(launcher.toAbsolutePath().normalize().startsWith(candidateRoot.toAbsolutePath().normalize()))
        require(embeddedJava.toAbsolutePath().normalize().startsWith(candidateRoot.toAbsolutePath().normalize()))
    }

    fun runOneShot(label: String, arguments: List<String>, workingDirectory: Path): BoundedProcessResult {
        check(!closed && persistent == null)
        require(arguments.none { '\u0000' in it })
        val command = command(arguments)
        val startedSubject = start(command, workingDirectory, label)
        val process = startedSubject.process
        val monitor = ProcessObservationMonitor(process, embeddedJava)
        val executor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "packaged-$label-stream-drain").apply { isDaemon = true }
        }
        val stdout = executor.submit(Callable { drainBounded(process.inputStream) })
        val stderr = executor.submit(Callable { drainBounded(process.errorStream) })
        val started = System.nanoTime()
        var timedOut = false
        var descendantsTerminated = 0
        try {
            val timeoutMillis = remainingMillis(CHILD_TIMEOUT)
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                timedOut = true
                descendantsTerminated = terminateTree(process)
            }
            val stdoutBytes = awaitDrain(stdout, process)
            val stderrBytes = awaitDrain(stderr, process)
            val guardEvidence = startedSubject.guardLaunch.verify(stderrBytes.retained)
            val evidence = monitor.finish(guardEvidence)
            mutableProcessEvidence += evidence
            val result = BoundedProcessResult(
                label = label,
                exitCode = if (process.isAlive) null else process.exitValue(),
                stdout = stdoutBytes.retained,
                stderr = stderrBytes.retained,
                stdoutBytesObserved = stdoutBytes.total,
                stderrBytesObserved = stderrBytes.total,
                stdoutTruncated = stdoutBytes.truncated,
                stderrTruncated = stderrBytes.truncated,
                timedOut = timedOut,
                durationMillis = Duration.ofNanos(System.nanoTime() - started).toMillis(),
                descendantsTerminated = descendantsTerminated,
                processEvidence = evidence,
            )
            mutableTranscripts += result.toTranscript(arguments)
            return result
        } catch (failure: Throwable) {
            descendantsTerminated += terminateTree(process)
            throw failure
        } finally {
            monitor.close()
            executor.shutdownNow()
            check(descendantsTerminated >= 0)
        }
    }

    fun startNdjson(label: String, readinessText: String, workingDirectory: Path): PersistentNdjsonProcess {
        check(!closed && persistent == null)
        val startedSubject = start(command(emptyList()), workingDirectory, label)
        val process = startedSubject.process
        return try {
            PersistentNdjsonProcess(
                label,
                process,
                embeddedJava,
                absoluteDeadlineNanos,
                readinessText,
                startedSubject.guardLaunch,
            ).also { persistent = it }
        } catch (failure: Throwable) {
            terminateTree(process)
            throw failure
        }
    }

    fun finishPersistent() {
        persistent?.let { process ->
            process.close()
            mutableTranscripts += process.transcript()
            mutableProcessEvidence += process.observationEvidence()
            persistent = null
        }
    }

    fun recordStartRefusal(failure: SubjectStartRefused) {
        mutableStartFailures += "${failure::class.java.simpleName}: ${failure.message}"
    }

    fun assertAllCompletedCleanly() {
        check(persistent == null) { "Persistent subject process was not closed" }
        check(mutableTranscripts.isNotEmpty()) { "No subject process transcript was retained" }
        mutableTranscripts.forEach { transcript ->
            check(!transcript.timedOut) { "${transcript.label} timed out" }
            check(!transcript.stdoutTruncated) { "${transcript.label} stdout was truncated" }
            check(!transcript.stderrTruncated) { "${transcript.label} stderr was truncated" }
            check(transcript.exitCode == 0) { "${transcript.label} exited ${transcript.exitCode}" }
        }
        mutableProcessEvidence.forEach(ProcessObservationEvidence::assertClean)
        sandbox.assertTripwiresClean()
    }

    override fun close() {
        if (closed) return
        runCatching { finishPersistent() }
        closed = true
    }

    private fun start(command: List<String>, workingDirectory: Path, label: String): StartedSubjectProcess {
        require(Files.isDirectory(workingDirectory, LinkOption.NOFOLLOW_LINKS)) {
            "Subject working directory is missing"
        }
        checkRemaining("start $label")
        val guardLaunch = sandbox.prepareGuardedLaunch(workingDirectory, candidateRoot, label)
        val builder = ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(false)
        builder.environment().apply {
            clear()
            putAll(guardLaunch.environment)
        }
        return try {
            StartedSubjectProcess(builder.start(), guardLaunch)
        } catch (failure: IOException) {
            throw SubjectStartRefused(launcher, failure)
        }
    }

    private fun command(arguments: List<String>): List<String> = if (sandbox.windows) {
        listOf(requireNotNull(sandbox.comspec).toString(), "/d", "/s", "/c", launcher.toString()) + arguments
    } else {
        listOf(launcher.toString()) + arguments
    }

    private fun remainingMillis(limit: Duration): Long {
        val remaining = absoluteDeadlineNanos - System.nanoTime()
        check(remaining > 0) { "Absolute scenario timeout expired" }
        return min(limit.toMillis(), TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1))
    }

    private fun checkRemaining(activity: String) {
        check(System.nanoTime() < absoluteDeadlineNanos) { "Absolute scenario timeout before $activity" }
    }

    private fun awaitDrain(future: Future<BoundedBytes>, process: Process): BoundedBytes = try {
        future.get(DRAIN_COMPLETION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
    } catch (timeout: TimeoutException) {
        terminateTree(process)
        throw IllegalStateException("A bounded subject output drain did not finish", timeout)
    }

    companion object {
        val CHILD_TIMEOUT: Duration = Duration.ofSeconds(180)
        val DRAIN_COMPLETION_TIMEOUT: Duration = Duration.ofSeconds(15)
        const val OUTPUT_LIMIT_BYTES: Int = 32 * 1024 * 1024
        const val BUFFER_BYTES: Int = 8192
    }
}

internal class PersistentNdjsonProcess(
    private val label: String,
    private val process: Process,
    embeddedJava: Path,
    private val absoluteDeadlineNanos: Long,
    readinessText: String,
    private val guardLaunch: GuardedChildLaunch,
) : AutoCloseable {
    private val monitor = ProcessObservationMonitor(process, embeddedJava)
    private val stdoutCapture = BoundedCapture(PackagedSubjectProcessHarness.OUTPUT_LIMIT_BYTES)
    private val stderrCapture = BoundedCapture(PackagedSubjectProcessHarness.OUTPUT_LIMIT_BYTES)
    private val lines = ArrayBlockingQueue<LineFrame>(1024)
    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "packaged-$label-ndjson-drain").apply { isDaemon = true }
    }
    private val stdoutFuture = executor.submit(Callable {
        drainFramedLines(process.inputStream, stdoutCapture, lines)
    })
    private val stderrFuture = executor.submit(Callable {
        drainCapture(process.errorStream, stderrCapture)
    })
    private val writer = OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8).buffered()
    private val requests = mutableListOf<String>()
    private val startedNanos = System.nanoTime()
    private var closed = false
    private var evidence: ProcessObservationEvidence? = null
    private var exitCode: Int? = null
    private var descendantsTerminated: Int = 0

    init {
        awaitReadiness(readinessText)
    }

    fun request(id: String, method: String, params: JsonElement = buildJsonObject {}): JsonElement {
        check(!closed)
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) { "Invalid request correlation ID" }
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        send(request, "$method#$id")
        val response = awaitResponse(id, method)
        val error = response["error"]
        check(error == null || error is JsonNull) { "$method returned JSON-RPC error: $error" }
        return requireNotNull(response["result"]) { "$method response omitted result" }
    }

    fun notification(method: String, params: JsonElement = buildJsonObject {}) {
        check(!closed)
        val notification = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }
        send(notification, "$method#notification")
    }

    fun transcript(): TranscriptEntry = TranscriptEntry(
        label = label,
        argv = emptyList(),
        stdout = stdoutCapture.snapshot(),
        stderr = stderrCapture.snapshot(),
        exitCode = exitCode,
        durationMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis(),
        stdoutBytesObserved = stdoutCapture.totalBytes(),
        stderrBytesObserved = stderrCapture.totalBytes(),
        stdoutTruncated = stdoutCapture.truncated(),
        stderrTruncated = stderrCapture.truncated(),
        timedOut = false,
    )

    fun requestAudit(): List<String> = requests.toList()

    fun observationEvidence(): ProcessObservationEvidence = requireNotNull(evidence) {
        "Persistent process observation was requested before close"
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { writer.close() }
        if (!process.waitFor(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            descendantsTerminated += terminateTree(process)
        }
        exitCode = if (process.isAlive) null else process.exitValue()
        awaitFuture(stdoutFuture, "stdout")
        awaitFuture(stderrFuture, "stderr")
        try {
            val guardEvidence = guardLaunch.verify(stderrCapture.snapshot())
            evidence = monitor.finish(guardEvidence)
        } finally {
            monitor.close()
            executor.shutdownNow()
        }
        check(descendantsTerminated >= 0)
    }

    private fun send(request: JsonObject, audit: String) {
        checkAbsoluteDeadline()
        val encoded = Json.encodeToString(request)
        require(encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_REQUEST_BYTES)
        requests += audit
        writer.write(encoded)
        writer.newLine()
        writer.flush()
    }

    private fun awaitResponse(expectedId: String, method: String): JsonObject {
        val requestDeadline = minOf(
            absoluteDeadlineNanos,
            System.nanoTime() + REQUEST_TIMEOUT.toNanos(),
        )
        var lastProgress = stdoutCapture.lastProgressNanos()
        while (System.nanoTime() < requestDeadline) {
            val frame = lines.poll(100, TimeUnit.MILLISECONDS)
            if (frame != null) {
                check(!frame.oversized) { "$method emitted an oversized NDJSON response line" }
                val text = decodeUtf8Strict(frame.bytes, "$method response")
                val response = try {
                    Json.parseToJsonElement(text).jsonObject
                } catch (failure: Exception) {
                    throw IllegalStateException("$method emitted invalid JSON-RPC response JSON", failure)
                }
                check(response["jsonrpc"]?.jsonPrimitive?.content == "2.0") {
                    "$method response has an invalid JSON-RPC version"
                }
                val observedId = response["id"]?.jsonPrimitive?.content
                check(observedId == expectedId) {
                    "$method response correlation mismatch: expected $expectedId, observed $observedId"
                }
                return response
            }
            val progress = stdoutCapture.lastProgressNanos()
            if (progress > lastProgress) lastProgress = progress
            check(System.nanoTime() - lastProgress <= IDLE_TIMEOUT.toNanos()) {
                "$method exceeded the bounded response-idle timeout"
            }
            if (!process.isAlive && lines.isEmpty()) {
                error("$method subject process exited before its response; stderr=${stderrTextForFailure()}")
            }
        }
        terminateTree(process)
        error("$method exceeded its bounded request timeout")
    }

    private fun awaitReadiness(readinessText: String) {
        val deadline = minOf(absoluteDeadlineNanos, System.nanoTime() + STARTUP_TIMEOUT.toNanos())
        while (System.nanoTime() < deadline) {
            if (stderrCapture.snapshot().toString(StandardCharsets.UTF_8).contains(readinessText)) return
            if (!process.isAlive) {
                error("$label exited before startup readiness; stderr=${stderrTextForFailure()}")
            }
            Thread.sleep(10)
        }
        terminateTree(process)
        error("$label did not emit startup readiness within the bounded timeout")
    }

    private fun awaitFuture(future: Future<*>, stream: String) {
        try {
            future.get(DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
        } catch (failure: TimeoutException) {
            terminateTree(process)
            throw IllegalStateException("$label $stream drain did not complete", failure)
        }
    }

    private fun stderrTextForFailure(): String = stderrCapture.snapshot()
        .toString(StandardCharsets.UTF_8)
        .take(4096)

    private fun checkAbsoluteDeadline() {
        check(System.nanoTime() < absoluteDeadlineNanos) { "Absolute scenario timeout expired" }
    }

    companion object {
        val STARTUP_TIMEOUT: Duration = Duration.ofSeconds(30)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(180)
        val IDLE_TIMEOUT: Duration = Duration.ofSeconds(60)
        val SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(15)
        val DRAIN_TIMEOUT: Duration = Duration.ofSeconds(15)
        const val MAX_REQUEST_BYTES: Int = 1024 * 1024
    }
}

private data class BoundedBytes(
    val retained: ByteArray,
    val total: Long,
    val truncated: Boolean,
)

private class BoundedCapture(private val limit: Int) {
    private val retained = ByteArrayOutputStream(min(limit, 8192))
    private var total = 0L
    private var truncated = false
    private var lastProgress = System.nanoTime()

    @Synchronized
    fun append(bytes: ByteArray, offset: Int, count: Int) {
        total += count
        lastProgress = System.nanoTime()
        val remaining = limit - retained.size()
        if (remaining > 0) retained.write(bytes, offset, min(count, remaining))
        if (total > limit) truncated = true
    }

    @Synchronized
    fun snapshot(): ByteArray = retained.toByteArray()

    @Synchronized
    fun totalBytes(): Long = total

    @Synchronized
    fun truncated(): Boolean = truncated

    @Synchronized
    fun lastProgressNanos(): Long = lastProgress
}

private data class LineFrame(
    val bytes: ByteArray,
    val oversized: Boolean,
)

private fun drainBounded(stream: InputStream): BoundedBytes = stream.use { input ->
    val retained = ByteArrayOutputStream(8192)
    val buffer = ByteArray(PackagedSubjectProcessHarness.BUFFER_BYTES)
    var total = 0L
    var truncated = false
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        val remaining = PackagedSubjectProcessHarness.OUTPUT_LIMIT_BYTES - retained.size()
        if (remaining > 0) retained.write(buffer, 0, min(count, remaining))
        if (total > PackagedSubjectProcessHarness.OUTPUT_LIMIT_BYTES) truncated = true
    }
    BoundedBytes(retained.toByteArray(), total, truncated)
}

private fun drainCapture(stream: InputStream, capture: BoundedCapture) = stream.use { input ->
    val buffer = ByteArray(PackagedSubjectProcessHarness.BUFFER_BYTES)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        capture.append(buffer, 0, count)
    }
}

private const val MAX_LINE_BYTES: Int = 1024 * 1024

private fun drainFramedLines(
    stream: InputStream,
    capture: BoundedCapture,
    lines: ArrayBlockingQueue<LineFrame>,
) = stream.use { input ->
    val readBuffer = ByteArray(PackagedSubjectProcessHarness.BUFFER_BYTES)
    var line = ByteArrayOutputStream(1024)
    var oversized = false
    while (true) {
        val count = input.read(readBuffer)
        if (count < 0) break
        capture.append(readBuffer, 0, count)
        for (index in 0 until count) {
            val byte = readBuffer[index]
            if (byte == '\n'.code.toByte()) {
                val raw = line.toByteArray().let { bytes ->
                    if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.copyOf(bytes.size - 1) else bytes
                }
                check(lines.offer(LineFrame(raw, oversized), 5, TimeUnit.SECONDS)) {
                    "NDJSON response queue remained full"
                }
                line = ByteArrayOutputStream(1024)
                oversized = false
            } else if (line.size() < MAX_LINE_BYTES) {
                line.write(byte.toInt())
            } else {
                oversized = true
            }
        }
    }
    if (line.size() > 0 || oversized) {
        check(lines.offer(LineFrame(line.toByteArray(), oversized), 5, TimeUnit.SECONDS)) {
            "NDJSON response queue remained full at EOF"
        }
    }
}

internal data class ObservedProcessIdentity(
    val pid: Long,
    val commandName: String,
    val commandSha256: String,
    val startInstant: String,
    val argumentsSha256: String,
    val identitySha256: String,
) {
    override fun toString(): String =
        "pid=$pid,command=$commandName,start=$startInstant,identitySha256=$identitySha256"
}

internal sealed class ProcessFinishContractViolation(
    message: String,
    val liveDescendantIdentities: List<ObservedProcessIdentity>,
    val survivorIdentities: List<ObservedProcessIdentity>,
) : IllegalStateException(message)

internal class LiveDescendantAtFinish(
    identities: List<ObservedProcessIdentity>,
) : ProcessFinishContractViolation(
    "Live descendant observed at supervised-process finish: ${identities.joinToString(" | ")}",
    identities,
    emptyList(),
)

internal class LiveRootAtFinish(
    identity: ObservedProcessIdentity,
) : ProcessFinishContractViolation(
    "Supervised root remained live at finish: $identity",
    emptyList(),
    emptyList(),
)

internal class ProcessSurvivedFinishCleanup(
    liveDescendants: List<ObservedProcessIdentity>,
    survivors: List<ObservedProcessIdentity>,
) : ProcessFinishContractViolation(
    "Supervised process survived bounded finish cleanup: ${survivors.joinToString(" | ")}",
    liveDescendants,
    survivors,
)

internal class ProcessObservationMonitor(
    private val process: Process,
    private val embeddedJava: Path,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val failure = AtomicReference<Throwable?>()
    private val observedCommands = Collections.synchronizedSet(linkedSetOf<String>())
    private val deniedCommands = Collections.synchronizedSet(linkedSetOf<String>())
    private val unexpectedDescendants = Collections.synchronizedSet(linkedSetOf<String>())
    private val socketInodes = Collections.synchronizedSet(linkedSetOf<String>())
    private val observedHandles = Collections.synchronizedSet(linkedSetOf<ProcessHandle>())
    private val embeddedJavaPids = Collections.synchronizedSet(linkedSetOf<Long>())
    private val embeddedObserved = AtomicBoolean(false)
    private val networkObservationSucceeded = AtomicBoolean(false)
    @Volatile private var maximumResidentBytes: Long = 0
    private val linux = System.getProperty("os.name").equals("Linux", ignoreCase = true)
    private val rootPid = process.pid()
    private val thread = Thread({ monitor() }, "packaged-subject-process-tripwire").apply {
        isDaemon = true
        start()
    }

    fun finish(childGuard: ChildGuardEvidence): ProcessObservationEvidence {
        running.set(false)
        thread.join(MONITOR_STOP_TIMEOUT.toMillis())
        enforceBoundedFinishContract()
        check(!thread.isAlive) { "Subject process monitor did not stop" }
        return ProcessObservationEvidence(
            embeddedJavaObserved = embeddedObserved.get(),
            embeddedJavaSha256 = sha256(embeddedJava),
            observedCommands = synchronized(observedCommands) { observedCommands.toSet() },
            deniedCommands = synchronized(deniedCommands) { deniedCommands.toSet() },
            unexpectedDescendantCommands = synchronized(unexpectedDescendants) { unexpectedDescendants.toSet() },
            networkObservationMode = if (linux) {
                "jdk21-child-security-guard-fail-closed+linux-proc-supplement"
            } else {
                "jdk21-child-security-guard-fail-closed"
            },
            networkObservationSucceeded = childGuard.networkDenied,
            linuxSocketObservationSucceeded = !linux || networkObservationSucceeded.get(),
            socketInodes = synchronized(socketInodes) { socketInodes.toSet() },
            maximumResidentBytes = maximumResidentBytes,
            monitorFailure = failure.get()?.let { "${it::class.java.simpleName}: ${it.message}" },
            childGuard = childGuard,
            guardProcessMatchedEmbeddedJava = synchronized(embeddedJavaPids) {
                childGuard.processId in embeddedJavaPids
            },
        )
    }

    override fun close() {
        running.set(false)
        if (thread.isAlive) thread.join(MONITOR_STOP_TIMEOUT.toMillis())
    }

    private fun enforceBoundedFinishContract() {
        val root = process.toHandle()
        val handles = snapshotObservedTree(root)
        val liveDescendants = handles.filter { handle -> handle.pid() != rootPid && handle.isAlive }
        val rootLive = root.isAlive
        if (liveDescendants.isEmpty() && !rootLive) return

        val liveDescendantIdentities = liveDescendants.map(::processIdentity).sortedBy(ObservedProcessIdentity::pid)
        val trigger = if (liveDescendantIdentities.isNotEmpty()) {
            LiveDescendantAtFinish(liveDescendantIdentities)
        } else {
            LiveRootAtFinish(processIdentity(root))
        }
        val cleanupOrder = handles.distinctBy(ProcessHandle::pid).sortedWith(
            compareBy<ProcessHandle> { handle -> handle.pid() == rootPid }
                .thenByDescending(ProcessHandle::pid),
        )
        cleanupOrder.filter(ProcessHandle::isAlive).forEach { handle ->
            runCatching { handle.destroy() }.onFailure { problem -> failure.compareAndSet(null, problem) }
        }
        awaitTermination(cleanupOrder, FINISH_GRACEFUL_TIMEOUT)
        cleanupOrder.filter(ProcessHandle::isAlive).forEach { handle ->
            runCatching { handle.destroyForcibly() }.onFailure { problem -> failure.compareAndSet(null, problem) }
        }
        awaitTermination(cleanupOrder, FINISH_FORCE_TIMEOUT)
        val survivors = cleanupOrder.filter(ProcessHandle::isAlive).map(::processIdentity)
            .sortedBy(ObservedProcessIdentity::pid)
        if (survivors.isNotEmpty()) {
            throw ProcessSurvivedFinishCleanup(liveDescendantIdentities, survivors).also { failure ->
                failure.addSuppressed(trigger)
            }
        }
        throw trigger
    }

    private fun snapshotObservedTree(root: ProcessHandle): List<ProcessHandle> {
        val handles = linkedMapOf<Long, ProcessHandle>()
        fun retain(handle: ProcessHandle) {
            handles.putIfAbsent(handle.pid(), handle)
        }
        retain(root)
        synchronized(observedHandles) { observedHandles.forEach(::retain) }
        handles.values.toList().filter(ProcessHandle::isAlive).forEach { handle ->
            runCatching {
                handle.descendants().use { descendants -> descendants.forEach(::retain) }
            }.onFailure { problem -> failure.compareAndSet(null, problem) }
        }
        return handles.values.toList()
    }

    private fun processIdentity(handle: ProcessHandle): ObservedProcessIdentity {
        val info = handle.info()
        val command = info.command().orElse("UNAVAILABLE")
        val commandName = command.substringAfterLast('/').substringAfterLast('\\').ifBlank { "unknown" }
        val startInstant = info.startInstant().map { instant -> instant.toString() }.orElse("UNAVAILABLE")
        val arguments = info.arguments().map { values -> values.joinToString("\u0000") }.orElse("UNAVAILABLE")
        val commandSha256 = sha256(command.toByteArray(StandardCharsets.UTF_8))
        val argumentsSha256 = sha256(arguments.toByteArray(StandardCharsets.UTF_8))
        val identitySha256 = sha256(
            listOf(handle.pid().toString(), startInstant, commandSha256, argumentsSha256)
                .joinToString("\u0000").toByteArray(StandardCharsets.UTF_8),
        )
        return ObservedProcessIdentity(
            pid = handle.pid(),
            commandName = commandName,
            commandSha256 = commandSha256,
            startInstant = startInstant,
            argumentsSha256 = argumentsSha256,
            identitySha256 = identitySha256,
        )
    }

    private fun monitor() {
        try {
            while (running.get()) {
                val root = process.toHandle()
                val handles = buildList {
                    add(root)
                    root.descendants().use { descendants -> descendants.forEach(::add) }
                }
                handles.forEach { handle -> observe(handle, handle.pid() != rootPid) }
                if (!root.isAlive && handles.none(ProcessHandle::isAlive)) break
                Thread.sleep(5)
            }
        } catch (problem: Throwable) {
            failure.compareAndSet(null, problem)
        }
    }

    private fun observe(handle: ProcessHandle, descendant: Boolean) {
        observedHandles += handle
        val commandPath = commandPath(handle)
        val commandName = commandPath?.fileName?.toString()?.lowercase()
            ?: handle.info().command().orElse("unknown").substringAfterLast('/').substringAfterLast('\\').lowercase()
        observedCommands += commandName
        if (commandName in DENIED_COMMANDS) deniedCommands += commandName
        if (commandName in setOf("java", "java.exe")) {
            val normalized = commandPath?.toAbsolutePath()?.normalize()
            if (normalized != null && sameFileSafely(normalized, embeddedJava)) {
                embeddedObserved.set(true)
                embeddedJavaPids += handle.pid()
            } else {
                deniedCommands += "external-$commandName"
            }
        } else if (descendant && commandName !in ALLOWED_BOOTSTRAP_COMMANDS) {
            unexpectedDescendants += commandName
        }
        if (linux) {
            observeLinuxSockets(handle.pid())
            observeLinuxMemory(handle.pid())
        }
    }

    private fun commandPath(handle: ProcessHandle): Path? {
        if (linux) {
            val proc = Path.of("/proc", handle.pid().toString(), "exe")
            runCatching { return proc.toRealPath() }
        }
        val value = handle.info().command().orElse(null) ?: return null
        return runCatching { Path.of(value).toAbsolutePath().normalize() }.getOrNull()
    }

    private fun observeLinuxSockets(pid: Long) {
        val processRoot = Path.of("/proc", pid.toString())
        val descriptors = processRoot.resolve("fd")
        if (!Files.isDirectory(descriptors, LinkOption.NOFOLLOW_LINKS)) return
        try {
            Files.list(descriptors).use { paths ->
                paths.forEach { descriptor ->
                    val target = runCatching { Files.readSymbolicLink(descriptor).toString() }.getOrNull()
                        ?: return@forEach
                    if (target.startsWith("socket:[") && target.endsWith(']')) {
                        val inode = target.removePrefix("socket:[").removeSuffix("]")
                        if (isInternetSocket(processRoot, inode)) socketInodes += "inet-socket:[$inode]"
                    }
                }
            }
            networkObservationSucceeded.set(true)
        } catch (_: IOException) {
            // /proc entries can disappear or be PID-reused between liveness and descriptor reads.
            // Other 5 ms samples remain authoritative; the completed-sample bit fails closed if none succeeds.
        }
    }

    private fun isInternetSocket(processRoot: Path, inode: String): Boolean =
        listOf("tcp", "tcp6", "udp", "udp6").any { protocol ->
            val table = processRoot.resolve("net/$protocol")
            if (!Files.isRegularFile(table, LinkOption.NOFOLLOW_LINKS)) return@any false
            runCatching {
                Files.readAllLines(table, StandardCharsets.US_ASCII).drop(1).any { line ->
                    val fields = line.trim().split(Regex("\\s+"))
                    fields.getOrNull(9) == inode
                }
            }.getOrDefault(false)
        }

    private fun observeLinuxMemory(pid: Long) {
        val status = Path.of("/proc", pid.toString(), "status")
        if (!Files.isRegularFile(status, LinkOption.NOFOLLOW_LINKS)) return
        val statusLines = try {
            Files.readAllLines(status, StandardCharsets.UTF_8)
        } catch (_: java.nio.file.NoSuchFileException) {
            // A process may exit after the no-follow file-kind check and before the read.
            return
        }
        val residentKilobytes = statusLines
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?: return
        maximumResidentBytes = maxOf(maximumResidentBytes, residentKilobytes * 1024)
    }

    private fun sameFileSafely(first: Path, second: Path): Boolean = runCatching {
        Files.isSameFile(first, second)
    }.getOrElse {
        first.toAbsolutePath().normalize() == second.toAbsolutePath().normalize()
    }

    companion object {
        private val DENIED_COMMANDS = setOf(
            "mvn", "mvn.cmd", "mvnw", "mvnw.cmd",
            "gradle", "gradle.bat", "gradlew", "gradlew.bat",
            "git", "git.exe", "curl", "curl.exe", "wget", "wget.exe",
            "npm", "npm.cmd", "npx", "npx.cmd",
        )
        private val ALLOWED_BOOTSTRAP_COMMANDS = setOf(
            "env", "sh", "dash", "bash", "dirname", "cmd.exe", "java", "java.exe", "unknown",
        )
        private val MONITOR_STOP_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val FINISH_GRACEFUL_TIMEOUT: Duration = Duration.ofSeconds(3)
        private val FINISH_FORCE_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}

internal fun terminateTree(process: Process): Int {
    val descendants = process.toHandle().descendants().use { stream -> stream.toList() }.asReversed()
    val handles = (descendants + process.toHandle()).distinct()
    handles.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy)
    awaitTermination(handles, Duration.ofSeconds(3))
    handles.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly)
    awaitTermination(handles, Duration.ofSeconds(5))
    check(handles.none(ProcessHandle::isAlive)) { "A supervised subject process survived cleanup" }
    return descendants.size
}

private fun awaitTermination(handles: List<ProcessHandle>, timeout: Duration) {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (handles.any(ProcessHandle::isAlive) && System.nanoTime() < deadline) Thread.sleep(10)
}

private fun BoundedProcessResult.toTranscript(arguments: List<String>): TranscriptEntry = TranscriptEntry(
    label = label,
    argv = arguments,
    stdout = stdout,
    stderr = stderr,
    exitCode = exitCode,
    durationMillis = durationMillis,
    stdoutBytesObserved = stdoutBytesObserved,
    stderrBytesObserved = stderrBytesObserved,
    stdoutTruncated = stdoutTruncated,
    stderrTruncated = stderrTruncated,
    timedOut = timedOut,
)

internal fun decodeUtf8Strict(bytes: ByteArray, subject: String): String = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes))
        .toString()
} catch (failure: Exception) {
    throw IllegalStateException("$subject is not strict UTF-8", failure)
}
