package org.refactorkit.java

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** REQ-JAVA-PROJECT-CONTEXT-001: policy over complete native facts, not native extraction proof. */
class JavaMainLaunchPolicySpec : StringSpec({
    val sdk = "a".repeat(64)
    fun authority(release: Int = 25, launcherSdk: String = sdk) = JavaLaunchAuthority(release, sdk, launcherSdk, "b".repeat(64))
    fun method(owner: String, shape: JavaMainParameterShape, invocation: JavaMainInvocation = JavaMainInvocation.STATIC,
               access: JavaMainAccess = JavaMainAccess.PUBLIC, returnsVoid: Boolean = true) = JavaLaunchMethod(
        owner, (if (shape == JavaMainParameterShape.STRING_ARRAY) "([Ljava/lang/String;)" else "()") +
            if (returnsVoid) "V" else "I",
        shape, access, invocation, returnsVoid)
    fun inventory(methods: List<JavaLaunchMethod>, construction: JavaLaunchConstruction = JavaLaunchConstruction.Unavailable,
                  complete: Boolean = true, truncated: Boolean = false, compact: Boolean = false,
                  source: Int = 25, target: Int = 25, binaryName: String = "demo.Child") = JavaNativeLaunchInventory(
        binaryName, source, target, if (compact) JavaLaunchSourceForm.COMPACT else JavaLaunchSourceForm.DECLARED,
        methods, construction, complete, truncated)
    fun selected(facts: JavaNativeLaunchInventory, profile: JavaMainLaunchProfile = JavaMainLaunchProfile.JAVA25) =
        JavaMainLaunchPolicy.select(profile, authority(), facts).shouldBeInstanceOf<JavaMainLaunchSelection.Selected>()

    "classic inherited selection retains different launch and declaring owners on the attested launcher" {
        val facts = inventory(listOf(method("demo.Base", JavaMainParameterShape.STRING_ARRAY)), source = 17, target = 17)
        val result = JavaMainLaunchPolicy.select(JavaMainLaunchProfile.INHERITED_CLASSIC, authority(21), facts)
            .shouldBeInstanceOf<JavaMainLaunchSelection.Selected>()
        result.binaryName shouldBe "demo.Child"
        result.declaringBinaryName shouldBe "demo.Base"
        result.inherited shouldBe true
        result.invocation shouldBe JavaMainInvocation.STATIC
        result.descriptor shouldBe "([Ljava/lang/String;)V"
        result.constructionDescriptor shouldBe null
    }

    "Java25 String-array instance main takes precedence over static no-arg without changing its owner" {
        val result = selected(inventory(listOf(
            method("demo.Child", JavaMainParameterShape.NONE),
            method("demo.Child", JavaMainParameterShape.STRING_ARRAY, JavaMainInvocation.INSTANCE, JavaMainAccess.PACKAGE),
        ), JavaLaunchConstruction.Eligible("demo.Child", "()V")))
        result.invocation shouldBe JavaMainInvocation.INSTANCE
        result.descriptor shouldBe "([Ljava/lang/String;)V"
        result.inherited shouldBe false
        result.constructionDescriptor shouldBe "()V"
    }

    "Java25 static no-arg selection needs no constructor or application instance" {
        val result = selected(inventory(listOf(method("demo.Child", JavaMainParameterShape.NONE,
            access = JavaMainAccess.PROTECTED))))
        result.invocation shouldBe JavaMainInvocation.STATIC
        result.descriptor shouldBe "()V"
        result.constructionDescriptor shouldBe null
    }

    "compact Java25 instance selection retains native owner and proven construction" {
        val result = selected(inventory(listOf(method("Compact", JavaMainParameterShape.NONE,
            JavaMainInvocation.INSTANCE, JavaMainAccess.PACKAGE)),
            JavaLaunchConstruction.Eligible("Compact", "()V"), compact = true, binaryName = "Compact"))
        result.binaryName shouldBe "Compact"
        result.invocation shouldBe JavaMainInvocation.INSTANCE
        result.descriptor shouldBe "()V"
    }

    "compact native binary owners retain contextual keywords and Unicode without TypeIdentifier filtering" {
        for (name in listOf("var", "yield", "record", "sealed", "permits", "IO", "java", "Café",
            "Or\u200Cder", "cla\u200Css", "va\u200Cr", "_\u200C")) {
            val result = selected(inventory(listOf(method(name, JavaMainParameterShape.NONE,
                JavaMainInvocation.INSTANCE, JavaMainAccess.PACKAGE)),
                JavaLaunchConstruction.Eligible(name, "()V"), compact = true, binaryName = name))
            result.binaryName shouldBe name
            result.declaringBinaryName shouldBe name
        }
    }

    "instance creation that is ineligible or unproved is refused rather than reported as absent" {
        val methods = listOf(method("demo.Child", JavaMainParameterShape.NONE, JavaMainInvocation.INSTANCE))
        JavaMainLaunchPolicy.select(JavaMainLaunchProfile.JAVA25, authority(), inventory(methods, JavaLaunchConstruction.Ineligible)) shouldBe
            JavaMainLaunchSelection.Refused(JavaMainLaunchRefusal.INSTANCE_CREATION_INELIGIBLE)
        JavaMainLaunchPolicy.select(JavaMainLaunchProfile.JAVA25, authority(), inventory(methods)) shouldBe
            JavaMainLaunchSelection.Refused(JavaMainLaunchRefusal.INSTANCE_CREATION_UNAVAILABLE)
    }

    "private and nonvoid methods cannot become a launch candidate in a complete inventory" {
        JavaMainLaunchPolicy.select(JavaMainLaunchProfile.JAVA25, authority(), inventory(listOf(
            method("demo.Child", JavaMainParameterShape.STRING_ARRAY, access = JavaMainAccess.PRIVATE),
            method("demo.Child", JavaMainParameterShape.NONE, returnsVoid = false),
        ))) shouldBe JavaMainLaunchSelection.Absent
    }

    "two effective members of the selected shape refuse without choosing by list order" {
        val facts = inventory(listOf(method("demo.Left", JavaMainParameterShape.STRING_ARRAY),
            method("demo.Right", JavaMainParameterShape.STRING_ARRAY)))
        JavaMainLaunchPolicy.select(JavaMainLaunchProfile.JAVA25, authority(), facts) shouldBe
            JavaMainLaunchSelection.Refused(JavaMainLaunchRefusal.AMBIGUOUS_MAIN)
    }

    "erased descriptors do not manufacture declared String-array shape or conceal inconsistent native facts" {
        val erased = JavaLaunchMethod("demo.Child", "([Ljava/lang/String;)V", JavaMainParameterShape.OTHER,
            JavaMainAccess.PUBLIC, JavaMainInvocation.STATIC, true)
        JavaMainLaunchPolicy.select(JavaMainLaunchProfile.JAVA25, authority(), inventory(listOf(erased))) shouldBe
            JavaMainLaunchSelection.Refused(JavaMainLaunchRefusal.UNQUALIFIED_DECLARATION)
        val inconsistent = erased.copy(parameterShape = JavaMainParameterShape.NONE)
        JavaMainLaunchPolicy.select(JavaMainLaunchProfile.JAVA25, authority(), inventory(listOf(inconsistent))) shouldBe
            JavaMainLaunchSelection.Refused(JavaMainLaunchRefusal.MALFORMED_NATIVE_FACTS)
    }

    "incomplete authority cannot become a complete zero-main result and detached members stay immutable" {
        JavaMainLaunchPolicy.select(JavaMainLaunchProfile.JAVA25, authority(26), inventory(emptyList())) shouldBe
            JavaMainLaunchSelection.Refused(JavaMainLaunchRefusal.UNQUALIFIED_LAUNCHER)
        JavaMainLaunchPolicy.select(JavaMainLaunchProfile.JAVA25, authority(launcherSdk = "c".repeat(64)), inventory(emptyList())) shouldBe
            JavaMainLaunchSelection.Refused(JavaMainLaunchRefusal.SDK_MISMATCH)
        JavaMainLaunchPolicy.select(JavaMainLaunchProfile.JAVA25, authority(), inventory(emptyList(), complete = false)) shouldBe
            JavaMainLaunchSelection.Refused(JavaMainLaunchRefusal.INCOMPLETE_NATIVE_INVENTORY)
        JavaMainLaunchPolicy.select(JavaMainLaunchProfile.JAVA25, authority(), inventory(emptyList(), truncated = true)) shouldBe
            JavaMainLaunchSelection.Refused(JavaMainLaunchRefusal.INCOMPLETE_NATIVE_INVENTORY)
        val input = mutableListOf(method("demo.Child", JavaMainParameterShape.STRING_ARRAY))
        val facts = inventory(input); input.clear()
        selected(facts).descriptor shouldBe "([Ljava/lang/String;)V"
        runCatching { (facts.methods as MutableList<*>).clear() }.isFailure shouldBe true
    }
})
