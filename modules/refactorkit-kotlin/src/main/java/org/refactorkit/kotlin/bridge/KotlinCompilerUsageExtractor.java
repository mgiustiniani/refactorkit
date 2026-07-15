package org.refactorkit.kotlin.bridge;

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
import org.jetbrains.kotlin.fir.FirElement;
import org.jetbrains.kotlin.fir.pipeline.FirResult;
import org.jetbrains.kotlin.fir.pipeline.ModuleCompilerAnalyzedOutput;
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference;
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid;
import org.jetbrains.kotlin.psi.KtBlockExpression;
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtNameReferenceExpression;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** K2 FIR extraction for source function-call usages after successful compilation. */
final class KotlinCompilerUsageExtractor {
    private static final int MAX_USAGES = 2_000;

    private KotlinCompilerUsageExtractor() {}

    static List<ExtractedUsage> extract(
        List<Path> sources,
        Path jdkHome,
        List<Path> classpath,
        String jvmTarget,
        List<KotlinCompilerSymbolExtractor.ExtractedSymbol> symbols
    ) {
        final Map<String, KotlinCompilerSymbolExtractor.ExtractedSymbol> targets = new HashMap<String, KotlinCompilerSymbolExtractor.ExtractedSymbol>();
        for (KotlinCompilerSymbolExtractor.ExtractedSymbol symbol : symbols) {
            if (!"FUNCTION".equals(symbol.kind())) continue;
            String key = declarationKey(Paths.get(symbol.path()), symbol.startOffset());
            if (targets.put(key, symbol) != null) throw failure("kotlin.usageTargetCollision");
        }
        Disposable disposable = Disposer.newDisposable("refactorkit-kotlin-usages");
        try {
            CompilerConfiguration configuration = new CompilerConfiguration();
            configuration.put(CommonConfigurationKeys.MODULE_NAME, "refactorkit-usages");
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
                "refactorkit-usages",
                Collections.<String>emptyList()
            );
            if (result == null) throw failure("kotlin.usageFirResolutionFailed");

            final List<ExtractedUsage> usages = new ArrayList<ExtractedUsage>();
            final Set<String> identities = new HashSet<String>();
            FirVisitorVoid visitor = new FirVisitorVoid() {
                @Override public void visitElement(FirElement element) { element.acceptChildren(this); }
                @Override public Object visitElement(FirElement element, Object ignored) {
                    visitElement(element);
                    return null;
                }
                @Override public void visitResolvedNamedReference(FirResolvedNamedReference reference) {
                    collect(reference, targets, identities, usages);
                    reference.acceptChildren(this);
                }
            };
            for (ModuleCompilerAnalyzedOutput output : result.getOutputs()) {
                for (org.jetbrains.kotlin.fir.declarations.FirFile file : output.getFir()) file.accept(visitor);
            }
            usages.sort(Comparator.comparing(ExtractedUsage::path)
                .thenComparingInt(ExtractedUsage::startOffset)
                .thenComparing(ExtractedUsage::targetIdentity));
            return usages;
        } catch (KotlinCompilerSymbolExtractor.SymbolExtractionException problem) {
            throw problem;
        } catch (Throwable problem) {
            throw failure("kotlin.usageExtractionFailed");
        } finally {
            Disposer.dispose(disposable);
        }
    }

    private static void collect(
        FirResolvedNamedReference reference,
        Map<String, KotlinCompilerSymbolExtractor.ExtractedSymbol> targets,
        Set<String> identities,
        List<ExtractedUsage> usages
    ) {
        KtSourceElement usageSource = reference.getSource();
        KtSourceElement targetSource = reference.getResolvedSymbol().getSource();
        if (!(usageSource instanceof KtPsiSourceElement) || !(targetSource instanceof KtPsiSourceElement)) return;
        PsiElement usagePsi = ((KtPsiSourceElement) usageSource).getPsi();
        PsiElement targetPsi = ((KtPsiSourceElement) targetSource).getPsi();
        if (!(usagePsi instanceof KtNameReferenceExpression) || usagePsi instanceof KtOperationReferenceExpression) return;
        if (parent(usagePsi, KtCallableReferenceExpression.class) != null) return;
        if (!(targetPsi instanceof KtNamedFunction)) return;
        KtNamedFunction function = (KtNamedFunction) targetPsi;
        if (parent(function, KtBlockExpression.class) != null) return;
        PsiElement targetIdentifier = function.getNameIdentifier();
        if (targetIdentifier == null) throw failure("kotlin.usageTargetLocationUnavailable");
        Path targetPath = canonicalPath(function.getContainingKtFile());
        KotlinCompilerSymbolExtractor.ExtractedSymbol target = targets.get(
            declarationKey(targetPath, targetIdentifier.getTextRange().getStartOffset())
        );
        if (target == null) throw failure("kotlin.usageTargetMissing");

        PsiElement usageIdentifier = ((KtNameReferenceExpression) usagePsi).getReferencedNameElement();
        if (usageIdentifier == null) throw failure("kotlin.usageLocationUnavailable");
        if (!usageIdentifier.getText().equals(reference.getName().asString()) ||
            !usageIdentifier.getText().equals(target.name())) return;
        Path path = canonicalPath((KtFile) usagePsi.getContainingFile());
        int start = usageIdentifier.getTextRange().getStartOffset();
        int end = usageIdentifier.getTextRange().getEndOffset();
        String identity = path + "\u0000" + start + "\u0000" + end + "\u0000" + target.identity();
        if (!identities.add(identity)) return;
        if (usages.size() >= MAX_USAGES) throw failure("kotlin.usageLimitExceeded");
        usages.add(new ExtractedUsage(path.toString(), target.identity(), usageIdentifier.getText(), start, end));
    }

    private static Path canonicalPath(KtFile file) {
        try {
            return Paths.get(file.getVirtualFilePath()).toAbsolutePath().normalize().toRealPath();
        } catch (Exception problem) {
            throw failure("kotlin.usagePathInvalid");
        }
    }

    private static String declarationKey(Path path, int startOffset) {
        try {
            return path.toAbsolutePath().normalize().toRealPath() + "\u0000" + startOffset;
        } catch (Exception problem) {
            throw failure("kotlin.usagePathInvalid");
        }
    }

    private static <T extends PsiElement> T parent(PsiElement start, Class<T> type) {
        PsiElement current = start.getParent();
        while (current != null) {
            if (type.isInstance(current)) return type.cast(current);
            current = current.getParent();
        }
        return null;
    }

    private static KotlinCompilerSymbolExtractor.SymbolExtractionException failure(String code) {
        return new KotlinCompilerSymbolExtractor.SymbolExtractionException(code);
    }

    static final class ExtractedUsage {
        private final String path;
        private final String targetIdentity;
        private final String selectionText;
        private final int startOffset;
        private final int endOffset;

        private ExtractedUsage(String path, String targetIdentity, String selectionText, int startOffset, int endOffset) {
            this.path = path;
            this.targetIdentity = targetIdentity;
            this.selectionText = selectionText;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }

        String path() { return path; }
        String targetIdentity() { return targetIdentity; }
        String selectionText() { return selectionText; }
        int startOffset() { return startOffset; }
        int endOffset() { return endOffset; }
    }
}
