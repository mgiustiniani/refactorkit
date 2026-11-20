package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class CTargetNameCollisionPolicySpec : StringSpec({
    "a spelling used only as a C struct tag does not conflict with an ordinary identifier" {
        CTargetNameCollisionPolicy.hasPotentialConflict("c", "struct compute;", "compute") shouldBe false
    }

    "comments and literals do not create target-name collisions" {
        CTargetNameCollisionPolicy.hasPotentialConflict(
            "c",
            "/* compute */ const char *label = \"compute\";",
            "compute",
        ) shouldBe false
    }

    "an ordinary occurrence alongside a tag remains a conflict" {
        CTargetNameCollisionPolicy.hasPotentialConflict(
            "c",
            "struct compute; int compute(void);",
            "compute",
        ) shouldBe true
    }

    "tag-shaped tokens in preprocessor directives remain unclassified" {
        CTargetNameCollisionPolicy.hasPotentialConflict("c", "#define TAG_TYPE struct compute\\\n\nTAG_TYPE value;", "compute") shouldBe true
    }

    "C++ class names are not exempted by the C tag namespace rule" {
        CTargetNameCollisionPolicy.hasPotentialConflict("cpp", "struct compute {};", "compute") shouldBe true
    }
})
