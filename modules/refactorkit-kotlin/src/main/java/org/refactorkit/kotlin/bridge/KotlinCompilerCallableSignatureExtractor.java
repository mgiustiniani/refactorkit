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
import org.jetbrains.kotlin.fir.declarations.FirRegularClass;
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction;
import org.jetbrains.kotlin.fir.pipeline.FirResult;
import org.jetbrains.kotlin.fir.pipeline.ModuleCompilerAnalyzedOutput;
import org.jetbrains.kotlin.fir.scopes.FirOverrideChecker;
import org.jetbrains.kotlin.fir.scopes.FirOverrideCheckerKt;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
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
            final List<FirSimpleFunction> sourceFunctions = new ArrayList<FirSimpleFunction>();
            final Map<ClassId, List<ClassId>> classSupertypes = new HashMap<ClassId, List<ClassId>>();
            FirVisitorVoid visitor = new FirVisitorVoid() {
                @Override public void visitElement(FirElement element) { element.acceptChildren(this); }
                @Override public Object visitElement(FirElement element, Object ignored) {
                    visitElement(element);
                    return null;
                }
                @Override public void visitSimpleFunction(FirSimpleFunction function) {
                    collectFunction(function, signatures);
                    if (sourceDeclaration(function.getSource(), KtNamedFunction.class) != null) {
                        sourceFunctions.add(function);
                    }
                    function.acceptChildren(this);
                }
                @Override public void visitConstructor(FirConstructor constructor) {
                    collectConstructor(constructor, signatures);
                    constructor.acceptChildren(this);
                }
                @Override public void visitRegularClass(FirRegularClass declaration) {
                    collectClassSupertypes(declaration, classSupertypes);
                    declaration.acceptChildren(this);
                }
            };
            for (ModuleCompilerAnalyzedOutput output : result.getOutputs()) {
                for (org.jetbrains.kotlin.fir.declarations.FirFile file : output.getFir()) file.accept(visitor);
            }
            bindOverrideFamilies(sourceFunctions, classSupertypes, signatures);
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

    private static void collectClassSupertypes(
        FirRegularClass declaration,
        Map<ClassId, List<ClassId>> classSupertypes
    ) {
        ClassId classId = declaration.getSymbol().getClassId();
        if (classId == null || classId.isLocal()) return;
        List<ClassId> parents = new ArrayList<ClassId>();
        for (FirTypeRef reference : declaration.getSuperTypeRefs()) {
            if (!(reference instanceof FirResolvedTypeRef) ||
                !(((FirResolvedTypeRef) reference).getType() instanceof org.jetbrains.kotlin.fir.types.ConeClassLikeType)) {
                continue;
            }
            ClassId parent = ((org.jetbrains.kotlin.fir.types.ConeClassLikeType)
                ((FirResolvedTypeRef) reference).getType()).getLookupTag().getClassId();
            if (parent != null && !parent.isLocal()) parents.add(parent);
        }
        classSupertypes.put(classId, Collections.unmodifiableList(parents));
    }

    private static boolean hasExternalAncestor(
        ClassId child,
        Map<ClassId, List<ClassId>> classSupertypes,
        java.util.Set<ClassId> visited
    ) {
        if (!visited.add(child)) return false;
        for (ClassId parent : classSupertypes.getOrDefault(child, Collections.<ClassId>emptyList())) {
            String identity = parent.asSingleFqName().asString();
            if ("kotlin.Any".equals(identity) || "java.lang.Object".equals(identity)) continue;
            if (!classSupertypes.containsKey(parent) ||
                hasExternalAncestor(parent, classSupertypes, visited)) return true;
        }
        return false;
    }

    private static boolean inherits(
        ClassId child,
        ClassId expectedParent,
        Map<ClassId, List<ClassId>> classSupertypes,
        java.util.Set<ClassId> visited
    ) {
        if (!visited.add(child)) return false;
        for (ClassId parent : classSupertypes.getOrDefault(child, Collections.<ClassId>emptyList())) {
            if (parent.equals(expectedParent) || inherits(parent, expectedParent, classSupertypes, visited)) return true;
        }
        return false;
    }

    private static void bindOverrideFamilies(
        List<FirSimpleFunction> functions,
        Map<ClassId, List<ClassId>> classSupertypes,
        Map<String, ExtractedCallableSignature> signatures
    ) {
        List<FirSimpleFunction> catalogued = new ArrayList<FirSimpleFunction>();
        List<String> keys = new ArrayList<String>();
        for (FirSimpleFunction function : functions) {
            KtNamedFunction declaration = sourceDeclaration(function.getSource(), KtNamedFunction.class);
            if (declaration == null || declaration.getNameIdentifier() == null) continue;
            String key = declarationKey(
                canonicalPath(declaration.getContainingKtFile()),
                declaration.getNameIdentifier().getTextRange().getStartOffset()
            );
            if (signatures.containsKey(key)) {
                catalogued.add(function);
                keys.add(key);
            }
        }
        if (catalogued.isEmpty()) return;
        int[] parent = new int[catalogued.size()];
        for (int index = 0; index < parent.length; index++) parent[index] = index;
        FirOverrideChecker checker = FirOverrideCheckerKt.getFirOverrideChecker(
            catalogued.get(0).getModuleData().getSession()
        );
        for (int left = 0; left < catalogued.size(); left++) {
            for (int right = left + 1; right < catalogued.size(); right++) {
                FirSimpleFunction first = catalogued.get(left);
                FirSimpleFunction second = catalogued.get(right);
                if (!first.getName().equals(second.getName())) continue;
                ClassId firstOwner = first.getSymbol().getCallableId().getClassId();
                ClassId secondOwner = second.getSymbol().getCallableId().getClassId();
                if (firstOwner == null || secondOwner == null ||
                    (!inherits(firstOwner, secondOwner, classSupertypes, new java.util.HashSet<ClassId>()) &&
                        !inherits(secondOwner, firstOwner, classSupertypes, new java.util.HashSet<ClassId>()))) continue;
                if (checker.isOverriddenFunction(first, second) || checker.isOverriddenFunction(second, first)) {
                    union(parent, left, right);
                }
            }
        }
        Map<Integer, List<Integer>> groups = new HashMap<Integer, List<Integer>>();
        for (int index = 0; index < parent.length; index++) {
            int root = find(parent, index);
            groups.computeIfAbsent(root, ignored -> new ArrayList<Integer>()).add(index);
        }
        for (List<Integer> group : groups.values()) {
            List<String> identities = new ArrayList<String>();
            for (int index : group) identities.add(signatures.get(keys.get(index)).jvmIdentity());
            Collections.sort(identities);
            String familyId = "kotlin-override-family-v1:" + sha256(String.join("\u0000", identities));
            boolean family = group.size() > 1;
            boolean externalBoundary = false;
            for (int index : group) {
                FirSimpleFunction function = catalogued.get(index);
                ClassId owner = function.getSymbol().getCallableId().getClassId();
                if (function.getStatus().isOverride() && owner != null &&
                    hasExternalAncestor(owner, classSupertypes, new java.util.HashSet<ClassId>())) {
                    externalBoundary = true;
                }
            }
            for (int index : group) {
                FirSimpleFunction function = catalogued.get(index);
                signatures.get(keys.get(index)).bindOverrideFamily(
                    familyId, family || function.getStatus().isOverride(), externalBoundary
                );
            }
        }
    }

    private static int find(int[] parent, int value) {
        int root = value;
        while (parent[root] != root) root = parent[root];
        while (parent[value] != value) {
            int next = parent[value];
            parent[value] = root;
            value = next;
        }
        return root;
    }

    private static void union(int[] parent, int left, int right) {
        int first = find(parent, left);
        int second = find(parent, right);
        if (first != second) parent[second] = first;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            return result.toString();
        } catch (Exception failure) {
            throw failure("kotlin.symbolOverrideEvidenceUnavailable");
        }
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
        private String overrideFamilyId = "";
        private boolean hierarchyMember;
        private boolean externalHierarchyBoundary;

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
        String overrideFamilyId() { return overrideFamilyId; }
        boolean isHierarchyMember() { return hierarchyMember; }
        boolean hasExternalHierarchyBoundary() { return externalHierarchyBoundary; }
        String jvmIdentity() { return owner + "#" + jvmName + descriptor; }
        void bindOverrideFamily(String familyId, boolean member, boolean externalBoundary) {
            this.overrideFamilyId = familyId;
            this.hierarchyMember = member;
            this.externalHierarchyBoundary = externalBoundary;
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ExtractedCallableSignature)) return false;
            ExtractedCallableSignature value = (ExtractedCallableSignature) other;
            return owner.equals(value.owner) && jvmName.equals(value.jvmName) && descriptor.equals(value.descriptor) &&
                sourceName.equals(value.sourceName) && kind.equals(value.kind) &&
                overrideFamilyId.equals(value.overrideFamilyId) && hierarchyMember == value.hierarchyMember &&
                externalHierarchyBoundary == value.externalHierarchyBoundary;
        }

        @Override public int hashCode() {
            int result = owner.hashCode();
            result = 31 * result + jvmName.hashCode();
            result = 31 * result + descriptor.hashCode();
            result = 31 * result + sourceName.hashCode();
            result = 31 * result + kind.hashCode();
            result = 31 * result + overrideFamilyId.hashCode();
            result = 31 * result + Boolean.hashCode(hierarchyMember);
            result = 31 * result + Boolean.hashCode(externalHierarchyBoundary);
            return result;
        }
    }
}
