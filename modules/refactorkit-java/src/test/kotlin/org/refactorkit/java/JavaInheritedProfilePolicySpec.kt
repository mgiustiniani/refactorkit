package org.refactorkit.java

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** REQ-JAVA-PROJECT-CONTEXT-001: method profiles share qualified Java25 source forms. */
class JavaInheritedProfilePolicySpec : StringSpec({
    val sdk = "a".repeat(64)
    val authority = JavaLaunchAuthority(25, sdk, sdk, "b".repeat(64))
    fun method(owner: String, array: Boolean, invocation: JavaMainInvocation) = JavaLaunchMethod(
        owner, if (array) "([Ljava/lang/String;)V" else "()V",
        if (array) JavaMainParameterShape.STRING_ARRAY else JavaMainParameterShape.NONE,
        JavaMainAccess.PUBLIC, invocation, true,
    )
    fun inventory(owner: String, methods: List<JavaLaunchMethod>, compact: Boolean = true,
                  complete: Boolean = true, source: Int = 25, target: Int = 25) = JavaNativeLaunchInventory(
        owner, source, target, if (compact) JavaLaunchSourceForm.COMPACT else JavaLaunchSourceForm.DECLARED,
        methods, JavaLaunchConstruction.Ineligible, complete, truncated = false,
    )
    fun select(facts: JavaNativeLaunchInventory) =
        JavaMainLaunchPolicy.select(JavaMainLaunchProfile.INHERITED_CLASSIC, authority, facts)

    "qualified compact instance main is absent from the inherited classic method profile" {
        select(inventory("record", listOf(method("record", false, JavaMainInvocation.INSTANCE)))) shouldBe
            JavaMainLaunchSelection.Absent
    }

    "qualified compact static noarg main is absent from the inherited classic method profile" {
        select(inventory("Or\u200Cder", listOf(method("Or\u200Cder", false, JavaMainInvocation.STATIC)))) shouldBe
            JavaMainLaunchSelection.Absent
    }

    "qualified compact public static String-array main retains its native owner" {
        val result = select(inventory("record", listOf(method("record", true, JavaMainInvocation.STATIC))))
            .shouldBeInstanceOf<JavaMainLaunchSelection.Selected>()
        result.binaryName shouldBe "record"
        result.declaringBinaryName shouldBe "record"
        result.invocation shouldBe JavaMainInvocation.STATIC
        result.constructionDescriptor shouldBe null
    }

    "compact source and target qualification remain mandatory for either method profile" {
        for ((source, target) in listOf(21 to 21, 25 to 21)) {
            val facts = inventory("record", emptyList(), source = source, target = target)
            for (profile in JavaMainLaunchProfile.entries) {
                JavaMainLaunchPolicy.select(profile, authority, facts) shouldBe
                    JavaMainLaunchSelection.Refused(JavaMainLaunchRefusal.UNQUALIFIED_SOURCE)
            }
        }
    }

    "incomplete compact native inventory cannot become an authoritative absent result" {
        select(inventory("record", emptyList(), complete = false)) shouldBe
            JavaMainLaunchSelection.Refused(JavaMainLaunchRefusal.INCOMPLETE_NATIVE_INVENTORY)
    }

    "mixed qualified source forms retain exactly their classic static String-array owners" {
        val base = method("demo.Parent", true, JavaMainInvocation.STATIC)
        val facts = listOf(
            inventory("demo.Parent", listOf(base), compact = false),
            inventory("demo.Child", listOf(base), compact = false),
            inventory("demo.AbstractMain", listOf(method("demo.AbstractMain", false, JavaMainInvocation.INSTANCE)), compact = false),
            inventory("record", listOf(method("record", false, JavaMainInvocation.INSTANCE))),
        )
        val selected = facts.mapNotNull { item -> when (val result = select(item)) {
            JavaMainLaunchSelection.Absent -> null
            is JavaMainLaunchSelection.Selected -> result.binaryName
            is JavaMainLaunchSelection.Refused -> error("Qualified source unexpectedly refused: ${result.reason}")
        } }
        selected shouldBe listOf("demo.Parent", "demo.Child")
    }
})
