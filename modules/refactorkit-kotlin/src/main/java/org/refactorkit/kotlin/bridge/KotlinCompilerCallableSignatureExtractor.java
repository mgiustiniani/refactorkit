package org.refactorkit.kotlin.bridge;

import kotlin.jvm.functions.Function1;
import org.jetbrains.kotlin.KtPsiSourceElement;
import org.jetbrains.kotlin.KtSourceElement;
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys;
import org.jetbrains.kotlin.cli.common.config.KotlinSourceRoot;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.FirKotlinToJvmBytecodeCompiler;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.cli.jvm.compiler.VfsBasedProjectEnvironment;
import org.jetbrains.kotlin.cli.jvm.config.JvmContentRootsKt;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.config.ApiVersion;
import org.jetbrains.kotlin.config.CommonConfigurationKeys;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.config.JVMConfigurationKeys;
import org.jetbrains.kotlin.config.JvmTarget;
import org.jetbrains.kotlin.config.LanguageVersion;
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl;
import org.jetbrains.kotlin.fileClasses.JvmFileClassUtil;
import org.jetbrains.kotlin.fir.FirElement;
import org.jetbrains.kotlin.fir.declarations.FirConstructor;
import org.jetbrains.kotlin.fir.declarations.FirFunction;
import org.jetbrains.kotlin.fir.declarations.FirReceiverParameter;
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction;
import org.jetbrains.kotlin.fir.pipeline.FirResult;
import org.jetbrains.kotlin.fir.pipeline.ModuleCompilerAnalyzedOutput;
import org.jetbrains.kotlin.fir.scopes.jvm.SignatureUtilsKt;
import org.jetbrains.kotlin.fir.types.ConeKotlinType;
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef;
import org.jetbrains.kotlin.fir.types.FirTypeRef;
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid;
import org.jetbrains.kotlin.name.ClassId;
import org.jetbrains.kotlin.psi.KtClass;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtConstructor;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtExpression;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.ValueArgument;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Exact K2 FIR-to-JVM callable signatures keyed by compiler PSI declaration locations. */
final class KotlinCompilerCallableSignatureExtractor {
    private static final int MAX_CALLABLES = 500;
    private static final int MAX_DESCRIPTOR_CHARS = 1_024;
    private static final Pattern JVM_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern JVM_BINARY_NAME = Pattern.compile(
        "[A-Za-z_][A-Za-z0-9_]*(?:[.$][A-Za-z_][A-Za-z0-9_]*)*"
    );

    private KotlinCompilerCallableSignatureExtractor() {}

    static Map<String, ExtractedCallableSignature> extract(
        List<Path> sources,
        Path jdkHome,
        List<Path> classpath,
        String jvmTarget
    ) {
        Disposable disposable = Disposer.newDisposable("refactorkit-kotlin-callable-signatures");
        try {
            CompilerConfiguration configuration = configuration(sources, jdkHome, classpath, jvmTarget);
            KotlinCoreEnvironment environment = KotlinCoreEnvironment.createForProduction(
                disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES
            );
            VfsBasedProjectEnvironment projectEnvironment = new VfsBasedProjectEnvironment(
                environment.getProject(),
                VirtualFileManager.getInstance().getFileSystem("file"),
                scope -> environment.createPackagePartProvider(scope)
            );
            FirKotlinToJvmBytecodeCompiler.FrontendContextForMultiChunkMode context =
                new FirKotlinToJvmBytecodeCompiler.FrontendContextForMultiChunkMode(
                    projectEnvironment, environment, configuration, environment.getProject()
                );
            FirResult result = FirKotlinToJvmBytecodeCompiler.INSTANCE.runFrontend(
                context,
                environment.getSourceFiles(),
                FirKotlinToJvmBytecodeCompiler.INSTANCE.createPendingReporter(MessageCollector.Companion.getNONE()),
                "refactorkit-callable-signatures",
                Collections.<String>emptyList()
            );
            if (result == null) throw failure("kotlin.symbolCallableFirResolutionFailed");

            final Map<String, ExtractedCallableSignature> signatures =
                new LinkedHashMap<String, ExtractedCallableSignature>();
            FirVisitorVoid visitor = new FirVisitorVoid() {
                @Override public void visitElement(FirElement element) { element.acceptChildren(this); }
                @Override public Object visitElement(FirElement element, Object ignored) {
                    visitElement(element);
                    return null;
                }
                @Override public void visitSimpleFunction(FirSimpleFunction function) {
                    collectFunction(function, signatures);
                    function.acceptChildren(this);
                }
                @Override public void visitConstructor(FirConstructor constructor) {
                    collectConstructor(constructor, signatures);
                    constructor.acceptChildren(this);
                }
            };
            for (ModuleCompilerAnalyzedOutput output : result.getOutputs()) {
                for (org.jetbrains.kotlin.fir.declarations.FirFile file : output.getFir()) file.accept(visitor);
            }
            return Collections.unmodifiableMap(signatures);
        } catch (KotlinCompilerSymbolExtractor.SymbolExtractionException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw failure("kotlin.symbolCallableFirResolutionFailed");
        } finally {
            Disposer.dispose(disposable);
        }
    }

    private static CompilerConfiguration configuration(
        List<Path> sources,
        Path jdkHome,
        List<Path> classpath,
        String jvmTarget
    ) {
        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.put(CommonConfigurationKeys.MODULE_NAME, "refactorkit-callable-signatures");
        configuration.put(CommonConfigurationKeys.USE_FIR, true);
        configuration.put(
            CommonConfigurationKeys.LANGUAGE_VERSION_SETTINGS,
            new LanguageVersionSettingsImpl(LanguageVersion.KOTLIN_2_0, ApiVersion.KOTLIN_2_0)
        );
        configuration.put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.Companion.getNONE());
        configuration.put(JVMConfigurationKeys.JDK_HOME, jdkHome.toFile());
        JvmTarget target = JvmTarget.fromString(jvmTarget);
        if (target == null) throw failure("kotlin.usageJvmTargetInvalid");
        configuration.put(JVMConfigurationKeys.JVM_TARGET, target);
        for (Path source : sources) {
            configuration.add(CLIConfigurationKeys.CONTENT_ROOTS, new KotlinSourceRoot(source.toString(), false, null));
        }
        List<java.io.File> classpathFiles = new ArrayList<java.io.File>();
        for (Path entry : classpath) classpathFiles.add(entry.toFile());
        JvmContentRootsKt.addJvmClasspathRoots(configuration, classpathFiles);
        JvmContentRootsKt.configureJdkClasspathRoots(configuration);
        return configuration;
    }

    private static void collectFunction(
        FirSimpleFunction function,
        Map<String, ExtractedCallableSignature> signatures
    ) {
        KtNamedFunction declaration = sourceDeclaration(function.getSource(), KtNamedFunction.class);
        if (declaration == null || declaration.getNameIdentifier() == null || declaration.getName() == null) return;
        ClassId classId = function.getSymbol().getCallableId().getClassId();
        String owner = classId == null
            ? binaryOwner(JvmFileClassUtil.getFileClassInternalName(declaration.getContainingKtFile()))
            : binaryName(classId);
        add(
            declaration.getContainingKtFile(),
            declaration.getNameIdentifier(),
            declaration.getName(),
            "FUNCTION",
            owner,
            jvmSignature(function, literalJvmName(declaration)),
            signatures
        );
    }

    private static void collectConstructor(
        FirConstructor constructor,
        Map<String, ExtractedCallableSignature> signatures
    ) {
        KtConstructor<?> declaration = sourceConstructor(constructor.getSource());
        if (declaration == null) return;
        KtClassOrObject type = declaration.getContainingClassOrObject();
        ClassId classId = type.getClassId();
        String name = type.getName();
        if (classId == null || classId.isLocal() || name == null) return;
        PsiElement identifier = constructorIdentifier(declaration);
        if (identifier == null) return;
        add(
            declaration.getContainingKtFile(),
            identifier,
            name,
            "CONSTRUCTOR",
            binaryName(classId),
            jvmSignature(constructor, "<init>"),
            signatures
        );
    }

    private static void add(
        KtFile file,
        PsiElement identifier,
        String sourceName,
        String kind,
        String owner,
        String signature,
        Map<String, ExtractedCallableSignature> signatures
    ) {
        int descriptorStart = signature.indexOf('(');
        if (descriptorStart <= 0) throw failure("kotlin.symbolCallableEvidenceMissing");
        String jvmName = signature.substring(0, descriptorStart);
        String descriptor = signature.substring(descriptorStart);
        if ((!JVM_NAME.matcher(jvmName).matches() && !("CONSTRUCTOR".equals(kind) && "<init>".equals(jvmName))) ||
            descriptor.length() > MAX_DESCRIPTOR_CHARS) {
            throw failure("kotlin.symbolJvmNameUnsupported");
        }
        if (signatures.size() >= MAX_CALLABLES) throw failure("kotlin.symbolLimitExceeded");
        String key = declarationKey(canonicalPath(file), identifier.getTextRange().getStartOffset());
        ExtractedCallableSignature value = new ExtractedCallableSignature(
            owner, jvmName, descriptor, sourceName, kind
        );
        ExtractedCallableSignature previous = signatures.put(key, value);
        if (previous != null && !previous.equals(value)) throw failure("kotlin.symbolCallableEvidenceAmbiguous");
    }

    private static String jvmSignature(FirFunction function, String jvmName) {
        Function1<FirTypeRef, ConeKotlinType> resolver = typeRef ->
            typeRef instanceof FirResolvedTypeRef ? ((FirResolvedTypeRef) typeRef).getType() : null;
        String signature = SignatureUtilsKt.computeJvmDescriptor(function, jvmName, true, resolver);
        if (signature == null) throw failure("kotlin.symbolCallableEvidenceMissing");
        // FIR scope signatures spell nested class segments with '.', while class-file descriptors use '$'.
        signature = signature.replace('.', '$');
        int parametersStart = signature.indexOf('(');
        int parametersEnd = signature.indexOf(')', parametersStart + 1);
        if (parametersStart < 0 || parametersEnd < 0) throw failure("kotlin.symbolCallableEvidenceMissing");
        FirReceiverParameter receiverParameter = function.getReceiverParameter();
        if (receiverParameter != null) {
            ConeKotlinType receiver = resolver.invoke(receiverParameter.getTypeRef());
            if (receiver == null) throw failure("kotlin.symbolCallableEvidenceMissing");
            String receiverDescriptor = SignatureUtilsKt.computeJvmDescriptorRepresentation(receiver, resolver);
            if (receiverDescriptor == null || receiverDescriptor.isEmpty()) {
                throw failure("kotlin.symbolCallableEvidenceMissing");
            }
            signature = signature.substring(0, parametersStart + 1) + receiverDescriptor +
                signature.substring(parametersStart + 1);
            parametersEnd += receiverDescriptor.length();
        }
        if (!function.getContextReceivers().isEmpty()) {
            throw failure("kotlin.symbolCallableShapeUnsupported");
        }
        if (function.getStatus().isSuspend()) {
            signature = signature.substring(0, parametersEnd) + "Lkotlin/coroutines/Continuation;)Ljava/lang/Object;";
        }
        if (signature.length() > MAX_DESCRIPTOR_CHARS + 512) {
            throw failure("kotlin.symbolCallableEvidenceMissing");
        }
        return signature;
    }

    private static String literalJvmName(KtNamedFunction declaration) {
        String result = declaration.getName();
        for (KtAnnotationEntry annotation : declaration.getAnnotationEntries()) {
            org.jetbrains.kotlin.name.Name shortName = annotation.getShortName();
            if (shortName == null || !"JvmName".equals(shortName.asString())) continue;
            List<? extends ValueArgument> arguments = annotation.getValueArguments();
            KtExpression expression = arguments.size() == 1 ? arguments.get(0).getArgumentExpression() : null;
            if (expression == null) throw failure("kotlin.symbolJvmNameUnsupported");
            String text = expression.getText();
            if (text.length() < 3 || text.charAt(0) != '"' || text.charAt(text.length() - 1) != '"') {
                throw failure("kotlin.symbolJvmNameUnsupported");
            }
            String literal = text.substring(1, text.length() - 1);
            if (!JVM_NAME.matcher(literal).matches()) throw failure("kotlin.symbolJvmNameUnsupported");
            result = literal;
        }
        if (result == null || !JVM_NAME.matcher(result).matches()) {
            throw failure("kotlin.symbolJvmNameUnsupported");
        }
        return result;
    }

    private static PsiElement constructorIdentifier(KtConstructor<?> declaration) {
        PsiElement keyword = declaration.getConstructorKeyword();
        if (keyword != null) return keyword;
        return declaration.getValueParameterList() == null
            ? null
            : declaration.getValueParameterList().getLeftParenthesis();
    }

    private static KtConstructor<?> sourceConstructor(KtSourceElement source) {
        if (!(source instanceof KtPsiSourceElement)) return null;
        PsiElement psi = ((KtPsiSourceElement) source).getPsi();
        KtConstructor<?> constructor = psi instanceof KtConstructor
            ? (KtConstructor<?>) psi
            : parent(psi, KtConstructor.class);
        if (constructor != null) return constructor;
        KtClass klass = psi instanceof KtClass ? (KtClass) psi : parent(psi, KtClass.class);
        return klass == null ? null : klass.getPrimaryConstructor();
    }

    private static <T extends PsiElement> T sourceDeclaration(KtSourceElement source, Class<T> type) {
        if (!(source instanceof KtPsiSourceElement)) return null;
        PsiElement psi = ((KtPsiSourceElement) source).getPsi();
        return type.isInstance(psi) ? type.cast(psi) : parent(psi, type);
    }

    private static <T extends PsiElement> T parent(PsiElement start, Class<T> type) {
        PsiElement current = start == null ? null : start.getParent();
        while (current != null) {
            if (type.isInstance(current)) return type.cast(current);
            current = current.getParent();
        }
        return null;
    }

    private static Path canonicalPath(KtFile file) {
        try {
            return Paths.get(file.getVirtualFilePath()).toAbsolutePath().normalize().toRealPath();
        } catch (Exception failure) {
            throw failure("kotlin.usagePathInvalid");
        }
    }

    static String declarationKey(Path path, int startOffset) {
        try {
            return path.toAbsolutePath().normalize().toRealPath() + "\u0000" + startOffset;
        } catch (Exception failure) {
            throw failure("kotlin.usagePathInvalid");
        }
    }

    private static String binaryOwner(String internalName) {
        String owner = internalName.replace('/', '.');
        if (owner.length() > 2048 || !JVM_BINARY_NAME.matcher(owner).matches()) {
            throw failure("kotlin.symbolJvmNameUnsupported");
        }
        return owner;
    }

    private static String binaryName(ClassId classId) {
        StringBuilder result = new StringBuilder();
        if (!classId.getPackageFqName().isRoot()) result.append(classId.getPackageFqName().asString()).append('.');
        boolean first = true;
        for (org.jetbrains.kotlin.name.Name segment : classId.getRelativeClassName().pathSegments()) {
            if (!first) result.append('$');
            result.append(segment.asString());
            first = false;
        }
        String value = result.toString();
        if (value.length() > 2048 || !JVM_BINARY_NAME.matcher(value).matches()) {
            throw failure("kotlin.symbolJvmNameUnsupported");
        }
        return value;
    }

    private static KotlinCompilerSymbolExtractor.SymbolExtractionException failure(String code) {
        return new KotlinCompilerSymbolExtractor.SymbolExtractionException(code);
    }

    static final class ExtractedCallableSignature {
        private final String owner;
        private final String jvmName;
        private final String descriptor;
        private final String sourceName;
        private final String kind;

        private ExtractedCallableSignature(
            String owner,
            String jvmName,
            String descriptor,
            String sourceName,
            String kind
        ) {
            this.owner = owner;
            this.jvmName = jvmName;
            this.descriptor = descriptor;
            this.sourceName = sourceName;
            this.kind = kind;
        }

        String owner() { return owner; }
        String jvmName() { return jvmName; }
        String descriptor() { return descriptor; }
        String sourceName() { return sourceName; }
        String kind() { return kind; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ExtractedCallableSignature)) return false;
            ExtractedCallableSignature value = (ExtractedCallableSignature) other;
            return owner.equals(value.owner) && jvmName.equals(value.jvmName) && descriptor.equals(value.descriptor) &&
                sourceName.equals(value.sourceName) && kind.equals(value.kind);
        }

        @Override public int hashCode() {
            int result = owner.hashCode();
            result = 31 * result + jvmName.hashCode();
            result = 31 * result + descriptor.hashCode();
            result = 31 * result + sourceName.hashCode();
            result = 31 * result + kind.hashCode();
            return result;
        }
    }
}
