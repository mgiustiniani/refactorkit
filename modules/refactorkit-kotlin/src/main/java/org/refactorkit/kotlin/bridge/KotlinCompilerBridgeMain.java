package org.refactorkit.kotlin.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Minimal isolated entry point for the explicitly supplied Kotlin compiler. */
public final class KotlinCompilerBridgeMain {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final long MAX_XML_BYTES = 6L * 1024L * 1024L;
    private static final Set<String> VALUE_OPTIONS = immutableSet(
        "-language-version", "-api-version", "-jvm-target", "-jdk-home", "-classpath", "-d"
    );
    private static final Set<String> FLAG_OPTIONS = immutableSet("-no-stdlib", "-no-reflect", "-Xuse-k2");

    private KotlinCompilerBridgeMain() {}

    public static void main(String[] args) {
        try {
            if (args.length < 4 || !SHA256.matcher(args[0]).matches() || !"--".equals(args[2])) {
                fail("kotlin.bridgeArgumentsInvalid");
                return;
            }
            Path overlay = canonicalDirectory(Paths.get(args[1]));
            String[] compilerArguments = new String[args.length - 3];
            System.arraycopy(args, 3, compilerArguments, 0, compilerArguments.length);
            BridgeInputs inputs = validateCompilerArguments(overlay, compilerArguments);

            Class<?> compilerClass = Class.forName("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", true,
                Thread.currentThread().getContextClassLoader());
            Class<?> servicesClass = Class.forName("org.jetbrains.kotlin.config.Services", true,
                Thread.currentThread().getContextClassLoader());
            Object compiler = compilerClass.getConstructor().newInstance();
            Field emptyField = servicesClass.getField("EMPTY");
            Object services = emptyField.get(null);
            Method execute = compilerClass.getMethod("execAndOutputXml", PrintStream.class, servicesClass, String[].class);

            BoundedOutputStream bounded = new BoundedOutputStream(MAX_XML_BYTES);
            Object exitCode;
            try (PrintStream output = new PrintStream(bounded, false, "UTF-8")) {
                exitCode = execute.invoke(compiler, output, services, compilerArguments);
            }
            String xmlBase64 = Base64.getEncoder().encodeToString(bounded.toByteArray());
            String exitName = exitCode instanceof Enum<?> ? ((Enum<?>) exitCode).name() : "UNKNOWN";
            String symbolPayload = "\"symbolsComplete\":false,\"symbolFailure\":\"kotlin.symbolCompilationFailed\"";
            if ("OK".equals(exitName)) {
                try {
                    symbolPayload = renderSymbols(KotlinCompilerSymbolExtractor.extract(inputs.sources, inputs.outputDirectory));
                } catch (KotlinCompilerSymbolExtractor.SymbolExtractionException failure) {
                    symbolPayload = "\"symbolsComplete\":false,\"symbolFailure\":\"" + escape(failure.getMessage()) + "\"";
                } catch (Throwable failure) {
                    symbolPayload = "\"symbolsComplete\":false,\"symbolFailure\":\"kotlin.symbolExtractionFailed\"";
                }
            }
            System.out.print("{\"schema\":1,\"complete\":true,\"snapshotHash\":\"" + args[0] +
                "\",\"exitCode\":\"" + escape(exitName) + "\",\"xmlBase64\":\"" + xmlBase64 + "\"," +
                symbolPayload + "}");
        } catch (IllegalArgumentException failure) {
            fail("kotlin.bridgeArgumentsInvalid");
        } catch (OutputLimitException failure) {
            fail("kotlin.compilerOutputLimit");
        } catch (Throwable failure) {
            fail("kotlin.compilerBridgeFailed");
        }
    }

    private static BridgeInputs validateCompilerArguments(Path overlay, String[] arguments) throws IOException {
        if (arguments.length == 0 || arguments.length > 4096) throw new IllegalArgumentException();
        Set<String> seen = new HashSet<>();
        java.util.List<Path> sources = new java.util.ArrayList<Path>();
        Path outputDirectory = null;
        for (int index = 0; index < arguments.length; index++) {
            String value = arguments[index];
            if (value.indexOf('\0') >= 0 || value.length() > 4096) throw new IllegalArgumentException();
            if (VALUE_OPTIONS.contains(value)) {
                if (!seen.add(value) || ++index >= arguments.length) throw new IllegalArgumentException();
                String optionValue = arguments[index];
                if (optionValue.indexOf('\0') >= 0 || optionValue.length() > 32768) throw new IllegalArgumentException();
                if ("-d".equals(value)) outputDirectory = requireInsideOverlay(overlay, Paths.get(optionValue), false);
                if ("-jdk-home".equals(value)) canonicalDirectory(Paths.get(optionValue));
                if ("-classpath".equals(value)) validateClasspath(optionValue);
            } else if (FLAG_OPTIONS.contains(value)) {
                if (!seen.add(value)) throw new IllegalArgumentException();
            } else if (value.startsWith("-")) {
                throw new IllegalArgumentException();
            } else {
                Path source = requireInsideOverlay(overlay, Paths.get(value), true);
                if (!source.getFileName().toString().endsWith(".kt")) throw new IllegalArgumentException();
                sources.add(source);
            }
        }
        if (sources.isEmpty() || outputDirectory == null || !seen.contains("-d") || !seen.contains("-jdk-home") ||
            !seen.contains("-jvm-target") || !seen.contains("-language-version") ||
            !seen.contains("-api-version") || !seen.contains("-no-stdlib") || !seen.contains("-no-reflect")) {
            throw new IllegalArgumentException();
        }
        return new BridgeInputs(Collections.unmodifiableList(sources), outputDirectory);
    }

    private static String renderSymbols(java.util.List<KotlinCompilerSymbolExtractor.ExtractedSymbol> symbols) {
        StringBuilder json = new StringBuilder("\"symbolsComplete\":true,\"symbols\":[");
        for (int index = 0; index < symbols.size(); index++) {
            if (index > 0) json.append(',');
            KotlinCompilerSymbolExtractor.ExtractedSymbol symbol = symbols.get(index);
            json.append("{\"identity\":\"").append(escape(symbol.identity()))
                .append("\",\"name\":\"").append(escape(symbol.name()))
                .append("\",\"kind\":\"").append(escape(symbol.kind()))
                .append("\",\"path\":\"").append(escape(symbol.path()))
                .append("\",\"selectionText\":\"").append(escape(symbol.selectionText()))
                .append("\",\"startOffset\":").append(symbol.startOffset())
                .append(",\"endOffset\":").append(symbol.endOffset()).append('}');
        }
        return json.append(']').toString();
    }

    private static void validateClasspath(String value) throws IOException {
        if (value.trim().isEmpty()) return;
        String[] entries = value.split(Pattern.quote(System.getProperty("path.separator")), -1);
        if (entries.length > 4096) throw new IllegalArgumentException();
        for (String entry : entries) {
            if (entry.trim().isEmpty()) throw new IllegalArgumentException();
            Path path = Paths.get(entry).toAbsolutePath().normalize();
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path) ||
                !path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
                throw new IllegalArgumentException();
            }
            path.toRealPath();
        }
    }

    private static Path canonicalDirectory(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized) || Files.isSymbolicLink(normalized)) throw new IllegalArgumentException();
        return normalized.toRealPath();
    }

    private static Path requireInsideOverlay(Path overlay, Path path, boolean regularFile) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)) throw new IllegalArgumentException();
        if (regularFile && !Files.isRegularFile(normalized)) throw new IllegalArgumentException();
        if (!regularFile && !Files.isDirectory(normalized)) throw new IllegalArgumentException();
        Path canonical = normalized.toRealPath();
        if (!canonical.startsWith(overlay) || canonical.equals(overlay)) throw new IllegalArgumentException();
        return canonical;
    }

    private static Set<String> immutableSet(String... values) {
        return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(values)));
    }

    private static void fail(String code) {
        System.out.print("{\"schema\":1,\"complete\":false,\"failure\":\"" + escape(code) + "\"}");
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\': escaped.append("\\\\"); break;
                case '"': escaped.append("\\\""); break;
                case '\b': escaped.append("\\b"); break;
                case '\f': escaped.append("\\f"); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (character < 0x20) escaped.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
                    else escaped.append(character);
            }
        }
        return escaped.toString();
    }

    private static final class BridgeInputs {
        private final java.util.List<Path> sources;
        private final Path outputDirectory;

        private BridgeInputs(java.util.List<Path> sources, Path outputDirectory) {
            this.sources = sources;
            this.outputDirectory = outputDirectory;
        }
    }

    private static final class BoundedOutputStream extends OutputStream {
        private final long limit;
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();

        private BoundedOutputStream(long limit) { this.limit = limit; }

        @Override public void write(int value) throws IOException {
            if ((long) delegate.size() + 1L > limit) throw new OutputLimitException();
            delegate.write(value);
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if ((long) delegate.size() + length > limit) throw new OutputLimitException();
            delegate.write(bytes, offset, length);
        }

        private byte[] toByteArray() { return delegate.toByteArray(); }
    }

    private static final class OutputLimitException extends IOException {}
}
