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
import org.jetbrains.kotlin.name.ClassId;
import org.jetbrains.kotlin.name.Name;
import org.jetbrains.kotlin.psi.KtClass;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtDeclaration;
import org.jetbrains.kotlin.psi.KtDeclarationContainer;
import org.jetbrains.kotlin.psi.KtEnumEntry;
import org.jetbrains.kotlin.psi.KtFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Compiler-PSI extraction for JVM type declarations after a successful K2 compilation. */
final class KotlinCompilerSymbolExtractor {
    private static final int MAX_SYMBOLS = 500;
    private static final Pattern JVM_SEGMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

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
            for (KtFile file : environment.getSourceFiles()) {
                Path path = Paths.get(file.getVirtualFilePath()).toAbsolutePath().normalize().toRealPath();
                collect(file.getDeclarations(), path, outputDirectory, identities, result);
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
        Set<String> identities,
        List<ExtractedSymbol> result
    ) {
        for (KtDeclaration declaration : declarations) {
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
            if (identifier == null) throw new SymbolExtractionException("kotlin.symbolLocationUnavailable");
            String kind = kind(type);
            result.add(new ExtractedSymbol(
                identity,
                name,
                kind,
                source.toString(),
                identifier.getTextRange().getStartOffset(),
                identifier.getTextRange().getEndOffset()
            ));
            if (type instanceof KtDeclarationContainer) {
                collect(((KtDeclarationContainer) type).getDeclarations(), source, outputDirectory, identities, result);
            }
        }
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

    private static String kind(KtClassOrObject type) {
        if (!(type instanceof KtClass)) {
            throw new SymbolExtractionException("kotlin.symbolDeclarationKindUnsupported");
        }
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
        private final int startOffset;
        private final int endOffset;

        ExtractedSymbol(String identity, String name, String kind, String path, int startOffset, int endOffset) {
            this.identity = identity;
            this.name = name;
            this.kind = kind;
            this.path = path;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }

        String identity() { return identity; }
        String name() { return name; }
        String kind() { return kind; }
        String path() { return path; }
        int startOffset() { return startOffset; }
        int endOffset() { return endOffset; }
    }

    static final class SymbolExtractionException extends RuntimeException {
        SymbolExtractionException(String code) { super(code); }
    }
}
