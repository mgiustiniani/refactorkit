package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class CNativeTargetPolicySpec : StringSpec({
    val i386 = "i386-unknown-linux-gnu"
    val x64 = "x86_64-unknown-linux-gnu"
    fun unit(flags: List<String> = emptyList(), target: String? = null, fileName: String = "main.c") = CCompilationUnit(
        file = Path.of("/unread/workspace").resolve(fileName), directory = Path.of("/unread/workspace"),
        arguments = listOf("/unread/clang", "-std=c17") + flags + listOf("-c", fileName),
        output = null, standard = "c17", defines = emptyList(), includeDirectories = emptyList(),
        target = target, sysroot = null,
    )
    fun refused(reason: CNativeTargetRefusal) = CNativeTargetAdmission.Refused(reason)

    "implicit target contexts remain implicit without filesystem access" {
        CNativeTargetPolicy.admit(listOf(unit(), unit())) shouldBe CNativeTargetAdmission.ImplicitDefault
        CNativeTargetPolicy.admit(emptyList()) shouldBe CNativeTargetAdmission.ImplicitDefault
    }
    "separate short and assigned long i386 forms retain one coherent target" {
        val contexts = listOf(unit(listOf("-target", i386), i386), unit(listOf("--target=$i386"), i386))
        CNativeTargetPolicy.admit(contexts) shouldBe CNativeTargetAdmission.Explicit(CNativeTarget.I386_LINUX)
    }
    "explicit qualified x86_64 context remains distinct from the implicit default" {
        CNativeTargetPolicy.admit(listOf(unit(listOf("--target=$x64"), x64))) shouldBe
            CNativeTargetAdmission.Explicit(CNativeTarget.X86_64_LINUX)
    }
    "option-shaped include and output operands cannot manufacture targets or invalid target flags" {
        CNativeTargetPolicy.admit(listOf(unit(listOf("-I", "--target=ghost", "-o", "-target=ghost")))) shouldBe
            CNativeTargetAdmission.ImplicitDefault
        CNativeTargetPolicy.admit(listOf(unit(listOf("-I", "--target", "-target", i386), i386))) shouldBe
            CNativeTargetAdmission.Explicit(CNativeTarget.I386_LINUX)
    }
    "multiple target selectors refuse even when their spelling or value repeats" {
        for (second in listOf(i386, x64)) {
            CNativeTargetPolicy.admit(listOf(unit(listOf("-target", i386, "--target=$second"), i386))) shouldBe
                refused(CNativeTargetRefusal.MULTIPLE_TARGET_FLAGS)
        }
    }
    "unqualified target triples cannot inherit support from the compiler or host" {
        for (target in listOf("ghost", "aarch64-unknown-linux-gnu", "x86_64-pc-windows-msvc", "")) {
            CNativeTargetPolicy.admit(listOf(unit(listOf("--target=$target"), target.takeIf(String::isNotEmpty)))) shouldBe
                refused(CNativeTargetRefusal.UNQUALIFIED_TARGET)
        }
    }
    "compiler-invalid target spellings and missing operands refuse instead of being repaired" {
        for (flags in listOf(listOf("--target", i386), listOf("-target=$i386"))) {
            CNativeTargetPolicy.admit(listOf(unit(flags, i386))) shouldBe refused(CNativeTargetRefusal.INVALID_OPTIONS)
        }
        val missing = unit().copy(arguments = listOf("/unread/clang", "-std=c17", "-target"))
        CNativeTargetPolicy.admit(listOf(missing)) shouldBe refused(CNativeTargetRefusal.INVALID_OPTIONS)
    }
    "conflicting target records for one source remain inconsistent" {
        val selected = unit(listOf("--target=$i386"), i386)
        CNativeTargetPolicy.admit(listOf(selected, unit(listOf("--target=$x64"), x64))) shouldBe
            refused(CNativeTargetRefusal.INCONSISTENT_CONTEXTS)
        CNativeTargetPolicy.admit(listOf(selected, unit())) shouldBe refused(CNativeTargetRefusal.INCONSISTENT_CONTEXTS)
    }
    "distinct translation units retain qualified explicit targets independently" {
        CNativeTargetPolicy.admit(listOf(unit(listOf("--target=$i386"), i386),
            unit(listOf("--target=$x64"), x64, "other.c"))) shouldBe CNativeTargetAdmission.PerTranslationUnit
    }
    "an implicit target stays independent from another source explicit target" {
        CNativeTargetPolicy.admit(listOf(unit(listOf("--target=$i386"), i386), unit(fileName = "other.c"))) shouldBe
            CNativeTargetAdmission.PerTranslationUnit
    }
    "path aliases of the same source cannot manufacture independent target ownership" {
        CNativeTargetPolicy.admit(listOf(unit(listOf("--target=$i386"), i386),
            unit(listOf("--target=$x64"), x64, "nested/../main.c"))) shouldBe refused(CNativeTargetRefusal.INCONSISTENT_CONTEXTS)
    }
    "heterogeneous contexts never admit an unqualified target through a qualified peer" {
        CNativeTargetPolicy.admit(listOf(unit(listOf("--target=$i386"), i386),
            unit(listOf("--target=ghost"), "ghost", "other.c"))) shouldBe refused(CNativeTargetRefusal.UNQUALIFIED_TARGET)
    }
    "parsed metadata cannot disagree with its original target argument" {
        CNativeTargetPolicy.admit(listOf(unit(listOf("--target=$i386"), x64))) shouldBe
            refused(CNativeTargetRefusal.METADATA_MISMATCH)
        CNativeTargetPolicy.admit(listOf(unit(emptyList(), i386))) shouldBe
            refused(CNativeTargetRefusal.METADATA_MISMATCH)
    }
    "target context bounds are checked before traversing argument collections" {
        val maximum = CCompilationDatabasePolicy.DEFAULT_MAX_UNITS
        CNativeTargetPolicy.admit(List(maximum) { unit() }) shouldBe CNativeTargetAdmission.ImplicitDefault
        CNativeTargetPolicy.admit(List(maximum + 1) { unit() }) shouldBe refused(CNativeTargetRefusal.CONTEXT_LIMIT)
    }
})
