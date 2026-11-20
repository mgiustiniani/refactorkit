package org.refactorkit.java

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.refactorkit.core.SemanticProcessProvenance
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import java.nio.file.Path
import java.time.Instant

/** REQ-JAVA-PROJECT-CONTEXT-001: native evidence bridge, distinct from compiler qualification. */
class JavaNativeLaunchSelectorSpec : StringSpec({
    val snapshot = "a".repeat(64); val sdk = "b".repeat(64); val java = "c".repeat(64)
    val source = Path.of("Main.java")
    fun main(owner: String = "demo.Child", descriptor: String = "()V", modifiers: Int = 0,
             shape: JavaNativeMainParameterShape = JavaNativeMainParameterShape.NONE) =
        JavaNativeLaunchMethodFacts(owner, descriptor, modifiers, shape,
            if (shape == JavaNativeMainParameterShape.NONE) 0 else 1, true, false, false, false)
    fun ctor(owner: String = "demo.Child", parameters: Boolean = false, modifiers: Int = 0) =
        JavaNativeLaunchMethodFacts(owner, if (parameters) "(I)V" else "()V", modifiers,
            if (parameters) JavaNativeMainParameterShape.OTHER else JavaNativeMainParameterShape.NONE,
            if (parameters) 1 else 0, true, true, !parameters, false)
    fun node(owner: String = "demo.Child", methods: List<JavaNativeLaunchMethodFacts> = listOf(ctor(), main()),
             kind: JavaNativeLaunchClassKind = JavaNativeLaunchClassKind.CLASS, modifiers: Int = 0,
             local: Boolean = false, member: Boolean = false, anonymous: Boolean = false,
             parent: String? = null) = JavaNativeLaunchClassFacts(owner, "demo", kind, modifiers,
        true, local, member, anonymous, parent, emptyList(), methods)
    fun capture(nodes: Map<String, JavaNativeLaunchClassFacts> = mapOf("demo.Child" to node()),
                effective: List<JavaNativeLaunchMethodFacts> = listOf(main()), owner: String = "demo.Child",
                complete: Boolean = true, compact: Boolean = false,
                context: JavaNativeLaunchCaptureContext = JavaNativeLaunchCaptureContext(snapshot, 25, 25, 25, "d".repeat(64)),
                platform: String = sdk, executableHash: String = java) = JavaCompilerLaunchResolution.Available(
        listOf(JavaCompilerLaunchSource(source, listOf(JavaNativeLaunchTypeReadout.Available(
            SourceRange(SourcePosition(0, 0), SourcePosition(2, 0)),
            if (compact) JavaNativeLaunchSourceForm.COMPACT else JavaNativeLaunchSourceForm.DECLARED,
            JavaNativeLaunchGraph(owner, nodes, effective, complete))))), platform, java, "e".repeat(64),
        SemanticProcessProvenance("java-launch-facts", Path.of("/sdk/bin/java"), executableHash, "f".repeat(64),
            Path.of("/private/overlay"), 100, Instant.EPOCH), context)
    fun select(value: JavaCompilerLaunchResolution, owner: String = "demo.Child") = JavaNativeLaunchSelector.select(
        JavaMainLaunchProfile.JAVA25, value, snapshot, sdk, source, owner)
    fun refused(value: JavaCompilerLaunchResolution, reason: JavaMainLaunchRefusal) {
        select(value) shouldBe JavaMainLaunchSelection.Refused(reason)
    }

    "inherited instance main keeps both owners and requires the launch class own construction" {
        val method = main("demo.Base")
        val base = node("demo.Base", listOf(ctor("demo.Base"), method))
        val child = node(methods = listOf(ctor()), parent = "demo.Base")
        val selected = select(capture(mapOf("demo.Child" to child, "demo.Base" to base), listOf(method)))
            .shouldBeInstanceOf<JavaMainLaunchSelection.Selected>()
        selected.binaryName shouldBe "demo.Child"; selected.declaringBinaryName shouldBe "demo.Base"
        selected.inherited shouldBe true; selected.invocation shouldBe JavaMainInvocation.INSTANCE
        selected.constructionDescriptor shouldBe "()V"
        refused(capture(mapOf("demo.Child" to node(methods = listOf(ctor(parameters = true)), parent = "demo.Base"),
            "demo.Base" to base), listOf(method)), JavaMainLaunchRefusal.INSTANCE_CREATION_INELIGIBLE)
    }

    "native construction facts separate nonprivate zero argument classes and records from ineligible types" {
        for (access in listOf(0, 1, 4)) {
            select(capture(mapOf("demo.Child" to node(methods = listOf(ctor(modifiers = access), main())))))
                .shouldBeInstanceOf<JavaMainLaunchSelection.Selected>().constructionDescriptor shouldBe "()V"
        }
        val ineligible = listOf(
            node(methods = listOf(ctor(modifiers = 2), main())),
            node(methods = listOf(ctor(parameters = true), main())),
            node(modifiers = 1024), node(member = true),
            node(kind = JavaNativeLaunchClassKind.ENUM), node(kind = JavaNativeLaunchClassKind.INTERFACE),
            node(kind = JavaNativeLaunchClassKind.ANNOTATION),
        )
        for (type in ineligible) refused(capture(mapOf("demo.Child" to type)), JavaMainLaunchRefusal.INSTANCE_CREATION_INELIGIBLE)
        for (type in listOf(node(member = true, modifiers = 8), node(kind = JavaNativeLaunchClassKind.RECORD))) {
            select(capture(mapOf("demo.Child" to type))).shouldBeInstanceOf<JavaMainLaunchSelection.Selected>()
                .constructionDescriptor shouldBe "()V"
        }
    }

    "static invocation ignores construction while local anonymous or missing construction remains unproved for instances" {
        val static = main(modifiers = 8)
        val abstract = node(methods = listOf(ctor(modifiers = 2), static), modifiers = 1024)
        select(capture(mapOf("demo.Child" to abstract), listOf(static)))
            .shouldBeInstanceOf<JavaMainLaunchSelection.Selected>().constructionDescriptor shouldBe null
        for (type in listOf(node(local = true), node(anonymous = true), node(methods = listOf(main())))) {
            refused(capture(mapOf("demo.Child" to type)), JavaMainLaunchRefusal.INSTANCE_CREATION_UNAVAILABLE)
        }
    }

    "declared shapes and exact source names reach policy without erasure or TypeIdentifier reconstruction" {
        val generic = main(descriptor = "([Ljava/lang/String;)V", modifiers = 8, shape = JavaNativeMainParameterShape.OTHER)
        refused(capture(mapOf("demo.Child" to node(methods = listOf(ctor(), generic))), listOf(generic)),
            JavaMainLaunchRefusal.UNQUALIFIED_DECLARATION)
        for (owner in listOf("record", "Or\u200Cder", "_\u200C")) {
            val method = main(owner)
            val type = node(owner, listOf(ctor(owner), method))
            select(capture(mapOf(owner to type), listOf(method), owner, compact = true), owner)
                .shouldBeInstanceOf<JavaMainLaunchSelection.Selected>().binaryName shouldBe owner
        }
        val mismatch = main().copy(parameterCount = 1)
        refused(capture(mapOf("demo.Child" to node(methods = listOf(ctor(), mismatch))), listOf(mismatch)),
            JavaMainLaunchRefusal.MALFORMED_NATIVE_FACTS)
    }

    "incomplete malformed or cross scope native evidence cannot establish an empty success" {
        refused(capture(complete = false), JavaMainLaunchRefusal.INCOMPLETE_NATIVE_INVENTORY)
        refused(capture(emptyMap(), emptyList()), JavaMainLaunchRefusal.MALFORMED_NATIVE_FACTS)
        refused(capture(mapOf("demo.Child" to node(parent = "demo.Missing"))), JavaMainLaunchRefusal.MALFORMED_NATIVE_FACTS)
        refused(capture(mapOf("demo.Child" to node(parent = "demo.Child"))), JavaMainLaunchRefusal.MALFORMED_NATIVE_FACTS)
        refused(capture(effective = listOf(main("demo.Unrelated"))), JavaMainLaunchRefusal.MALFORMED_NATIVE_FACTS)
        refused(JavaCompilerLaunchResolution.Refused("java.launch.compilerDiagnostics", "source errors"),
            JavaMainLaunchRefusal.INCOMPLETE_NATIVE_INVENTORY)
        refused(capture(effective = emptyList()), JavaMainLaunchRefusal.MALFORMED_NATIVE_FACTS)
        select(capture(mapOf("demo.Child" to node(methods = listOf(ctor()))), effective = emptyList())) shouldBe JavaMainLaunchSelection.Absent
        JavaNativeLaunchSelector.select(JavaMainLaunchProfile.JAVA25, capture(), snapshot, sdk, Path.of("Other.java"), "demo.Child") shouldBe
            JavaMainLaunchSelection.Refused(JavaMainLaunchRefusal.NATIVE_TYPE_UNAVAILABLE)
    }

    "selection binds snapshot SDK compiler worker and launcher authority instead of the host JVM" {
        refused(capture(context = JavaNativeLaunchCaptureContext("1".repeat(64), 25, 25, 25, "d".repeat(64))),
            JavaMainLaunchRefusal.CAPTURE_SNAPSHOT_MISMATCH)
        refused(capture(platform = "2".repeat(64)), JavaMainLaunchRefusal.SDK_MISMATCH)
        refused(capture(executableHash = "3".repeat(64)), JavaMainLaunchRefusal.AUTHORITY_UNAVAILABLE)
        refused(capture(context = JavaNativeLaunchCaptureContext(snapshot, 25, 25, 25, "missing")),
            JavaMainLaunchRefusal.AUTHORITY_UNAVAILABLE)
        refused(capture(context = JavaNativeLaunchCaptureContext(snapshot, 24, 24, 25, "d".repeat(64))),
            JavaMainLaunchRefusal.UNQUALIFIED_SOURCE)
        refused(capture(context = JavaNativeLaunchCaptureContext(snapshot, 25, 25, 26, "d".repeat(64))),
            JavaMainLaunchRefusal.UNQUALIFIED_LAUNCHER)
    }
})
