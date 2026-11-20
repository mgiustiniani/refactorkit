package org.refactorkit.java

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** REQ-JAVA-PROJECT-CONTEXT-001: API eligibility never grants source grammar authority. */
class JavaPlatformUseEligibilitySpec : StringSpec({
    fun decide(api: Int = 27, sdk: Int = api, parser: Boolean = false, exact: Boolean = true,
               use: JavaPlatformUse = JavaPlatformUse.NATIVE_COMPILER_API) =
        JavaReleasePlatformEligibilityPolicy.decide(api, parser, sdk, exact, use)
    fun code(result: JavaReleasePlatformEligibility) = (result as JavaReleasePlatformEligibility.Refused).code

    "native SDK27 APIs are independently eligible when its source27 grammar is unsupported" {
        decide() shouldBe JavaReleasePlatformEligibility.Eligible
    }
    "the same SDK27 evidence cannot qualify unsupported source27 grammar" {
        code(decide(use = JavaPlatformUse.SOURCE_RELEASE)) shouldBe "java.platform.releaseUnsupported"
    }
    "native SDK27 APIs still require exact signature evidence" {
        code(decide(exact = false)) shouldBe "java.platform.releaseSignaturesUnavailable"
    }
    "a newer API request cannot borrow an older SDK identity" {
        code(decide(sdk = 25)) shouldBe "java.platform.compilerApiUnqualified"
    }
    "historical signatures are not the native compiler API of a newer SDK" {
        code(decide(api = 17, sdk = 27, parser = true)) shouldBe "java.platform.compilerApiUnqualified"
    }
    "the verified native SDK26 API profile is independent from parser grammar support" {
        decide(api = 26) shouldBe JavaReleasePlatformEligibility.Eligible
    }
    "native SDK26 API authority still requires exact signature evidence" {
        code(decide(api = 26, exact = false)) shouldBe "java.platform.releaseSignaturesUnavailable"
    }
    "native SDK26 API authority cannot use a different SDK release" {
        code(decide(api = 26, sdk = 27)) shouldBe "java.platform.compilerApiUnqualified"
    }
    "qualifying SDK26 native APIs does not qualify unsupported source26 grammar" {
        code(decide(api = 26, use = JavaPlatformUse.SOURCE_RELEASE)) shouldBe "java.platform.releaseUnsupported"
    }
    "a future SDK profile is not admitted by its numeric metadata or parser claim" {
        code(decide(api = 28, parser = true)) shouldBe "java.platform.compilerApiUnqualified"
    }
    "the existing native SDK21 profile retains its exact platform identity" {
        decide(api = 21, parser = true) shouldBe JavaReleasePlatformEligibility.Eligible
    }
    "the existing SDK25 API profile does not depend on using source25 grammar" {
        decide(api = 25) shouldBe JavaReleasePlatformEligibility.Eligible
    }
    "release17 remains a historical source signature profile on SDK27" {
        decide(api = 17, sdk = 27, parser = true, use = JavaPlatformUse.SOURCE_RELEASE) shouldBe JavaReleasePlatformEligibility.Eligible
    }
})
