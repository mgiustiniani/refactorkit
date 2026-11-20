package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

/** REQ-C-COMPILATION-DATABASE-001 / REQ-C-SEMANTIC-RENAME-001: pre-execution captured-include boundary. */
class CCapturedSourceDirectivePolicySpec : StringSpec({
    fun check(source: String) = CHeaderFieldSourcePolicy.check(mapOf(Path.of("src/main.c") to source))
    val refused = CHeaderFieldSourceAdmission.Refused(CHeaderFieldRefusal.UNSUPPORTED_SOURCE)
    "literal quoted includes and pragma once retain their existing admission" {
        check("#include \"api.h\"\n#pragma once\nint value;\n") shouldBe CHeaderFieldSourceAdmission.Admitted
    }
    "a comment-prefixed include cannot bypass the literal include ownership preflight" {
        check("/* prefix */ #include \"/tmp/unowned.h\"\nint value;\n") shouldBe refused
    }
    "digraph preprocessing includes are unqualified before compiler execution" {
        check("%:include \"/tmp/unowned.h\"\nint value;\n") shouldBe refused
    }
    "a multiline comment ending before a directive does not hide its preprocessing role" {
        check("/* prefix\n */ #include \"/tmp/unowned.h\"\nint value;\n") shouldBe refused
    }
    "hash characters in ordinary comments and strings remain ordinary source content" {
        check("/* #include bad */\nconst char *text = \"#include bad\";\n") shouldBe CHeaderFieldSourceAdmission.Admitted
    }
    "ordinary macro definitions remain refused" {
        check("#define VALUE 1\nint value;\n") shouldBe refused
    }
    "a comment-prefixed pragma is not silently admitted as a qualified directive" {
        check("/* prefix */ #pragma once\nint value;\n") shouldBe refused
    }
    "per-file source limits are checked before tokenization" {
        check("a".repeat(1_048_577)) shouldBe CHeaderFieldSourceAdmission.Refused(CHeaderFieldRefusal.INPUT_LIMIT)
    }
    "carriage-return line endings cannot hide a comment-prefixed directive" {
        check("int value;\r/* prefix */ #include \"/tmp/unowned.h\"\r") shouldBe refused
    }
    "an unterminated string cannot invalidate directive line ownership" {
        check("const char *text = \"broken\n/* prefix */ #include \"/tmp/unowned.h\"\n") shouldBe refused
    }
    "standard C keyword and predefined identifier spellings stay admitted" {
        check("_Atomic(int) value;\nint helper(void) { return _Alignof(int) + __func__[0]; }\n") shouldBe CHeaderFieldSourceAdmission.Admitted
    }
    "extension spellings in comments and strings do not become language syntax" {
        check("/* __typeof__(int) */\nconst char *text = \"__attribute__ __typeof__\";\n") shouldBe CHeaderFieldSourceAdmission.Admitted
    }
})
