package org.refactorkit.java

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class JavaReleasePlatformEligibilitySpec : StringSpec({
    "requires parser support, a same-or-newer configured JDK, and exact ct.sym evidence" {
        JavaReleasePlatformEligibilityPolicy.decide(
            release = 26,
            parserSupportsRelease = true,
            platformRelease = 27,
            containsExactSignature = true,
        ) shouldBe JavaReleasePlatformEligibility.Eligible
    }

    "refuses a platform when only its numeric declaration is supported" {
        val refused = JavaReleasePlatformEligibilityPolicy.decide(
            release = 26,
            parserSupportsRelease = true,
            platformRelease = 21,
            containsExactSignature = true,
        ) as JavaReleasePlatformEligibility.Refused

        refused.code shouldBe "java.platform.releaseSignaturesUnavailable"
    }

    "refuses an exact platform signature when the installed parser cannot model that release" {
        val refused = JavaReleasePlatformEligibilityPolicy.decide(
            release = 27,
            parserSupportsRelease = false,
            platformRelease = 27,
            containsExactSignature = true,
        ) as JavaReleasePlatformEligibility.Refused

        refused.code shouldBe "java.platform.releaseUnsupported"
    }

    "refuses when the configured JDK archive lacks the exact release signature" {
        val refused = JavaReleasePlatformEligibilityPolicy.decide(
            release = 26,
            parserSupportsRelease = true,
            platformRelease = 27,
            containsExactSignature = false,
        ) as JavaReleasePlatformEligibility.Refused

        refused.code shouldBe "java.platform.releaseSignaturesUnavailable"
    }
})
