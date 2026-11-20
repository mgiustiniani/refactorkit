package org.refactorkit.java

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class JavaTypeRepairSpec : StringSpec({
    "the bounded primitive field repair accepts only int to long" {
        JavaTypeRepairPolicy.supportsPrimitiveFieldChange("int", "long") shouldBe true
    }

    "the bounded primitive field repair refuses narrowing and unrelated type pairs" {
        listOf(
            "long" to "int",
            "int" to "short",
            "int" to "java.lang.Long",
            "double" to "long",
        ).all { (oldType, newType) -> JavaTypeRepairPolicy.supportsPrimitiveFieldChange(oldType, newType) } shouldBe false
    }
})
