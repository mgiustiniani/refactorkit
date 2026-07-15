package org.refactorkit.kotlin.bridge;

import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys;
import org.jetbrains.kotlin.cli.common.config.KotlinSourceRoot;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.config.CommonConfigurationKeys;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.fileClasses.JvmFileClassUtil;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.name.ClassId;
import org.jetbrains.kotlin.name.Name;
import org.jetbrains.kotlin.psi.KtClass;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtDeclaration;
import org.jetbrains.kotlin.psi.KtDeclarationContainer;
import org.jetbrains.kotlin.psi.KtEnumEntry;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtObjectDeclaration;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.org.objectweb.asm.ClassReader;
import org.jetbrains.org.objectweb.asm.ClassVisitor;
import org.jetbrains.org.objectweb.asm.MethodVisitor;
import org.jetbrains.org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Compiler-PSI and class-file extraction for bounded JVM declarations after successful K2 compilation. */
final class KotlinCompilerSymbolExtractor {
    private static final int MAX_SYMBOLS = 500;
    private static final long MAX_CLASS_FILE_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_METHODS_PER_OWNER = 10_000;
    private static final int MAX_DESCRIPTOR_CHARS = 1_024;
    private static final Pattern JVM_SEGMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern JVM_BINARY_NAME = Pattern.compile(
        "[A-Za-z_][A-Za-z0-9_]*(?:[.$][A-Za-z_][A-Za-z0-9_]*)*"
    );

    private KotlinCompilerSymbolExtractor() {}

    static List<ExtractedSymbol> extract(List<Path> sources, Path outputDirectory) {
        Disposable disposable = Disposer.newDisposable("refactorkit-kotlin-symbols");
        try {
            CompilerConfiguration configuration = new CompilerConfiguration();
            configuration.put(CommonConfigurationKeys.MODULE_NAME, "refactorkit-symbols");
            configuration.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.Companion.getNONE());
            for (Path source : sources) {
                configuration.add(
                    CLIConfigurationKeys.CONTENT_ROOTS,
                    new KotlinSourceRoot(source.toString(), false, null)
                );
            }
            KotlinCoreEnvironment environment = KotlinCoreEnvironment.createForProduction(
                disposable,
                configuration,
                EnvironmentConfigFiles.JVM_CONFIG_FILES
            );
            List<ExtractedSymbol> result = new ArrayList<ExtractedSymbol>();
            Set<String> identities = new HashSet<String>();
            Map<String, List<JvmMethod>> methods = new HashMap<String, List<JvmMethod>>();
            for (KtFile file : environment.getSourceFiles()) {
                Path path = Paths.get(file.getVirtualFilePath()).toAbsolutePath().normalize().toRealPath();
                String fileOwner = null;
                for (KtDeclaration declaration : file.getDeclarations()) {
                    if (declaration instanceof KtNamedFunction) {
                        fileOwner = binaryOwner(JvmFileClassUtil.getFileClassInternalName(file));
                        break;
                    }
                }
                collect(file.getDeclarations(), path, outputDirectory, fileOwner, identities, methods, result);
            }
            result.sort(Comparator.comparing(ExtractedSymbol::identity));
            return result;
        } catch (SymbolExtractionException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SymbolExtractionException("kotlin.symbolExtractionFailed");
        } finally {
            Disposer.dispose(disposable);
        }
    }

    private static void collect(
        List<KtDeclaration> declarations,
        Path source,
        Path outputDirectory,
        String callableOwner,
        Set<String> identities,
        Map<String, List<JvmMethod>> methods,
        List<ExtractedSymbol> result
    ) {
        for (KtDeclaration declaration : declarations) {
            if (declaration instanceof KtNamedFunction) {
                collectFunction(
                    (KtNamedFunction) declaration, source, outputDirectory, callableOwner, identities, methods, result
                );
                continue;
            }
            if (!(declaration instanceof KtClassOrObject) || declaration instanceof KtEnumEntry) continue;
            KtClassOrObject type = (KtClassOrObject) declaration;
            ClassId classId = type.getClassId();
            String name = type.getName();
            if (classId == null || classId.isLocal() || name == null) continue;
            String identity = binaryName(classId);
            if (name.length() > 512 || identity.length() > 2048) {
                throw new SymbolExtractionException("kotlin.symbolNameLimitExceeded");
            }
            if (!identities.add(identity)) throw new SymbolExtractionException("kotlin.symbolIdentityCollision");
            if (result.size() >= MAX_SYMBOLS) throw new SymbolExtractionException("kotlin.symbolLimitExceeded");
            Path classFile = outputDirectory.resolve(identity.replace('.', '/') + ".class").normalize();
            if (!classFile.startsWith(outputDirectory) || Files.isSymbolicLink(classFile) ||
                !Files.isRegularFile(classFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new SymbolExtractionException("kotlin.symbolBinaryEvidenceMissing");
            }
            org.jetbrains.kotlin.com.intellij.psi.PsiElement identifier = type.getNameIdentifier();
            if (identifier == null && type instanceof KtObjectDeclaration && ((KtObjectDeclaration) type).isCompanion()) {
                identifier = ((KtObjectDeclaration) type).getObjectKeyword();
            }
            if (identifier == null) throw new SymbolExtractionException("kotlin.symbolLocationUnavailable");
            String kind = kind(type);
            result.add(new ExtractedSymbol(
                identity,
                name,
                kind,
                source.toString(),
                identity,
                "",
                identifier.getText(),
                visibility(type),
                identifier.getTextRange().getStartOffset(),
                identifier.getTextRange().getEndOffset()
            ));
            if (type instanceof KtDeclarationContainer) {
                collect(
                    ((KtDeclarationContainer) type).getDeclarations(), source, outputDirectory, identity,
                    identities, methods, result
                );
            }
        }
    }

    private static void collectFunction(
        KtNamedFunction function,
        Path source,
        Path outputDirectory,
        String owner,
        Set<String> identities,
        Map<String, List<JvmMethod>> methods,
        List<ExtractedSymbol> result
    ) {
        String name = function.getName();
        if (owner == null) throw new SymbolExtractionException("kotlin.symbolCallableEvidenceMissing");
        org.jetbrains.kotlin.com.intellij.psi.PsiElement identifier = function.getNameIdentifier();
        if (name == null || identifier == null) throw new SymbolExtractionException("kotlin.symbolLocationUnavailable");
        if (!JVM_SEGMENT.matcher(name).matches() || name.length() > 512 || owner.length() > 2048) {
            throw new SymbolExtractionException("kotlin.symbolJvmNameUnsupported");
        }
        List<JvmMethod> ownerMethods = methods.get(owner);
        if (ownerMethods == null) {
            ownerMethods = readMethods(outputDirectory, owner);
            methods.put(owner, ownerMethods);
        }
        List<JvmMethod> matches = new ArrayList<JvmMethod>();
        for (JvmMethod method : ownerMethods) if (method.name.equals(name)) matches.add(method);
        if (matches.isEmpty()) throw new SymbolExtractionException("kotlin.symbolCallableEvidenceMissing");
        if (matches.size() != 1) throw new SymbolExtractionException("kotlin.symbolCallableEvidenceAmbiguous");
        String descriptor = matches.get(0).descriptor;
        if (descriptor.length() > MAX_DESCRIPTOR_CHARS) {
            throw new SymbolExtractionException("kotlin.symbolDescriptorLimitExceeded");
        }
        String identity = owner + "#" + name + descriptor;
        if (!identities.add(identity)) throw new SymbolExtractionException("kotlin.symbolIdentityCollision");
        if (result.size() >= MAX_SYMBOLS) throw new SymbolExtractionException("kotlin.symbolLimitExceeded");
        result.add(new ExtractedSymbol(
            identity, name, "FUNCTION", source.toString(), owner, descriptor, identifier.getText(),
            visibility(function), identifier.getTextRange().getStartOffset(), identifier.getTextRange().getEndOffset()
        ));
    }

    private static List<JvmMethod> readMethods(Path outputDirectory, String owner) {
        Path classFile = outputDirectory.resolve(owner.replace('.', '/') + ".class").normalize();
        try {
            if (!classFile.startsWith(outputDirectory) || Files.isSymbolicLink(classFile) ||
                !Files.isRegularFile(classFile, java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
                Files.size(classFile) > MAX_CLASS_FILE_BYTES) {
                throw new SymbolExtractionException("kotlin.symbolBinaryEvidenceMissing");
            }
            final List<JvmMethod> methods = new ArrayList<JvmMethod>();
            ClassReader reader = new ClassReader(Files.readAllBytes(classFile));
            if (!reader.getClassName().equals(owner.replace('.', '/'))) {
                throw new SymbolExtractionException("kotlin.symbolBinaryEvidenceMissing");
            }
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(
                    int access, String name, String descriptor, String signature, String[] exceptions
                ) {
                    if (!"<init>".equals(name) && !"<clinit>".equals(name) &&
                        (access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) == 0) {
                        if (methods.size() >= MAX_METHODS_PER_OWNER) {
                            throw new SymbolExtractionException("kotlin.symbolCallableEvidenceLimitExceeded");
                        }
                        methods.add(new JvmMethod(name, descriptor));
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return methods;
        } catch (SymbolExtractionException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SymbolExtractionException("kotlin.symbolBinaryEvidenceMissing");
        }
    }

    private static String binaryOwner(String internalName) {
        String owner = internalName.replace('/', '.');
        if (owner.length() > 2048 || !JVM_BINARY_NAME.matcher(owner).matches()) {
            throw new SymbolExtractionException("kotlin.symbolJvmNameUnsupported");
        }
        return owner;
    }

    private static String binaryName(ClassId classId) {
        List<String> segments = new ArrayList<String>();
        for (Name segment : classId.getPackageFqName().pathSegments()) {
            String value = segment.asString();
            if (!JVM_SEGMENT.matcher(value).matches()) throw new SymbolExtractionException("kotlin.symbolJvmNameUnsupported");
            segments.add(value);
        }
        StringBuilder relative = new StringBuilder();
        for (Name segment : classId.getRelativeClassName().pathSegments()) {
            String value = segment.asString();
            if (!JVM_SEGMENT.matcher(value).matches()) throw new SymbolExtractionException("kotlin.symbolJvmNameUnsupported");
            if (relative.length() > 0) relative.append('$');
            relative.append(value);
        }
        if (relative.length() == 0) throw new SymbolExtractionException("kotlin.symbolIdentityUnavailable");
        return (segments.isEmpty() ? "" : String.join(".", segments) + ".") + relative;
    }

    private static String visibility(org.jetbrains.kotlin.psi.KtModifierListOwner declaration) {
        if (declaration.hasModifier(KtTokens.PRIVATE_KEYWORD)) return "PRIVATE";
        if (declaration.hasModifier(KtTokens.PROTECTED_KEYWORD)) return "PROTECTED";
        if (declaration.hasModifier(KtTokens.INTERNAL_KEYWORD)) return "INTERNAL";
        return "PUBLIC";
    }

    private static String kind(KtClassOrObject type) {
        if (!(type instanceof KtClass)) return "OBJECT";
        KtClass klass = (KtClass) type;
        if (klass.isAnnotation()) return "ANNOTATION";
        if (klass.isInterface()) return "INTERFACE";
        if (klass.isEnum()) return "ENUM";
        return "CLASS";
    }

    static final class ExtractedSymbol {
        private final String identity;
        private final String name;
        private final String kind;
        private final String path;
        private final String owner;
        private final String descriptor;
        private final String selectionText;
        private final String visibility;
        private final int startOffset;
        private final int endOffset;

        ExtractedSymbol(
            String identity,
            String name,
            String kind,
            String path,
            String owner,
            String descriptor,
            String selectionText,
            String visibility,
            int startOffset,
            int endOffset
        ) {
            this.identity = identity;
            this.name = name;
            this.kind = kind;
            this.path = path;
            this.owner = owner;
            this.descriptor = descriptor;
            this.selectionText = selectionText;
            this.visibility = visibility;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }

        String identity() { return identity; }
        String name() { return name; }
        String kind() { return kind; }
        String path() { return path; }
        String owner() { return owner; }
        String descriptor() { return descriptor; }
        String selectionText() { return selectionText; }
        String visibility() { return visibility; }
        int startOffset() { return startOffset; }
        int endOffset() { return endOffset; }
    }

    private static final class JvmMethod {
        private final String name;
        private final String descriptor;

        private JvmMethod(String name, String descriptor) {
            this.name = name;
            this.descriptor = descriptor;
        }
    }

    static final class SymbolExtractionException extends RuntimeException {
        SymbolExtractionException(String code) { super(code); }
    }
}
