package org.refactorkit.java

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** REQ-JAVA-PROJECT-CONTEXT-001: parser/API eligibility cannot widen legacy wire grammar. */
class JavaContextSchemaSourceLevelSpec : StringSpec({
    "legacy schemas retain their independent native API26 refusal" {
        (2..5).forEach { version ->
            JavaProjectContextSemanticReleasePolicy.supportsApiProfile(26, "java-project-context-v$version") shouldBe false
        }
    }
    "versioned metadata schemas retain native API26 value admission" {
        (6..7).forEach { version ->
            JavaProjectContextSemanticReleasePolicy.supportsApiProfile(26, "java-project-context-v$version") shouldBe true
        }
    }
    "the independently qualified API27 profile remains valid in legacy schemas" {
        (2..5).forEach { version ->
            JavaProjectContextSemanticReleasePolicy.supportsApiProfile(27, "java-project-context-v$version") shouldBe true
        }
    }
    "historical API profiles retain their legacy schema values" {
        (8..25).forEach { release ->
            JavaProjectContextSemanticReleasePolicy.supportsApiProfile(release, "java-project-context-v2") shouldBe true
        }
    }
    "a future API cannot borrow the versioned metadata range" {
        JavaProjectContextSemanticReleasePolicy.supportsApiProfile(28, "java-project-context-v7") shouldBe false
    }
    "an unknown schema cannot borrow an independently qualified API profile" {
        JavaProjectContextSemanticReleasePolicy.supportsApiProfile(27, "java-project-context-v8") shouldBe false
    }
    "API27 with source17 retains legacy context eligibility" {
        JavaProjectContextSemanticReleasePolicy.requiresVersionedSourceContext(17, "java-project-context-v2") shouldBe false
    }
    "source25 remains at the legacy language boundary even with newer APIs" {
        JavaProjectContextSemanticReleasePolicy.requiresVersionedSourceContext(25, "java-project-context-v2") shouldBe false
    }
    for (version in 2..5) {
        "source26 requires an explicit versioned context beyond legacy v$version" {
            JavaProjectContextSemanticReleasePolicy.requiresVersionedSourceContext(26, "java-project-context-v$version") shouldBe true
        }
    }
    for (version in 6..7) {
        "v$version retains its independent metadata and semantic source26 policy" {
            JavaProjectContextSemanticReleasePolicy.requiresVersionedSourceContext(26, "java-project-context-v$version") shouldBe false
        }
    }
})
