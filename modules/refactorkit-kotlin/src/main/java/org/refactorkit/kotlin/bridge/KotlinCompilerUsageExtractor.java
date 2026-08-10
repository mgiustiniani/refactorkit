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
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.config.ApiVersion;
import org.jetbrains.kotlin.config.CommonConfigurationKeys;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.config.JVMConfigurationKeys;
import org.jetbrains.kotlin.config.JvmTarget;
import org.jetbrains.kotlin.config.LanguageVersion;
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl;
import org.jetbrains.kotlin.fir.FirElement;
import org.jetbrains.kotlin.fir.declarations.FirFunction;
import org.jetbrains.kotlin.fir.declarations.FirProperty;
import org.jetbrains.kotlin.fir.declarations.FirReceiverParameter;
import org.jetbrains.kotlin.fir.declarations.FirResolvedImport;
import org.jetbrains.kotlin.fir.declarations.FirTypeParameter;
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall;
import org.jetbrains.kotlin.fir.expressions.FirNamedArgumentExpression;
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier;
import org.jetbrains.kotlin.fir.expressions.impl.FirResolvedArgumentList;
import org.jetbrains.kotlin.fir.pipeline.FirResult;
import org.jetbrains.kotlin.fir.pipeline.ModuleCompilerAnalyzedOutput;
import org.jetbrains.kotlin.fir.scopes.jvm.SignatureUtilsKt;
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference;
import org.jetbrains.kotlin.fir.types.ConeClassLikeType;
import org.jetbrains.kotlin.fir.types.ConeKotlinType;
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType;
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef;
import org.jetbrains.kotlin.fir.types.FirTypeRef;
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol;
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol;
import org.jetbrains.kotlin.fir.symbols.impl.FirFieldSymbol;
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol;
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol;
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol;
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol;
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid;
import org.jetbrains.kotlin.name.ClassId;
import org.jetbrains.kotlin.name.FqName;
import org.jetbrains.kotlin.name.Name;
import org.jetbrains.kotlin.load.java.JvmAbi;
import org.jetbrains.kotlin.load.kotlin.JvmPackagePartSource;
import org.jetbrains.kotlin.resolve.jvm.JvmClassName;
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedContainerSource;
import org.jetbrains.kotlin.psi.KtBlockExpression;
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression;
import org.jetbrains.kotlin.psi.KtClass;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtConstructor;
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression;
import org.jetbrains.kotlin.psi.KtEnumEntry;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtImportDirective;
import org.jetbrains.kotlin.psi.KtNameReferenceExpression;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.KtNullableType;
import org.jetbrains.kotlin.psi.KtObjectDeclaration;
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression;
import org.jetbrains.kotlin.psi.KtParameter;
import org.jetbrains.kotlin.psi.KtProperty;
import org.jetbrains.kotlin.psi.KtSimpleNameExpression;
import org.jetbrains.kotlin.psi.KtTypeReference;
import org.jetbrains.kotlin.psi.KtTypeParameter;
import org.jetbrains.kotlin.psi.KtUserType;
import org.jetbrains.kotlin.psi.KtValueArgument;

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
    private static final int MAX_SELECTION_CHARS = 512;

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
            String key = declarationTargetKey(Paths.get(symbol.path()), symbol.startOffset(), symbol.kind());
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
                @Override public void visitFunctionCall(FirFunctionCall call) {
                    collectNamedArguments(call, declarationTargets, identities, usages);
                    collectImplicitFunctionBinding(call, declarationTargets, identities, usages);
                    call.acceptChildren(this);
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

    private static void collectImplicitFunctionBinding(
        FirFunctionCall call,
        Map<String, KotlinCompilerSymbolExtractor.ExtractedSymbol> targets,
        Set<String> identities,
        List<ExtractedUsage> usages
    ) {
        if (!(call.getCalleeReference() instanceof FirResolvedNamedReference)) return;
        FirResolvedNamedReference reference = (FirResolvedNamedReference) call.getCalleeReference();
        if (!(reference.getResolvedSymbol() instanceof FirNamedFunctionSymbol)) return;
        FirNamedFunctionSymbol function = (FirNamedFunctionSymbol) reference.getResolvedSymbol();
        KtSourceElement referenceSource = reference.getSource();
        PsiElement referencePsi = referenceSource instanceof KtPsiSourceElement
            ? ((KtPsiSourceElement) referenceSource).getPsi() : null;
        if (referencePsi instanceof KtSimpleNameExpression) {
            PsiElement identifier = ((KtSimpleNameExpression) referencePsi).getReferencedNameElement();
            if (identifier != null && (identifier.getText().equals(function.getName().asString()) ||
                referencePsi instanceof KtOperationReferenceExpression)) return;
        }
        KtSourceElement callSource = call.getSource();
        PsiElement usagePsi = callSource instanceof KtPsiSourceElement
            ? ((KtPsiSourceElement) callSource).getPsi() : referencePsi;
        if (usagePsi == null) return;
        addResolvedFunctionUsage(usagePsi, function, targets, identities, usages);
    }

    private static void collectNamedArguments(
        FirFunctionCall call,
        Map<String, KotlinCompilerSymbolExtractor.ExtractedSymbol> targets,
        Set<String> identities,
        List<ExtractedUsage> usages
    ) {
        if (!(call.getArgumentList() instanceof FirResolvedArgumentList)) return;
        FirResolvedArgumentList arguments = (FirResolvedArgumentList) call.getArgumentList();
        for (org.jetbrains.kotlin.fir.expressions.FirExpression original :
                arguments.getOriginalArgumentList().getArguments()) {
            if (!(original instanceof FirNamedArgumentExpression)) continue;
            FirNamedArgumentExpression argument = (FirNamedArgumentExpression) original;
            org.jetbrains.kotlin.fir.declarations.FirValueParameter mapped = arguments.getMapping().get(argument);
            if (mapped == null) mapped = arguments.getMapping().get(argument.getExpression());
            if (mapped == null) throw failure("kotlin.usageNamedArgumentMappingUnavailable");
            KtSourceElement parameterSource = mapped.getSource();
            KtSourceElement argumentSource = argument.getSource();
            if (!(parameterSource instanceof KtPsiSourceElement) || !(argumentSource instanceof KtPsiSourceElement)) {
                continue;
            }
            PsiElement parameterPsi = ((KtPsiSourceElement) parameterSource).getPsi();
            KtParameter parameter = parameterPsi instanceof KtParameter
                ? (KtParameter) parameterPsi : parent(parameterPsi, KtParameter.class);
            PsiElement parameterIdentifier = parameter == null ? null : parameter.getNameIdentifier();
            if (parameter == null || parameterIdentifier == null) continue;
            KotlinCompilerSymbolExtractor.ExtractedSymbol target = targets.get(declarationTargetKey(
                canonicalPath(parameter.getContainingKtFile()), parameterIdentifier.getTextRange().getStartOffset(),
                "PARAMETER"
            ));
            if (target == null) continue;
            PsiElement argumentPsi = ((KtPsiSourceElement) argumentSource).getPsi();
            KtValueArgument valueArgument = argumentPsi instanceof KtValueArgument
                ? (KtValueArgument) argumentPsi : parent(argumentPsi, KtValueArgument.class);
            org.jetbrains.kotlin.psi.ValueArgumentName argumentName = valueArgument == null
                ? null : valueArgument.getArgumentName();
            PsiElement label = argumentName == null ? null : argumentName.getReferenceExpression().getReferencedNameElement();
            if (label == null || !label.getText().equals(target.name())) {
                throw failure("kotlin.usageNamedArgumentLocationUnavailable");
            }
            addUsage(label, target, identities, usages);
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
        if (reference.getResolvedSymbol() instanceof FirFieldSymbol) {
            throw failure("kotlin.usageExternalFieldUnsupported");
        }
        if (!(usagePsi instanceof KtSimpleNameExpression)) {
            if (reference.getResolvedSymbol() instanceof FirNamedFunctionSymbol) {
                addResolvedFunctionUsage(
                    usagePsi, (FirNamedFunctionSymbol) reference.getResolvedSymbol(), targets, identities, usages
                );
            }
            return;
        }
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
                    addExternalCallableUsage(
                        usagePsi,
                        binaryIdentity,
                        "<init>",
                        jvmDescriptor(((FirConstructorSymbol) reference.getResolvedSymbol()).getFir(), "<init>"),
                        identities,
                        usages
                    );
                    }
                }
            } else if (reference.getResolvedSymbol() instanceof FirNamedFunctionSymbol) {
                addResolvedFunctionUsage(
                    usagePsi, (FirNamedFunctionSymbol) reference.getResolvedSymbol(), targets, identities, usages
                );
            } else if (reference.getResolvedSymbol() instanceof FirPropertySymbol) {
                FirPropertySymbol property = (FirPropertySymbol) reference.getResolvedSymbol();
                String owner = externalCallableOwner(
                    property.getCallableId().getClassId(), property.getFir().getContainerSource()
                );
                String propertyName = property.getName().asString();
                String getterName = JvmAbi.getterName(propertyName);
                if (owner != null && property.getFir().getGetter() == null) {
                    throw failure("kotlin.usageExternalFieldUnsupported");
                }
                if (owner != null && usagePsi.getText().equals(propertyName)) {
                    addExternalCallableUsage(
                        usagePsi,
                        owner,
                        getterName,
                        propertyGetterDescriptor(property.getFir()),
                        identities,
                        usages
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
        String targetKind;
        KtConstructor<?> sourceConstructor = reference.getResolvedSymbol() instanceof FirConstructorSymbol
            ? sourceConstructor(targetPsi) : null;
        if (sourceConstructor != null) {
            targetIdentifier = constructorIdentifier(sourceConstructor);
            targetFile = sourceConstructor.getContainingKtFile();
            targetKind = "CONSTRUCTOR";
        } else if (targetPsi instanceof KtNamedFunction) {
            KtNamedFunction function = (KtNamedFunction) targetPsi;
            if (parent(function, KtBlockExpression.class) != null) return;
            targetIdentifier = function.getNameIdentifier();
            targetFile = function.getContainingKtFile();
            targetKind = "FUNCTION";
        } else if (targetPsi instanceof KtProperty) {
            KtProperty property = (KtProperty) targetPsi;
            if (property.isLocal()) return;
            targetIdentifier = property.getNameIdentifier();
            targetFile = property.getContainingKtFile();
            targetKind = "PROPERTY";
        } else if (targetPsi instanceof KtParameter) {
            KtParameter parameter = (KtParameter) targetPsi;
            targetIdentifier = parameter.getNameIdentifier();
            targetFile = parameter.getContainingKtFile();
            if (reference.getResolvedSymbol() instanceof FirPropertySymbol) {
                targetKind = "PROPERTY";
            } else if (reference.getResolvedSymbol() instanceof FirValueParameterSymbol) {
                targetKind = "PARAMETER";
            } else {
                return;
            }
        } else {
            KtClassOrObject type = targetType(targetPsi);
            ClassId targetClassId = type == null ? null : type.getClassId();
            if (type == null || type instanceof KtEnumEntry || targetClassId == null || targetClassId.isLocal()) return;
            targetIdentifier = typeIdentifier(type);
            targetFile = type.getContainingKtFile();
            targetKind = typeKind(type);
        }
        if (targetIdentifier == null) throw failure("kotlin.usageTargetLocationUnavailable");
        Path targetPath = canonicalPath(targetFile);
        int targetOffset = targetIdentifier.getTextRange().getStartOffset();
        KotlinCompilerSymbolExtractor.ExtractedSymbol target = targets.get(declarationTargetKey(
            targetPath, targetOffset, targetKind
        ));
        if (target == null && targetPsi instanceof KtParameter &&
            !targets.containsKey(declarationTargetKey(targetPath, targetOffset, "PARAMETER")) &&
            !targets.containsKey(declarationTargetKey(targetPath, targetOffset, "PROPERTY"))) return;
        if (target == null) throw failure("kotlin.usageTargetMissing");
        PsiElement usageIdentifier = ((KtSimpleNameExpression) usagePsi).getReferencedNameElement();
        if (usageIdentifier == null) throw failure("kotlin.usageLocationUnavailable");
        String resolvedSourceName = "CONSTRUCTOR".equals(target.kind())
            ? target.name() : reference.getName().asString();
        if (!usageIdentifier.getText().equals(resolvedSourceName) ||
            !matchesTargetName(usageIdentifier, target, importAliases)) {
            if (reference.getResolvedSymbol() instanceof FirNamedFunctionSymbol && "FUNCTION".equals(target.kind())) {
                addUsage(usageIdentifier, target, identities, usages);
            }
            return;
        }
        addUsage(usageIdentifier, target, identities, usages);
    }

    private static void addResolvedFunctionUsage(
        PsiElement usagePsi,
        FirNamedFunctionSymbol function,
        Map<String, KotlinCompilerSymbolExtractor.ExtractedSymbol> targets,
        Set<String> identities,
        List<ExtractedUsage> usages
    ) {
        PsiElement location = boundedUsageElement(usagePsi);
        if (location == null || !(location.getContainingFile() instanceof KtFile)) return;
        KtSourceElement targetSource = function.getSource();
        if (targetSource instanceof KtPsiSourceElement) {
            PsiElement targetPsi = ((KtPsiSourceElement) targetSource).getPsi();
            KtNamedFunction declaration = targetPsi instanceof KtNamedFunction
                ? (KtNamedFunction) targetPsi : parent(targetPsi, KtNamedFunction.class);
            if (declaration == null || parent(declaration, KtBlockExpression.class) != null) return;
            PsiElement identifier = declaration.getNameIdentifier();
            if (identifier == null) throw failure("kotlin.usageTargetLocationUnavailable");
            KotlinCompilerSymbolExtractor.ExtractedSymbol target = targets.get(declarationTargetKey(
                canonicalPath(declaration.getContainingKtFile()), identifier.getTextRange().getStartOffset(),
                "FUNCTION"
            ));
            if (target == null) throw failure("kotlin.usageTargetMissing");
            addUsage(location, target, identities, usages);
            return;
        }
        String owner = externalCallableOwner(function.getCallableId().getClassId(), function.getFir().getContainerSource());
        if (owner != null) {
            addExternalCallableUsage(
                location, owner, function.getName().asString(),
                jvmDescriptor(function.getFir(), function.getName().asString()), identities, usages
            );
        }
    }

    private static PsiElement boundedUsageElement(PsiElement candidate) {
        String text = candidate.getText();
        if (isBoundedSelection(text)) return candidate;
        KtSimpleNameExpression simpleName = PsiTreeUtil.findChildOfType(candidate, KtSimpleNameExpression.class);
        PsiElement identifier = simpleName == null ? null : simpleName.getReferencedNameElement();
        return identifier != null && isBoundedSelection(identifier.getText()) ? identifier : null;
    }

    private static boolean isBoundedSelection(String text) {
        return text != null && !text.isEmpty() && text.length() <= MAX_SELECTION_CHARS &&
            text.indexOf('\u0000') < 0 && text.indexOf('\r') < 0 && text.indexOf('\n') < 0;
    }

    private static void collectTypeParameter(
        FirTypeParameter parameter,
        Map<String, KotlinCompilerSymbolExtractor.ExtractedSymbol> targets,
        Map<FirTypeParameterSymbol, KotlinCompilerSymbolExtractor.ExtractedSymbol> typeParameters
    ) {
        KtSourceElement parameterSource = parameter.getSource();
        if (!(parameterSource instanceof KtPsiSourceElement)) return;
        PsiElement psi = ((KtPsiSourceElement) parameterSource).getPsi();
        KtTypeParameter declaration = psi instanceof KtTypeParameter ?
            (KtTypeParameter) psi : parent(psi, KtTypeParameter.class);
        PsiElement identifier = declaration == null ? null : declaration.getNameIdentifier();
        if (declaration == null || identifier == null) return;
        KotlinCompilerSymbolExtractor.ExtractedSymbol target = targets.get(declarationTargetKey(
            canonicalPath(declaration.getContainingKtFile()), identifier.getTextRange().getStartOffset(),
            "TYPE_PARAMETER"
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
        KtSourceElement source = typeRef.getSource();
        if (!(source instanceof KtPsiSourceElement)) return;
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
        PsiElement typePsi = ((KtPsiSourceElement) source).getPsi();
        PsiElement identifier = typeIdentifier(typePsi);
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
        KtSourceElement qualifierSource = qualifier.getSource();
        ClassId qualifierClassId = qualifier.getClassId();
        if (!(qualifierSource instanceof KtPsiSourceElement) || qualifierClassId == null ||
            qualifierClassId.isLocal()) return;
        PsiElement psi = ((KtPsiSourceElement) qualifierSource).getPsi();
        PsiElement identifier = typeIdentifier(psi);
        if (identifier == null) return;
        FirClassLikeSymbol<?> symbol = qualifier.getSymbol();
        if (symbol == null) return;
        KtSourceElement symbolSource = symbol.getSource();
        if (symbolSource instanceof KtPsiSourceElement) {
            KtClassOrObject type = targetType(((KtPsiSourceElement) symbolSource).getPsi());
            if (type == null) return;
            PsiElement targetIdentifier = typeIdentifier(type);
            if (targetIdentifier == null) throw failure("kotlin.usageTargetLocationUnavailable");
            KotlinCompilerSymbolExtractor.ExtractedSymbol target = targets.get(declarationTargetKey(
                canonicalPath(type.getContainingKtFile()), targetIdentifier.getTextRange().getStartOffset(),
                typeKind(type)
            ));
            if (target == null) throw failure("kotlin.usageTargetMissing");
            if (matchesTargetName(identifier, target, importAliases)) addUsage(identifier, target, identities, usages);
        } else {
            String binaryIdentity = binaryName(qualifierClassId);
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
        KtSourceElement importSource = resolvedImport.getSource();
        FqName importedFqName = resolvedImport.getImportedFqName();
        if (resolvedImport.isAllUnder() || alias == null || importedFqName == null ||
            !(importSource instanceof KtPsiSourceElement)) return;
        Name importedName = resolvedImport.getImportedName();
        if (importedName == null) return;
        ClassId parent = resolvedImport.getResolvedParentClassId();
        ClassId classId = parent == null ? ClassId.topLevel(importedFqName) :
            parent.createNestedClassId(importedName);
        String binaryIdentity = binaryName(classId);
        KotlinCompilerSymbolExtractor.ExtractedSymbol target = targets.get(binaryIdentity);
        String targetIdentity = target == null ? externalTypeIdentity(binaryIdentity) : target.identity();
        PsiElement sourcePsi = ((KtPsiSourceElement) importSource).getPsi();
        KtImportDirective directive = sourcePsi instanceof KtImportDirective ?
            (KtImportDirective) sourcePsi : parent(sourcePsi, KtImportDirective.class);
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
        KtSourceElement importSource = resolvedImport.getSource();
        if (resolvedImport.isAllUnder() || !(importSource instanceof KtPsiSourceElement)) return;
        Name importedName = resolvedImport.getImportedName();
        FqName importedFqName = resolvedImport.getImportedFqName();
        if (importedName == null || importedFqName == null) return;
        ClassId parent = resolvedImport.getResolvedParentClassId();
        ClassId classId = parent == null ? ClassId.topLevel(importedFqName) :
            parent.createNestedClassId(importedName);
        String binaryIdentity = binaryName(classId);
        KotlinCompilerSymbolExtractor.ExtractedSymbol target = targets.get(binaryIdentity);
        PsiElement importPsi = ((KtPsiSourceElement) importSource).getPsi();
        PsiElement identifier = typeIdentifier(importPsi);
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

    private static String externalCallableOwner(ClassId classId, DeserializedContainerSource container) {
        if (classId != null) return classId.isLocal() ? null : binaryName(classId);
        if (!(container instanceof JvmPackagePartSource)) return null;
        JvmPackagePartSource packagePart = (JvmPackagePartSource) container;
        JvmClassName owner = packagePart.getFacadeClassName();
        if (owner == null) owner = packagePart.getClassName();
        return owner.getInternalName().replace('/', '.');
    }

    private static void addExternalCallableUsage(
        PsiElement identifier,
        String jvmOwner,
        String callableName,
        String jvmDescriptor,
        Set<String> identities,
        List<ExtractedUsage> usages
    ) {
        if (!(identifier.getContainingFile() instanceof KtFile)) throw failure("kotlin.usageLocationUnavailable");
        Path path = canonicalPath((KtFile) identifier.getContainingFile());
        int start = identifier.getTextRange().getStartOffset();
        int end = identifier.getTextRange().getEndOffset();
        String targetIdentity = "external-jvm-callable-v1:" + jvmOwner + "#" + callableName + jvmDescriptor;
        String identity = path + "\u0000" + start + "\u0000" + end + "\u0000" + targetIdentity;
        if (!identities.add(identity)) return;
        if (usages.size() >= MAX_USAGES) throw failure("kotlin.usageLimitExceeded");
        usages.add(new ExtractedUsage(path.toString(), targetIdentity, identifier.getText(), start, end));
    }

    private static String propertyGetterDescriptor(FirProperty property) {
        StringBuilder descriptor = new StringBuilder("(");
        property.getContextReceivers().forEach(receiver ->
            descriptor.append(jvmTypeDescriptor(receiver.getTypeRef()))
        );
        FirReceiverParameter extensionReceiver = property.getReceiverParameter();
        if (extensionReceiver != null) descriptor.append(jvmTypeDescriptor(extensionReceiver.getTypeRef()));
        return descriptor.append(')').append(jvmTypeDescriptor(property.getReturnTypeRef())).toString();
    }

    private static String jvmTypeDescriptor(FirTypeRef typeRef) {
        if (!(typeRef instanceof FirResolvedTypeRef)) throw failure("kotlin.usageCallableDescriptorUnavailable");
        Function1<FirTypeRef, ConeKotlinType> resolver = candidate ->
            candidate instanceof FirResolvedTypeRef ? ((FirResolvedTypeRef) candidate).getType() : null;
        String descriptor = SignatureUtilsKt.computeJvmDescriptorRepresentation(
            ((FirResolvedTypeRef) typeRef).getType(), resolver
        );
        if (descriptor == null || descriptor.isEmpty()) throw failure("kotlin.usageCallableDescriptorUnavailable");
        return descriptor.replace('.', '$');
    }

    private static String jvmDescriptor(FirFunction function, String jvmName) {
        Function1<FirTypeRef, ConeKotlinType> resolver = typeRef ->
            typeRef instanceof FirResolvedTypeRef ? ((FirResolvedTypeRef) typeRef).getType() : null;
        String signature = SignatureUtilsKt.computeJvmDescriptor(function, jvmName, true, resolver);
        if (signature != null) signature = signature.replace('.', '$');
        if (signature == null || !signature.startsWith(jvmName + "(")) {
            throw failure("kotlin.usageCallableDescriptorUnavailable");
        }
        String descriptor = signature.substring(jvmName.length());
        StringBuilder leadingReceivers = new StringBuilder();
        function.getContextReceivers().forEach(receiver ->
            leadingReceivers.append(jvmTypeDescriptor(receiver.getTypeRef()))
        );
        FirReceiverParameter extensionReceiver = function.getReceiverParameter();
        if (extensionReceiver != null) leadingReceivers.append(jvmTypeDescriptor(extensionReceiver.getTypeRef()));
        return leadingReceivers.length() == 0 ? descriptor :
            "(" + leadingReceivers + descriptor.substring(1);
    }

    private static KtConstructor<?> sourceConstructor(PsiElement source) {
        KtConstructor<?> constructor = source instanceof KtConstructor
            ? (KtConstructor<?>) source : parent(source, KtConstructor.class);
        if (constructor != null) return constructor;
        KtClass klass = source instanceof KtClass ? (KtClass) source : parent(source, KtClass.class);
        return klass == null ? null : klass.getPrimaryConstructor();
    }

    private static PsiElement constructorIdentifier(KtConstructor<?> constructor) {
        PsiElement keyword = constructor.getConstructorKeyword();
        if (keyword != null) return keyword;
        return constructor.getValueParameterList() == null
            ? null : constructor.getValueParameterList().getLeftParenthesis();
    }

    private static String typeKind(KtClassOrObject type) {
        if (!(type instanceof KtClass)) return "OBJECT";
        KtClass klass = (KtClass) type;
        if (klass.isAnnotation()) return "ANNOTATION";
        if (klass.isInterface()) return "INTERFACE";
        if (klass.isEnum()) return "ENUM";
        return "CLASS";
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

    private static String declarationTargetKey(Path path, int startOffset, String kind) {
        return declarationKey(path, startOffset) + "\u0000" + kind;
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
