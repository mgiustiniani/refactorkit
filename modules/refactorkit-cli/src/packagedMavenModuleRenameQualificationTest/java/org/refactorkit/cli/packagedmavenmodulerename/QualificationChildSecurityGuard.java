package org.refactorkit.cli.packagedmavenmodulerename;

import java.io.FilePermission;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.SocketPermission;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.LinkPermission;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.Permission;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * JDK 21-only qualification agent that installs the fail-closed child-process guard before product main.
 *
 * <p>The class is copied into a test-generated javaagent JAR. It is deliberately independent of every
 * production class and is injected only through the scrubbed {@code JAVA_TOOL_OPTIONS} owned by the
 * packaged Story-BDD harness.</p>
 */
public final class QualificationChildSecurityGuard {
    static final String POLICY_VERSION = "packaged-story-bdd-jdk21-v1";
    static final String PROPERTY_PREFIX = "refactorkit.qualification.guard.";
    static final String INSTALLED_MARKER = "REFRACTORKIT_QUALIFICATION_CHILD_GUARD_INSTALLED";

    private QualificationChildSecurityGuard() {
    }

    public static void premain(String ignoredAgentArguments, java.lang.instrument.Instrumentation ignoredInstrumentation) {
        try {
            Configuration configuration = Configuration.load();
            int javaFeature = javaFeature();
            if (javaFeature != 21) {
                throw new IllegalStateException("The packaged qualification child guard requires JDK 21 exactly");
            }
            FailClosedSecurityManager guard = new FailClosedSecurityManager(configuration);
            System.setSecurityManager(guard);
            if (System.getSecurityManager() != guard) {
                throw new IllegalStateException("The packaged qualification child guard was not installed");
            }

            Properties attestation = new Properties();
            attestation.setProperty("policyVersion", POLICY_VERSION);
            attestation.setProperty("guardClass", FailClosedSecurityManager.class.getName());
            attestation.setProperty("javaFeature", Integer.toString(javaFeature));
            attestation.setProperty("processId", Long.toString(currentProcessId()));
            attestation.setProperty("tokenSha256", sha256(configuration.token.getBytes(StandardCharsets.UTF_8)));
            attestation.setProperty("workspacePathSha256", pathSha256(configuration.workspace));
            attestation.setProperty("homePathSha256", pathSha256(configuration.home));
            attestation.setProperty("temporaryPathSha256", pathSha256(configuration.temporary));
            attestation.setProperty("installedRuntimePathSha256", pathSha256(configuration.installedRuntime));
            attestation.setProperty("readOnlyCandidatePathSha256", pathSha256(configuration.readOnlyCandidate));
            attestation.setProperty("launcherBoundaryAlreadyStarted", "true");
            attestation.setProperty("socketConnectDenied", Boolean.toString(expectDenied(
                "socket connect", () -> System.getSecurityManager().checkConnect("127.0.0.1", 9)
            )));
            attestation.setProperty("socketListenDenied", Boolean.toString(expectDenied(
                "socket listen", () -> System.getSecurityManager().checkListen(0)
            )));
            attestation.setProperty("socketAcceptDenied", Boolean.toString(expectDenied(
                "socket accept", () -> System.getSecurityManager().checkAccept("127.0.0.1", 9)
            )));
            attestation.setProperty("socketMulticastDenied", Boolean.toString(expectDenied(
                "socket multicast", () -> System.getSecurityManager().checkMulticast(
                    InetAddress.getByAddress(new byte[] {(byte) 224, 0, 0, 1})
                )
            )));
            attestation.setProperty("processExecDenied", Boolean.toString(expectDenied(
                "process exec", () -> new ProcessBuilder(
                    configuration.installedRuntime.resolve("bin").resolve(javaExecutableName()).toString(),
                    "-version"
                ).start()
            )));
            attestation.setProperty("outsideWriteDenied", Boolean.toString(expectDenied(
                "outside write", () -> {
                    Path probe = configuration.installedRuntime.resolve("refactorkit-qualification-write-probe");
                    try (OutputStream ignored = Files.newOutputStream(
                        probe,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE
                    )) {
                        throw new IllegalStateException("Outside-write probe unexpectedly opened");
                    }
                }
            )));
            attestation.setProperty("readOnlyCandidateWriteDenied", Boolean.toString(expectDenied(
                "read-only candidate write", () -> {
                    Path probe = configuration.readOnlyCandidate.resolve("refactorkit-qualification-write-probe");
                    try (OutputStream ignored = Files.newOutputStream(
                        probe,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE
                    )) {
                        throw new IllegalStateException("Candidate-write probe unexpectedly opened");
                    }
                }
            )));
            attestation.setProperty(
                "readOnlyCandidateReportsNotWritable",
                Boolean.toString(!configuration.readOnlyCandidate.toFile().canWrite())
            );
            attestation.setProperty("installedRuntimeReadDenied", Boolean.toString(expectDenied(
                "installed runtime read",
                () -> Files.readAllBytes(configuration.installedRuntime.resolve("release"))
            )));
            attestation.setProperty("symbolicAndHardLinksDenied", Boolean.toString(expectDenied(
                "symbolic link permission", () -> System.getSecurityManager().checkPermission(
                    new LinkPermission("symbolic")
                )
            ) && expectDenied(
                "hard link permission", () -> System.getSecurityManager().checkPermission(
                    new LinkPermission("hard")
                )
            )));

            requireTrueAttestations(attestation);
            guard.completeStartupSelfTest();
            try (OutputStream output = Files.newOutputStream(
                configuration.attestation,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )) {
                attestation.store(output, "RefactorKit packaged Story-BDD child guard attestation");
            }
            String tokenHash = attestation.getProperty("tokenSha256");
            System.err.println(INSTALLED_MARKER + " policyVersion=" + POLICY_VERSION + " tokenSha256=" + tokenHash);
        } catch (Throwable failure) {
            System.err.println("REFRACTORKIT_QUALIFICATION_CHILD_GUARD_INSTALLATION_FAILED: " + failure);
            failure.printStackTrace(System.err);
            throw new IllegalStateException("Fail-closed packaged qualification child guard installation failed", failure);
        }
    }

    private static boolean expectDenied(String subject, CheckedAction action) throws Exception {
        try {
            action.run();
        } catch (SecurityException expected) {
            return true;
        }
        throw new IllegalStateException("The child guard did not deny " + subject);
    }

    private static void requireTrueAttestations(Properties attestation) {
        List<String> required = Arrays.asList(
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
            "symbolicAndHardLinksDenied"
        );
        for (String name : required) {
            if (!"true".equals(attestation.getProperty(name))) {
                throw new IllegalStateException("Missing fail-closed child guard attestation: " + name);
            }
        }
    }

    private static int javaFeature() {
        String specificationVersion = System.getProperty("java.specification.version");
        if (specificationVersion == null || !specificationVersion.matches("[0-9]+")) {
            throw new IllegalStateException("The child guard cannot identify the JDK feature version");
        }
        return Integer.parseInt(specificationVersion);
    }

    private static long currentProcessId() {
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        String pid = runtimeName.substring(0, runtimeName.indexOf('@'));
        if (!pid.matches("[0-9]+")) {
            throw new IllegalStateException("The child guard cannot identify its process ID");
        }
        return Long.parseLong(pid);
    }

    private static String javaExecutableName() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")
            ? "java.exe"
            : "java";
    }

    private static String pathSha256(Path path) throws Exception {
        return sha256(path.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder value = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        }
        return value.toString();
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }

    private static final class Configuration {
        private final Path workspace;
        private final Path home;
        private final Path temporary;
        private final Path installedRuntime;
        private final Path readOnlyCandidate;
        private final Path attestation;
        private final String token;

        private Configuration(
            Path workspace,
            Path home,
            Path temporary,
            Path installedRuntime,
            Path readOnlyCandidate,
            Path attestation,
            String token
        ) throws IOException {
            this.workspace = requiredDirectory(workspace, "workspace");
            this.home = requiredDirectory(home, "home");
            this.temporary = requiredDirectory(temporary, "temporary");
            this.installedRuntime = requiredDirectory(installedRuntime, "installed runtime");
            this.readOnlyCandidate = requiredDirectory(readOnlyCandidate, "read-only extracted candidate");
            this.attestation = requiredNewFilePath(attestation, this.temporary);
            this.token = token;
        }

        private static Configuration load() throws IOException {
            String required = requiredProperty("required");
            if (!"true".equals(required)) {
                throw new IllegalStateException("The child guard required flag is not true");
            }
            String policyVersion = requiredProperty("policyVersion");
            if (!POLICY_VERSION.equals(policyVersion)) {
                throw new IllegalStateException("Unexpected child guard policy version: " + policyVersion);
            }
            String token = requiredProperty("token");
            if (!token.matches("[0-9a-f]{64}")) {
                throw new IllegalStateException("The child guard token is not a 256-bit lowercase hex value");
            }
            return new Configuration(
                Paths.get(requiredProperty("workspace")),
                Paths.get(requiredProperty("home")),
                Paths.get(requiredProperty("temporary")),
                Paths.get(requiredProperty("installedRuntime")),
                Paths.get(requiredProperty("readOnlyCandidate")),
                Paths.get(requiredProperty("attestation")),
                token
            );
        }

        private static String requiredProperty(String suffix) {
            String name = PROPERTY_PREFIX + suffix;
            String value = System.getProperty(name);
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalStateException("Missing child guard property " + name);
            }
            return value;
        }

        private static Path requiredDirectory(Path path, String subject) throws IOException {
            Path normalized = path.toAbsolutePath().normalize();
            if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
                throw new IllegalStateException("Child guard " + subject + " is not a no-follow directory");
            }
            return normalized.toRealPath();
        }

        private static Path requiredNewFilePath(Path path, Path temporaryRoot) throws IOException {
            Path normalized = path.toAbsolutePath().normalize();
            if (!normalized.startsWith(temporaryRoot.toAbsolutePath().normalize())) {
                throw new IllegalStateException("Child guard attestation escaped isolated temporary storage");
            }
            if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Child guard attestation path already exists");
            }
            Path parent = normalized.getParent();
            if (parent == null || !Files.isSameFile(parent, temporaryRoot)) {
                throw new IllegalStateException("Child guard attestation parent is not the isolated temporary root");
            }
            return normalized;
        }
    }

    @SuppressWarnings("removal")
    private static final class FailClosedSecurityManager extends SecurityManager {
        private static final List<String> WRITE_ACTIONS = Arrays.asList(
            "write", "delete", "execute"
        );

        private final Path workingDirectory;
        private final List<Path> allowedWriteRoots;
        private final List<Path> deniedInstalledRuntimeRoots;
        private final Path readOnlyCandidateRoot;
        private volatile boolean startupSelfTest = true;

        private FailClosedSecurityManager(Configuration configuration) {
            this.workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
            this.allowedWriteRoots = Arrays.asList(
                configuration.workspace,
                configuration.home,
                configuration.temporary
            );
            Path configured = Paths.get(System.getProperty(PROPERTY_PREFIX + "installedRuntime"))
                .toAbsolutePath()
                .normalize();
            this.deniedInstalledRuntimeRoots = configured.equals(configuration.installedRuntime)
                ? Arrays.asList(configured)
                : Arrays.asList(configured, configuration.installedRuntime);
            this.readOnlyCandidateRoot = configuration.readOnlyCandidate;
        }

        private void completeStartupSelfTest() {
            startupSelfTest = false;
        }

        @Override
        public void checkPermission(Permission permission) {
            if (permission instanceof SocketPermission) {
                deny("socket permission " + permission.getActions());
            }
            if (permission instanceof LinkPermission) {
                deny("filesystem link permission " + permission.getName());
            }
            if (permission instanceof FilePermission) {
                FilePermission filePermission = (FilePermission) permission;
                String actions = filePermission.getActions().toLowerCase(Locale.ROOT);
                if (actions.contains("execute")) {
                    deny("process execution");
                }
                if (containsAny(actions, WRITE_ACTIONS)) {
                    assertAllowedWrite(filePermission.getName());
                }
                if (actions.contains("read")) {
                    assertNotInstalledRuntime(filePermission.getName());
                }
            }
            if (permission instanceof RuntimePermission && "setSecurityManager".equals(permission.getName())) {
                deny("security-manager replacement");
            }
        }

        @Override
        public void checkPermission(Permission permission, Object context) {
            checkPermission(permission);
        }

        @Override
        public void checkConnect(String host, int port) {
            deny("socket connect");
        }

        @Override
        public void checkConnect(String host, int port, Object context) {
            deny("socket connect");
        }

        @Override
        public void checkListen(int port) {
            deny("socket listen");
        }

        @Override
        public void checkAccept(String host, int port) {
            deny("socket accept");
        }

        @Override
        public void checkMulticast(InetAddress address) {
            deny("socket multicast");
        }

        @Override
        @SuppressWarnings("deprecation")
        public void checkMulticast(InetAddress address, byte ttl) {
            deny("socket multicast");
        }

        @Override
        public void checkExec(String command) {
            deny("process execution after the already-started launcher boundary");
        }

        @Override
        public void checkRead(String file) {
            assertNotInstalledRuntime(file);
        }

        @Override
        public void checkWrite(String file) {
            assertAllowedWrite(file);
        }

        @Override
        public void checkDelete(String file) {
            assertAllowedWrite(file);
        }

        private void assertAllowedWrite(String rawPath) {
            Path candidate = effectiveWritePath(rawPath);
            for (Path root : allowedWriteRoots) {
                if (candidate.startsWith(root)) {
                    return;
                }
            }
            if (candidate.startsWith(readOnlyCandidateRoot) && isReadOnlyCapabilityProbe()) {
                return;
            }
            deny("write outside disposable workspace, isolated home, or isolated temporary storage");
        }

        private boolean isReadOnlyCapabilityProbe() {
            for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
                if (element.getClassName().equals("java.io.File") && element.getMethodName().equals("canWrite")) {
                    return true;
                }
                if (element.getClassName().equals("java.nio.file.Files") &&
                    element.getMethodName().equals("isWritable")) {
                    return true;
                }
                if (element.getClassName().startsWith("sun.nio.fs.") &&
                    (element.getMethodName().equals("checkAccess") ||
                        element.getMethodName().equals("isWritable"))) {
                    return true;
                }
            }
            return false;
        }

        private Path effectiveWritePath(String rawPath) {
            Path lexical = normalizedPath(stripFilePermissionWildcard(rawPath));
            Path existing = lexical;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null) {
                deny("write with no admitted existing ancestor");
            }
            try {
                Path realExisting = existing.toRealPath();
                return realExisting.resolve(existing.relativize(lexical)).normalize();
            } catch (IOException failure) {
                throw new SecurityException("REFRACTORKIT_QUALIFICATION_GUARD_DENIED: unresolved write target", failure);
            }
        }

        private void assertNotInstalledRuntime(String rawPath) {
            Path candidate = normalizedPath(stripFilePermissionWildcard(rawPath));
            for (Path root : deniedInstalledRuntimeRoots) {
                if (candidate.startsWith(root)) {
                    deny("access to the installed test runtime");
                }
            }
        }

        private Path normalizedPath(String rawPath) {
            if ("<<ALL FILES>>".equals(rawPath)) {
                deny("unbounded filesystem authority");
            }
            Path path = FileSystems.getDefault().getPath(rawPath);
            if (!path.isAbsolute()) {
                path = workingDirectory.resolve(path);
            }
            return path.toAbsolutePath().normalize();
        }

        private static String stripFilePermissionWildcard(String rawPath) {
            if (rawPath.endsWith("/-") || rawPath.endsWith("/*")) {
                return rawPath.substring(0, rawPath.length() - 2);
            }
            return rawPath;
        }

        private static boolean containsAny(String value, List<String> candidates) {
            for (String candidate : candidates) {
                if (value.contains(candidate)) {
                    return true;
                }
            }
            return false;
        }

        private void deny(String subject) {
            if (!startupSelfTest) {
                StackTraceElement caller = firstProductCaller();
                System.err.println(
                    "REFRACTORKIT_QUALIFICATION_GUARD_UNEXPECTED_DENIAL subject=" + subject +
                        " caller=" + caller.getClassName() + "." + caller.getMethodName()
                );
            }
            throw new SecurityException("REFRACTORKIT_QUALIFICATION_GUARD_DENIED: " + subject);
        }

        private StackTraceElement firstProductCaller() {
            StackTraceElement fallback = null;
            for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
                String className = element.getClassName();
                if (className.equals(Thread.class.getName()) ||
                    className.equals(FailClosedSecurityManager.class.getName()) ||
                    className.equals(QualificationChildSecurityGuard.class.getName()) ||
                    className.equals(SecurityManager.class.getName())) {
                    continue;
                }
                if (fallback == null) {
                    fallback = element;
                }
                if (className.startsWith("org.refactorkit.")) {
                    return element;
                }
            }
            if (fallback != null) {
                return fallback;
            }
            throw new IllegalStateException("The child guard could not identify a denied caller");
        }
    }
}
