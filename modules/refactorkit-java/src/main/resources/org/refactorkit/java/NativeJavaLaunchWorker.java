import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;
import javax.tools.*;

/** Transport adapter: no project class loading, annotation processing, or class generation. */
public final class NativeJavaLaunchWorker {
    public static void main(String[] args) throws Exception {
        if (args.length != 0 || Runtime.version().feature() != 25) throw new IOException("Unqualified worker runtime");
        var request = NativeLaunchAnalysis.read(new DataInputStream(System.in));
        var bytes = request.capture();
        System.out.write(bytes);
        System.out.flush();
    }
}

/** Read-only application service; native element/tree projection stays inside this process. */
final class NativeLaunchAnalysis {
    static final int MAGIC = 0x524b4c32;
    static final int MAX_TYPES = 4096;
    static final int MAX_MEMBERS = 16384;
    final Path root;
    final List<Path> sources;
    final List<Path> classpath;

    NativeLaunchAnalysis(Path root, List<Path> sources, List<Path> classpath) {
        this.root = root; this.sources = sources; this.classpath = classpath;
    }

    static NativeLaunchAnalysis read(DataInputStream input) throws IOException {
        if (input.readInt() != MAGIC) throw new IOException("Invalid request identity");
        Path sdk = Path.of(input.readUTF()).toRealPath();
        if (!Path.of(System.getProperty("java.home")).toRealPath().equals(sdk)) throw new IOException("Compiler SDK mismatch");
        Path root = Path.of(input.readUTF()).toRealPath();
        int count = input.readInt();
        if (count < 1 || count > 10000) throw new IOException("Source limit");
        var sources = new ArrayList<Path>();
        var unique = new HashSet<Path>();
        for (int i = 0; i < count; i++) {
            Path path = root.resolve(input.readUTF()).normalize();
            if (!path.startsWith(root) || !path.toRealPath().equals(path) || !unique.add(path) ||
                !Files.isRegularFile(path) || !path.toString().endsWith(".java")) throw new IOException("Invalid source path");
            sources.add(path);
        }
        int entries = input.readInt();
        if (entries < 0 || entries > 1024) throw new IOException("Classpath limit");
        var classpath = new ArrayList<Path>();
        for (int i = 0; i < entries; i++) classpath.add(Path.of(input.readUTF()).toRealPath());
        if (input.read() != -1) throw new IOException("Trailing request data");
        return new NativeLaunchAnalysis(root, sources, classpath);
    }

    byte[] capture() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IOException("Compiler unavailable");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (StandardJavaFileManager standard = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            standard.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
            standard.setLocationFromPaths(StandardLocation.SOURCE_PATH, List.of());
            standard.setLocationFromPaths(StandardLocation.ANNOTATION_PROCESSOR_PATH, List.of());
            JavaFileManager readonly = new ForwardingJavaFileManager<StandardJavaFileManager>(standard) {
                @Override public JavaFileObject getJavaFileForOutput(Location l, String n, JavaFileObject.Kind k, FileObject s) throws IOException {
                    throw new IOException("Compiler output is forbidden");
                }
                @Override public FileObject getFileForOutput(Location l, String p, String n, FileObject s) throws IOException {
                    throw new IOException("Compiler output is forbidden");
                }
            };
            var task = (JavacTask) compiler.getTask(new StringWriter(), readonly, diagnostics,
                List.of("-proc:none", "--release", "25", "-implicit:none", "-encoding", "UTF-8"), null,
                standard.getJavaFileObjectsFromPaths(sources));
            var units = new ArrayList<CompilationUnitTree>();
            task.parse().forEach(units::add);
            task.analyze(); // Deliberately never call generate() or CompilationTask.call().
            var errors = diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).toList();
            var buffer = new ByteArrayOutputStream();
            var output = new DataOutputStream(buffer);
            output.writeInt(MAGIC);
            output.writeInt(errors.isEmpty() ? 0 : 1);
            output.writeInt(Math.min(errors.size(), 64));
            for (var error : errors.subList(0, Math.min(errors.size(), 64))) output.writeUTF(error.getCode());
            if (errors.isEmpty()) {
                var projection = new NativeLaunchProjection(task, root);
                output.writeInt(units.size());
                for (var unit : units) projection.writeUnit(output, unit);
            }
            output.flush();
            if (buffer.size() > 16 * 1024 * 1024) throw new IOException("Response limit");
            return buffer.toByteArray();
        }
    }
}

/** ACL for one exact SDK25 compiler. Binary owners and effective members are native values. */
final class NativeLaunchProjection {
    final Trees trees;
    final Elements elements;
    final Types types;
    final Path root;
    final int implicitMask;
    final java.lang.reflect.Field flags;

    NativeLaunchProjection(JavacTask task, Path root) throws Exception {
        this.trees = Trees.instance(task); this.elements = task.getElements(); this.types = task.getTypes(); this.root = root;
        // Public language-model APIs have no implicit-class predicate in Java25.
        // Use the selected compiler's native flag, fail closed if its shape changes.
        this.implicitMask = Class.forName("com.sun.tools.javac.code.Flags").getField("IMPLICIT_CLASS").getInt(null);
        this.flags = Class.forName("com.sun.tools.javac.tree.JCTree$JCModifiers").getField("flags");
    }

    void writeUnit(DataOutputStream output, CompilationUnitTree unit) throws Exception {
        Path source = Path.of(unit.getSourceFile().toUri()).toRealPath();
        if (!source.startsWith(root)) throw new IOException("Source ownership unavailable");
        String text = unit.getSourceFile().getCharContent(true).toString();
        var classes = new ArrayList<ClassTree>();
        new TreePathScanner<Void, Void>() {
            @Override public Void visitClass(ClassTree tree, Void unused) {
                if (classes.size() >= NativeLaunchAnalysis.MAX_TYPES) throw new IllegalStateException("Type limit");
                classes.add(tree);
                return super.visitClass(tree, unused);
            }
        }.scan(unit, null);
        output.writeUTF(root.relativize(source).toString());
        output.writeInt(classes.size());
        for (ClassTree tree : classes) {
            var element = trees.getElement(com.sun.source.util.TreePath.getPath(unit, tree));
            if (!(element instanceof TypeElement type)) throw new IOException("Type binding unavailable");
            boolean compact = (flags.getLong(tree.getModifiers()) & implicitMask) != 0;
            long start = trees.getSourcePositions().getStartPosition(unit, tree);
            long end = trees.getSourcePositions().getEndPosition(unit, tree);
            if (compact) { start = 0; end = text.length(); } // Native implicit type owns the whole compilation unit.
            if (start < 0 || end <= start || end > text.length()) throw new IOException("Native range unavailable");
            output.writeInt((int) start); output.writeInt((int) end); output.writeBoolean(compact);
            output.writeUTF(binary(type));
            var graph = new LinkedHashMap<String, TypeElement>();
            collect(type, graph);
            output.writeInt(graph.size());
            for (TypeElement node : graph.values()) writeNode(output, node);
            var members = elements.getAllMembers(type).stream()
                .filter(e -> e.getKind() == ElementKind.METHOD && e.getSimpleName().contentEquals("main"))
                .map(e -> (ExecutableElement) e).toList();
            // getAllMembers retains hidden static declarations. Delegate specificity to
            // native compiler predicates; never reconstruct it from owner names/erasure.
            if (members.size() > NativeLaunchAnalysis.MAX_MEMBERS) throw new IOException("Effective member limit");
            var effective = members.stream().filter(candidate -> members.stream().noneMatch(other ->
                other != candidate && (elements.hides(other, candidate) || elements.overrides(other, candidate, type)))).toList();
            writeMethods(output, effective);
        }
    }

    void collect(TypeElement type, Map<String, TypeElement> graph) throws IOException {
        String binary = binary(type);
        if (graph.containsKey(binary)) return;
        if (graph.size() >= NativeLaunchAnalysis.MAX_TYPES) throw new IOException("Hierarchy limit");
        graph.put(binary, type);
        if (type.getSuperclass().getKind() != TypeKind.NONE) collect(declared(type.getSuperclass()), graph);
        for (TypeMirror iface : type.getInterfaces()) collect(declared(iface), graph);
    }

    void writeNode(DataOutputStream output, TypeElement type) throws IOException {
        output.writeUTF(binary(type)); output.writeUTF(elements.getPackageOf(type).getQualifiedName().toString());
        String kind = switch (type.getKind()) {
            case CLASS -> "CLASS"; case INTERFACE -> "INTERFACE"; case ENUM -> "ENUM";
            case RECORD -> "RECORD"; case ANNOTATION_TYPE -> "ANNOTATION";
            default -> throw new IOException("Unsupported type kind");
        };
        output.writeUTF(kind); output.writeInt(modifiers(type.getModifiers()));
        output.writeBoolean(trees.getPath(type) != null);
        output.writeBoolean(type.getNestingKind() == NestingKind.LOCAL);
        output.writeBoolean(type.getNestingKind() == NestingKind.MEMBER);
        output.writeBoolean(type.getNestingKind() == NestingKind.ANONYMOUS);
        output.writeUTF(type.getSuperclass().getKind() == TypeKind.NONE ? "" : binary(declared(type.getSuperclass())));
        output.writeInt(type.getInterfaces().size());
        for (TypeMirror iface : type.getInterfaces()) output.writeUTF(binary(declared(iface)));
        var methods = type.getEnclosedElements().stream()
            .filter(e -> e.getKind() == ElementKind.CONSTRUCTOR || e.getKind() == ElementKind.METHOD && e.getSimpleName().contentEquals("main"))
            .map(e -> (ExecutableElement) e).toList();
        writeMethods(output, methods);
    }

    void writeMethods(DataOutputStream output, List<ExecutableElement> methods) throws IOException {
        if (methods.size() > NativeLaunchAnalysis.MAX_MEMBERS) throw new IOException("Member limit");
        output.writeInt(methods.size());
        for (ExecutableElement method : methods) {
            output.writeUTF(binary((TypeElement) method.getEnclosingElement()));
            var descriptor = new StringBuilder("(");
            for (var parameter : method.getParameters()) descriptor.append(descriptor(parameter.asType()));
            descriptor.append(')').append(method.getKind() == ElementKind.CONSTRUCTOR ? "V" : descriptor(method.getReturnType()));
            output.writeUTF(descriptor.toString()); output.writeInt(modifiers(method.getModifiers()));
            String shape = "OTHER";
            if (method.getParameters().isEmpty()) shape = "NONE";
            else if (method.getParameters().size() == 1) {
                TypeMirror parameter = method.getParameters().get(0).asType();
                if (parameter.getKind() == TypeKind.ARRAY) {
                    TypeMirror component = ((ArrayType) parameter).getComponentType();
                    if (component.getKind() == TypeKind.DECLARED && binary(declared(component)).equals("java.lang.String")) shape = "STRING_ARRAY";
                }
            }
            output.writeUTF(shape); output.writeInt(method.getParameters().size());
            output.writeBoolean(method.getReturnType().getKind() == TypeKind.VOID);
            output.writeBoolean(method.getKind() == ElementKind.CONSTRUCTOR);
            output.writeBoolean(method.getKind() == ElementKind.CONSTRUCTOR && elements.getOrigin(method) == Elements.Origin.MANDATED);
            output.writeBoolean(method.isVarArgs());
            writeOrigin(output, method);
        }
    }

    void writeOrigin(DataOutputStream output, ExecutableElement method) throws IOException {
        var path = trees.getPath(method);
        if (path != null) {
            var unit = path.getCompilationUnit();
            long start = trees.getSourcePositions().getStartPosition(unit, path.getLeaf());
            long end = trees.getSourcePositions().getEndPosition(unit, path.getLeaf());
            String content = unit.getSourceFile().getCharContent(true).toString();
            Path source = Path.of(unit.getSourceFile().toUri()).toRealPath();
            if (source.startsWith(root) && start >= 0 && end > start && end <= content.length()) {
                output.writeByte(1);
                output.writeUTF(root.relativize(source).toString());
                output.writeInt((int) start); output.writeInt((int) end);
            } else output.writeByte(0); // Generated constructors can lack a declaration end position.
            return;
        }
        var file = elements.getFileObjectOf(method);
        if (file == null || file.getKind() == JavaFileObject.Kind.SOURCE) {
            output.writeByte(0);
            return;
        }
        var module = elements.getModuleOf(method);
        output.writeByte(2);
        output.writeUTF(file.toUri().toString());
        output.writeUTF(module == null || module.isUnnamed() ? "" : module.getQualifiedName().toString());
    }

    String descriptor(TypeMirror type) throws IOException {
        return switch (type.getKind()) {
            case BOOLEAN -> "Z"; case BYTE -> "B"; case CHAR -> "C"; case SHORT -> "S";
            case INT -> "I"; case LONG -> "J"; case FLOAT -> "F"; case DOUBLE -> "D"; case VOID -> "V";
            case ARRAY -> "[" + descriptor(((ArrayType) type).getComponentType());
            case DECLARED -> "L" + binary(declared(type)).replace('.', '/') + ";";
            case TYPEVAR -> descriptor(types.erasure(type));
            default -> throw new IOException("Unresolved descriptor");
        };
    }

    TypeElement declared(TypeMirror type) throws IOException {
        if (type.getKind() != TypeKind.DECLARED || !(types.asElement(type) instanceof TypeElement element))
            throw new IOException("Unresolved hierarchy");
        return element;
    }

    String binary(TypeElement type) throws IOException {
        String value = elements.getBinaryName(type).toString();
        if (value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > 1024) throw new IOException("Invalid native owner");
        return value;
    }

    int modifiers(Set<Modifier> values) {
        int flags = 0;
        for (Modifier value : values) flags |= switch (value) {
            case PUBLIC -> 1; case PRIVATE -> 2; case PROTECTED -> 4; case STATIC -> 8;
            case FINAL -> 16; case SYNCHRONIZED -> 32; case VOLATILE -> 64; case TRANSIENT -> 128;
            case NATIVE -> 256; case ABSTRACT -> 1024; case STRICTFP -> 2048; default -> 0;
        };
        return flags;
    }
}
