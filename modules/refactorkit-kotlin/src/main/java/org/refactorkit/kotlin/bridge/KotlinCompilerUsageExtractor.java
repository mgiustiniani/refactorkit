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
import org.jetbrains.kotlin.fir.declarations.FirResolvedImport;
import org.jetbrains.kotlin.fir.declarations.FirTypeParameter;
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier;
import org.jetbrains.kotlin.fir.pipeline.FirResult;
import org.jetbrains.kotlin.fir.pipeline.ModuleCompilerAnalyzedOutput;
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference;
import org.jetbrains.kotlin.fir.types.ConeClassLikeType;
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType;
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef;
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol;
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol;
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol;
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid;
import org.jetbrains.kotlin.name.ClassId;
import org.jetbrains.kotlin.name.Name;
import org.jetbrains.kotlin.psi.KtBlockExpression;
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtConstructor;
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtImportDirective;
import org.jetbrains.kotlin.psi.KtNameReferenceExpression;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.KtNullableType;
import org.jetbrains.kotlin.psi.KtObjectDeclaration;
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression;
import org.jetbrains.kotlin.psi.KtProperty;
import org.jetbrains.kotlin.psi.KtParameter;
import org.jetbrains.kotlin.psi.KtSimpleNameExpression;
import org.jetbrains.kotlin.psi.KtTypeReference;
import org.jetbrains.kotlin.psi.KtTypeParameter;
import org.jetbrains.kotlin.psi.KtUserType;

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

/** K2 FIR extraction for compiler-resolved source function and type usages after successful compilation. */
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
        final Map<String, KotlinCompilerSymbolExtractor.ExtractedSymbol> declarationTargets =
            new HashMap<String, KotlinCompilerSymbolExtractor.ExtractedSymbol>();
        final Map<String, KotlinCompilerSymbolExtractor.ExtractedSymbol> typeTargets =
            new HashMap<String, KotlinCompilerSymbolExtractor.ExtractedSymbol>();
        for (KotlinCompilerSymbolExtractor.ExtractedSymbol symbol : symbols) {
            String key = declarationKey(Paths.get(symbol.path()), symbol.startOffset());
            if (declarationTargets.put(key, symbol) != null) throw failure("kotlin.usageTargetCollision");
            if (!"FUNCTION".equals(symbol.kind()) && typeTargets.put(symbol.identity(), symbol) != null) {
                throw failure("kotlin.usageTargetCollision");
            }
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
            final Map<FirTypeParameterSymbol, KotlinCompilerSymbolExtractor.ExtractedSymbol> typeParameters =
                new HashMap<FirTypeParameterSymbol, KotlinCompilerSymbolExtractor.ExtractedSymbol>();
            final Map<String, String> importAliases = new HashMap<String, String>();
            FirVisitorVoid typeParameterVisitor = new FirVisitorVoid() {
                @Override public void visitElement(FirElement element) { element.acceptChildren(this); }
                @Override public Object visitElement(FirElement element, Object ignored) {
                    visitElement(element);
                    return null;
                }
                @Override public void visitTypeParameter(FirTypeParameter parameter) {
                    collectTypeParameter(parameter, declarationTargets, typeParameters);
                    parameter.acceptChildren(this);
                }
                @Override public void visitResolvedImport(FirResolvedImport resolvedImport) {
                    collectImportAlias(resolvedImport, typeTargets, importAliases);
                    resolvedImport.acceptChildren(this);
                }
            };
            for (ModuleCompilerAnalyzedOutput output : result.getOutputs()) {
                for (org.jetbrains.kotlin.fir.declarations.FirFile file : output.getFir()) file.accept(typeParameterVisitor);
            }
            FirVisitorVoid visitor = new FirVisitorVoid() {
                @Override public void visitElement(FirElement element) { element.acceptChildren(this); }
                @Override public Object visitElement(FirElement element, Object ignored) {
                    visitElement(element);
                    return null;
                }
                @Override public void visitResolvedNamedReference(FirResolvedNamedReference reference) {
                    collectNamedReference(reference, declarationTargets, importAliases, identities, usages);
                    reference.acceptChildren(this);
                }
                @Override public void visitTypeParameter(FirTypeParameter parameter) {
                    collectTypeParameter(parameter, declarationTargets, typeParameters);
                    parameter.acceptChildren(this);
                }
                @Override public void visitResolvedTypeRef(FirResolvedTypeRef typeRef) {
                    collectTypeReference(typeRef, typeTargets, typeParameters, importAliases, identities, usages);
                    typeRef.acceptChildren(this);
                }
                @Override public void visitResolvedQualifier(FirResolvedQualifier qualifier) {
                    collectQualifier(qualifier, declarationTargets, importAliases, identities, usages);
                    qualifier.acceptChildren(this);
                }
                @Override public void visitResolvedImport(FirResolvedImport resolvedImport) {
                    collectImport(resolvedImport, typeTargets, identities, usages);
                    resolvedImport.acceptChildren(this);
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

    private static void collectNamedReference(
        FirResolvedNamedReference reference,
        Map<String, KotlinCompilerSymbolExtractor.ExtractedSymbol> targets,
        Map<String, String> importAliases,
        Set<String> identities,
        List<ExtractedUsage> usages
    ) {
        KtSourceElement usageSource = reference.getSource();
        KtSourceElement targetSource = reference.getResolvedSymbol().getSource();
        if (!(usageSource instanceof KtPsiSourceElement)) return;
        PsiElement usagePsi = ((KtPsiSourceElement) usageSource).getPsi();
        if (!(usagePsi instanceof KtNameReferenceExpression) || usagePsi instanceof KtOperationReferenceExpression) return;
        if (!(targetSource instanceof KtPsiSourceElement)) {
            if (reference.getResolvedSymbol() instanceof FirConstructorSymbol) {
                ClassId classId = ((FirConstructorSymbol) reference.getResolvedSymbol()).getCallableId().getClassId();
                if (classId != null && !classId.isLocal()) {
                    String binaryIdentity = binaryName(classId);
                    String simpleName = binaryIdentity.substring(
                        Math.max(binaryIdentity.lastIndexOf('.'), binaryIdentity.lastIndexOf('$')) + 1
                    );
                    if (usagePsi.getText().equals(simpleName) ||
                        matchesAliasIdentity(usagePsi, externalTypeIdentity(binaryIdentity), importAliases)) {
                        addExternalUsage(usagePsi, binaryIdentity, identities, usages);
                    }
                }
            } else if (reference.getResolvedSymbol() instanceof FirNamedFunctionSymbol) {
                FirNamedFunctionSymbol function = (FirNamedFunctionSymbol) reference.getResolvedSymbol();
                ClassId classId = function.getCallableId().getClassId();
                if (classId != null && !classId.isLocal() && usagePsi.getText().equals(function.getName().asString())) {
                    addExternalCallableUsage(
                        usagePsi, binaryName(classId), function.getName().asString(), identities, usages
                    );
                }
            }
            return;
        }
        PsiElement targetPsi = ((KtPsiSourceElement) targetSource).getPsi();
        if (parent(usagePsi, KtCallableReferenceExpression.class) != null ||
            parent(usagePsi, KtImportDirective.class) != null) return;
        PsiElement targetIdentifier;
        KtFile targetFile;
        if (targetPsi instanceof KtNamedFunction) {
            KtNamedFunction function = (KtNamedFunction) targetPsi;
            if (parent(function, KtBlockExpression.class) != null) return;
            targetIdentifier = function.getNameIdentifier();
            targetFile = function.getContainingKtFile();
        } else if (targetPsi instanceof KtProperty) {
            KtProperty property = (KtProperty) targetPsi;
            if (property.isLocal()) return;
            targetIdentifier = property.getNameIdentifier();
            targetFile = property.getContainingKtFile();
        } else if (targetPsi instanceof KtParameter) {
            KtParameter parameter = (KtParameter) targetPsi;
            targetIdentifier = parameter.getNameIdentifier();
            targetFile = parameter.getContainingKtFile();
            if (targetIdentifier == null || !targets.containsKey(declarationKey(
                canonicalPath(targetFile), targetIdentifier.getTextRange().getStartOffset()
            ))) return;
        } else {
            KtClassOrObject type = targetType(targetPsi);
            if (type == null || type.getClassId() == null || type.getClassId().isLocal()) return;
            targetIdentifier = typeIdentifier(type);
            targetFile = type.getContainingKtFile();
        }
        if (targetIdentifier == null) throw failure("kotlin.usageTargetLocationUnavailable");
        KotlinCompilerSymbolExtractor.ExtractedSymbol target = targets.get(
            declarationKey(canonicalPath(targetFile), targetIdentifier.getTextRange().getStartOffset())
        );
        if (target == null) throw failure("kotlin.usageTargetMissing");
        PsiElement usageIdentifier = ((KtNameReferenceExpression) usagePsi).getReferencedNameElement();
        if (usageIdentifier == null) throw failure("kotlin.usageLocationUnavailable");
        if (!usageIdentifier.getText().equals(reference.getName().asString()) ||
            !matchesTargetName(usageIdentifier, target, importAliases)) return;
        addUsage(usageIdentifier, target, identities, usages);
    }

    private static void collectTypeParameter(
        FirTypeParameter parameter,
        Map<String, KotlinCompilerSymbolExtractor.ExtractedSymbol> targets,
        Map<FirTypeParameterSymbol, KotlinCompilerSymbolExtractor.ExtractedSymbol> typeParameters
    ) {
        if (!(parameter.getSource() instanceof KtPsiSourceElement)) return;
        PsiElement psi = ((KtPsiSourceElement) parameter.getSource()).getPsi();
        KtTypeParameter declaration = psi instanceof KtTypeParameter ?
            (KtTypeParameter) psi : parent(psi, KtTypeParameter.class);
        if (declaration == null || declaration.getNameIdentifier() == null) return;
        PsiElement identifier = declaration.getNameIdentifier();
        KotlinCompilerSymbolExtractor.ExtractedSymbol target = targets.get(declarationKey(
            canonicalPath(declaration.getContainingKtFile()), identifier.getTextRange().getStartOffset()
        ));
        if (target != null && "TYPE_PARAMETER".equals(target.kind())) typeParameters.put(parameter.getSymbol(), target);
    }

    private static void collectTypeReference(
        FirResolvedTypeRef typeRef,
        Map<String, KotlinCompilerSymbolExtractor.ExtractedSymbol> targets,
        Map<FirTypeParameterSymbol, KotlinCompilerSymbolExtractor.ExtractedSymbol> typeParameters,
        Map<String, String> importAliases,
        Set<String> identities,
        List<ExtractedUsage> usages
    ) {
        if (!(typeRef.getSource() instanceof KtPsiSourceElement)) return;
        KotlinCompilerSymbolExtractor.ExtractedSymbol target;
        String externalIdentity = null;
        if (typeRef.getType() instanceof ConeClassLikeType) {
            ClassId classId = ((ConeClassLikeType) typeRef.getType()).getLookupTag().getClassId();
            String binaryIdentity = binaryName(classId);
            target = targets.get(binaryIdentity);
            if (target == null) externalIdentity = binaryIdentity;
        } else if (typeRef.getType() instanceof ConeTypeParameterType) {
            target = typeParameters.get(((ConeTypeParameterType) typeRef.getType()).getLookupTag()
                .getTypeParameterSymbol());
        } else return;
        PsiElement identifier = typeIdentifier(((KtPsiSourceElement) typeRef.getSource()).getPsi());
        if (identifier == null) return;
        if (target != null) {
            if (!matchesTargetName(identifier, target, importAliases)) return;
            addUsage(identifier, target, identities, usages);
        } else if (externalIdentity != null &&
            (identifier.getText().equals(externalIdentity.substring(
                Math.max(externalIdentity.lastIndexOf('.'), externalIdentity.lastIndexOf('$')) + 1
            )) || matchesAliasIdentity(identifier, externalTypeIdentity(externalIdentity), importAliases))) {
            addExternalUsage(identifier, externalIdentity, identities, usages);
        }
    }

    private static void collectQualifier(
        FirResolvedQualifier qualifier,
        Map<String, KotlinCompilerSymbolExtractor.ExtractedSymbol> targets,
        Map<String, String> importAliases,
        Set<String> identities,
        List<ExtractedUsage> usages
    ) {
        if (!(qualifier.getSource() instanceof KtPsiSourceElement) || qualifier.getClassId() == null ||
            qualifier.getClassId().isLocal()) return;
        PsiElement identifier = typeIdentifier(((KtPsiSourceElement) qualifier.getSource()).getPsi());
        if (identifier == null) return;
        if (qualifier.getSymbol().getSource() instanceof KtPsiSourceElement) {
            KtClassOrObject type = targetType(((KtPsiSourceElement) qualifier.getSymbol().getSource()).getPsi());
            if (type == null) return;
            PsiElement targetIdentifier = typeIdentifier(type);
            if (targetIdentifier == null) throw failure("kotlin.usageTargetLocationUnavailable");
            KotlinCompilerSymbolExtractor.ExtractedSymbol target = targets.get(
                declarationKey(canonicalPath(type.getContainingKtFile()), targetIdentifier.getTextRange().getStartOffset())
            );
            if (target == null) throw failure("kotlin.usageTargetMissing");
            if (matchesTargetName(identifier, target, importAliases)) addUsage(identifier, target, identities, usages);
        } else {
            String binaryIdentity = binaryName(qualifier.getClassId());
            String simpleName = binaryIdentity.substring(
                Math.max(binaryIdentity.lastIndexOf('.'), binaryIdentity.lastIndexOf('$')) + 1
            );
            if (identifier.getText().equals(simpleName) ||
                matchesAliasIdentity(identifier, externalTypeIdentity(binaryIdentity), importAliases)) {
                addExternalUsage(identifier, binaryIdentity, identities, usages);
            }
        }
    }

    private static void collectImportAlias(
        FirResolvedImport resolvedImport,
        Map<String, KotlinCompilerSymbolExtractor.ExtractedSymbol> targets,
        Map<String, String> importAliases
    ) {
        Name alias = resolvedImport.getAliasName();
        if (resolvedImport.isAllUnder() || alias == null || resolvedImport.getImportedFqName() == null ||
            !(resolvedImport.getSource() instanceof KtPsiSourceElement)) return;
        Name importedName = resolvedImport.getImportedName();
        if (importedName == null) return;
        ClassId parent = resolvedImport.getResolvedParentClassId();
        ClassId classId = parent == null ? ClassId.topLevel(resolvedImport.getImportedFqName()) :
            parent.createNestedClassId(importedName);
        String binaryIdentity = binaryName(classId);
        KotlinCompilerSymbolExtractor.ExtractedSymbol target = targets.get(binaryIdentity);
        String targetIdentity = target == null ? externalTypeIdentity(binaryIdentity) : target.identity();
        PsiElement source = ((KtPsiSourceElement) resolvedImport.getSource()).getPsi();
        KtImportDirective directive = source instanceof KtImportDirective ?
            (KtImportDirective) source : parent(source, KtImportDirective.class);
        if (directive == null || !alias.asString().equals(directive.getAliasName())) {
            throw failure("kotlin.usageAliasLocationUnavailable");
        }
        String key = aliasKey(canonicalPath(directive.getContainingKtFile()), alias.asString());
        String previous = importAliases.put(key, targetIdentity);
        if (previous != null && !previous.equals(targetIdentity)) throw failure("kotlin.usageAliasCollision");
    }

    private static void collectImport(
        FirResolvedImport resolvedImport,
        Map<String, KotlinCompilerSymbolExtractor.ExtractedSymbol> targets,
        Set<String> identities,
        List<ExtractedUsage> usages
    ) {
        if (resolvedImport.isAllUnder() || !(resolvedImport.getSource() instanceof KtPsiSourceElement)) return;
        Name importedName = resolvedImport.getImportedName();
        if (importedName == null || resolvedImport.getImportedFqName() == null) return;
        ClassId parent = resolvedImport.getResolvedParentClassId();
        ClassId classId = parent == null ? ClassId.topLevel(resolvedImport.getImportedFqName()) :
            parent.createNestedClassId(importedName);
        String binaryIdentity = binaryName(classId);
        KotlinCompilerSymbolExtractor.ExtractedSymbol target = targets.get(binaryIdentity);
        PsiElement identifier = typeIdentifier(((KtPsiSourceElement) resolvedImport.getSource()).getPsi());
        if (identifier == null) return;
        if (target != null) {
            if (!identifier.getText().equals(target.name())) return;
            addUsage(identifier, target, identities, usages);
        } else if (identifier.getText().equals(importedName.asString())) {
            addExternalUsage(identifier, binaryIdentity, identities, usages);
        }
    }

    private static boolean matchesAliasIdentity(
        PsiElement identifier,
        String targetIdentity,
        Map<String, String> importAliases
    ) {
        if (!(identifier.getContainingFile() instanceof KtFile)) return false;
        return targetIdentity.equals(importAliases.get(aliasKey(
            canonicalPath((KtFile) identifier.getContainingFile()), identifier.getText()
        )));
    }

    private static String externalTypeIdentity(String binaryIdentity) {
        return "external-jvm-type-v1:" + binaryIdentity;
    }

    private static boolean matchesTargetName(
        PsiElement identifier,
        KotlinCompilerSymbolExtractor.ExtractedSymbol target,
        Map<String, String> importAliases
    ) {
        if (identifier.getText().equals(target.name())) return true;
        return matchesAliasIdentity(identifier, target.identity(), importAliases);
    }

    private static String aliasKey(Path path, String alias) {
        return path + "\u0000" + alias;
    }

    private static void addUsage(
        PsiElement identifier,
        KotlinCompilerSymbolExtractor.ExtractedSymbol target,
        Set<String> identities,
        List<ExtractedUsage> usages
    ) {
        if (!(identifier.getContainingFile() instanceof KtFile)) throw failure("kotlin.usageLocationUnavailable");
        Path path = canonicalPath((KtFile) identifier.getContainingFile());
        int start = identifier.getTextRange().getStartOffset();
        int end = identifier.getTextRange().getEndOffset();
        String identity = path + "\u0000" + start + "\u0000" + end + "\u0000" + target.identity();
        if (!identities.add(identity)) return;
        if (usages.size() >= MAX_USAGES) throw failure("kotlin.usageLimitExceeded");
        usages.add(new ExtractedUsage(path.toString(), target.identity(), identifier.getText(), start, end));
    }

    private static void addExternalUsage(
        PsiElement identifier,
        String jvmBinaryName,
        Set<String> identities,
        List<ExtractedUsage> usages
    ) {
        if (!(identifier.getContainingFile() instanceof KtFile)) throw failure("kotlin.usageLocationUnavailable");
        Path path = canonicalPath((KtFile) identifier.getContainingFile());
        int start = identifier.getTextRange().getStartOffset();
        int end = identifier.getTextRange().getEndOffset();
        String targetIdentity = "external-jvm-type-v1:" + jvmBinaryName;
        String identity = path + "\u0000" + start + "\u0000" + end + "\u0000" + targetIdentity;
        if (!identities.add(identity)) return;
        if (usages.size() >= MAX_USAGES) throw failure("kotlin.usageLimitExceeded");
        usages.add(new ExtractedUsage(path.toString(), targetIdentity, identifier.getText(), start, end));
    }

    private static void addExternalCallableUsage(
        PsiElement identifier,
        String jvmOwner,
        String callableName,
        Set<String> identities,
        List<ExtractedUsage> usages
    ) {
        if (!(identifier.getContainingFile() instanceof KtFile)) throw failure("kotlin.usageLocationUnavailable");
        Path path = canonicalPath((KtFile) identifier.getContainingFile());
        int start = identifier.getTextRange().getStartOffset();
        int end = identifier.getTextRange().getEndOffset();
        String targetIdentity = "external-jvm-callable-v1:" + jvmOwner + "#" + callableName;
        String identity = path + "\u0000" + start + "\u0000" + end + "\u0000" + targetIdentity;
        if (!identities.add(identity)) return;
        if (usages.size() >= MAX_USAGES) throw failure("kotlin.usageLimitExceeded");
        usages.add(new ExtractedUsage(path.toString(), targetIdentity, identifier.getText(), start, end));
    }

    private static KtClassOrObject targetType(PsiElement target) {
        if (target instanceof KtClassOrObject) return (KtClassOrObject) target;
        if (target instanceof KtConstructor) return ((KtConstructor<?>) target).getContainingClassOrObject();
        return null;
    }

    private static PsiElement typeIdentifier(KtClassOrObject type) {
        PsiElement identifier = type.getNameIdentifier();
        if (identifier == null && type instanceof KtObjectDeclaration && ((KtObjectDeclaration) type).isCompanion()) {
            identifier = ((KtObjectDeclaration) type).getObjectKeyword();
        }
        return identifier;
    }

    private static PsiElement typeIdentifier(PsiElement source) {
        if (source instanceof KtNameReferenceExpression) {
            return ((KtNameReferenceExpression) source).getReferencedNameElement();
        }
        if (source instanceof KtTypeReference) {
            return typeIdentifier(((KtTypeReference) source).getTypeElement());
        }
        if (source instanceof KtNullableType) {
            return typeIdentifier(((KtNullableType) source).getInnerType());
        }
        if (source instanceof KtUserType) {
            KtSimpleNameExpression reference = ((KtUserType) source).getReferenceExpression();
            return reference == null ? null : reference.getReferencedNameElement();
        }
        if (source instanceof KtDotQualifiedExpression) {
            return typeIdentifier(((KtDotQualifiedExpression) source).getSelectorExpression());
        }
        if (source instanceof KtImportDirective) {
            return typeIdentifier(((KtImportDirective) source).getImportedReference());
        }
        return null;
    }

    private static String binaryName(ClassId classId) {
        StringBuilder result = new StringBuilder();
        if (!classId.getPackageFqName().isRoot()) result.append(classId.getPackageFqName().asString()).append('.');
        boolean first = true;
        for (Name segment : classId.getRelativeClassName().pathSegments()) {
            if (!first) result.append('$');
            result.append(segment.asString());
            first = false;
        }
        return result.toString();
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
