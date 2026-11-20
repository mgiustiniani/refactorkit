import com.sun.source.util.JavacTask;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;

/** ACL for the selected SDK's public compiler API. No processors, generation or application loading. */
public final class NativeJavaDiagnosticsWorker {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IOException("Unexpected compiler arguments");
        byte[] response = NativeJavaDiagnosticAnalysis.capture(new DataInputStream(System.in));
        System.out.write(response);
        System.out.flush();
    }
}

/** Read-only native acquisition service, separate from the stdio entry point. */
final class NativeJavaDiagnosticAnalysis {
    private static final int MAGIC = 0x524b4431;

    static byte[] capture(DataInputStream in) throws Exception {
        if (in.readInt() != MAGIC || !Path.of(in.readUTF()).toRealPath().equals(Path.of(System.getProperty("java.home")).toRealPath()))
            throw new IOException("SDK identity mismatch");
        Path root = Path.of(in.readUTF()).toRealPath();
        List<Path> sources = paths(in, root, true, 10000);
        List<Path> roots = paths(in, root, false, 1024);
        List<Path> classpath = paths(in, null, false, 1024);
        boolean release = in.readBoolean();
        int languageRelease = in.readInt();
        if ((languageRelease != 25 && languageRelease != 26) || Runtime.version().feature() != languageRelease)
            throw new IOException("Unqualified compiler release");
        if (sources.isEmpty() || in.read() != -1) throw new IOException("Incomplete request");
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IOException("Compiler unavailable");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var standard = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            standard.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
            standard.setLocationFromPaths(StandardLocation.SOURCE_PATH, roots);
            standard.setLocationFromPaths(StandardLocation.ANNOTATION_PROCESSOR_PATH, List.of());
            var readonly = new ForwardingJavaFileManager<StandardJavaFileManager>(standard) {
                @Override public JavaFileObject getJavaFileForOutput(Location l, String n, JavaFileObject.Kind k, FileObject s) throws IOException {
                    throw new IOException("Compiler output forbidden");
                }
                @Override public FileObject getFileForOutput(Location l, String p, String n, FileObject s) throws IOException {
                    throw new IOException("Compiler output forbidden");
                }
            };
            var options = new ArrayList<>(List.of("-proc:none", "-implicit:none", "-encoding", "UTF-8", "-Xprefer:source", "-Xmaxerrs", "10001"));
            String level = Integer.toString(languageRelease);
            options.addAll(release ? List.of("--release", level) : List.of("-source", level, "-target", level));
            var task = (JavacTask) compiler.getTask(new StringWriter(), readonly, diagnostics, options, null,
                standard.getJavaFileObjectsFromPaths(sources));
            var parsed = new LinkedHashSet<Path>();
            for (var unit : task.parse()) parsed.add(Path.of(unit.getSourceFile().toUri()).toRealPath());
            if (!parsed.equals(new LinkedHashSet<>(sources))) throw new IOException("Incomplete explicit source inventory");
            task.analyze(); // Never generate() or CompilationTask.call().
            var errors = diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).toList();
            if (errors.size() >= 10001) throw new IOException("Incomplete diagnostic inventory");
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(parsed.size());
            for (Path path : parsed) out.writeUTF(root.relativize(path).toString());
            out.writeInt(errors.size());
            for (var diagnostic : errors) {
                if (diagnostic.getSource() == null) throw new IOException("Unlocated compiler error");
                Path path = Path.of(diagnostic.getSource().toUri()).toRealPath();
                if (!path.startsWith(root)) throw new IOException("Unowned diagnostic source");
                String text = diagnostic.getSource().getCharContent(true).toString();
                long start = diagnostic.getStartPosition(), end = diagnostic.getEndPosition();
                if (start < 0 || end < start || end > text.length()) throw new IOException("Unlocated compiler range");
                out.writeUTF(root.relativize(path).toString());
                out.writeInt((int) start); out.writeInt((int) end);
                out.writeUTF(diagnostic.getCode()); out.writeUTF(diagnostic.getMessage(Locale.ROOT));
            }
            out.flush();
            if (bytes.size() > 16 * 1024 * 1024) throw new IOException("Diagnostic response limit");
            return bytes.toByteArray();
        }
    }

    private static List<Path> paths(DataInputStream input, Path root, boolean source, int maximum) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > maximum) throw new IOException("Path limit");
        var paths = new ArrayList<Path>();
        var unique = new HashSet<Path>();
        for (int i = 0; i < count; i++) {
            String value = input.readUTF();
            Path path = (root == null ? Path.of(value) : root.resolve(value)).normalize();
            if (!path.isAbsolute() || root != null && !path.startsWith(root) || !path.toRealPath().equals(path) ||
                !unique.add(path) || source && (!Files.isRegularFile(path) || !value.endsWith(".java")))
                throw new IOException("Unowned compiler path");
            paths.add(path);
        }
        return paths;
    }
}
