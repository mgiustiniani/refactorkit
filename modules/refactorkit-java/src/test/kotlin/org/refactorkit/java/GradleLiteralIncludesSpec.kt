package org.refactorkit.java

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path

class GradleLiteralIncludesSpec : StringSpec({
    for ((file, kotlinDsl) in listOf("settings.gradle.kts" to true, "settings.gradle" to false)) {
        val forms = linkedMapOf(
            "inline literal list" to if (kotlinDsl) "include(\":lib\", \"«target»\", \":tools\")\n" else "include ':lib', '«target»', ':tools'\n",
            "separate include statements" to "include(\":lib\")\ninclude(\"«target»\")\ninclude(\":tools\")\n",
            "multiline parentheses" to "include(\n    \":lib\",\n    \"«target»\",\n    \":tools\"\n)\n",
            "comments and CRLF" to "// include(\":app\") is an example\r\ninclude(\":lib\", /* keep */ \"«target»\", \":tools\") // tail\r\n",
            "unprefixed literal identities" to "include(\"lib\", \"«target»\", \"tools\")\n",
            "semicolon statement boundary" to "rootProject.name = \"fixture\"; include(\":lib\", \"«target»\", \":tools\")\n",
        )
        for ((profile, template) in forms) {
            "$file $profile selects exactly one project literal and preserves peers and physical directory" {
                val oldLiteral = if (profile == "unprefixed literal identities") "app" else ":app"
                val content = template.replace("«target»", oldLiteral)
                val proposal = GradleSettingsModuleEdit.proposeRename(file, content, ":app", ":renamed")
                    .shouldBeInstanceOf<GradleSettingsModuleRenameResult.Supported>().proposal
                proposal.physicalProjectDirectory shouldBe Path.of("app")
                content.substring(proposal.pathLiteralStart, proposal.pathLiteralEnd) shouldBe oldLiteral
                proposal.insertionOffset shouldBe content.length
                val newline = if ("\r\n" in content) "\r\n" else "\n"
                val mapping = if (kotlinDsl) "project(\":renamed\").projectDir = file(\"app\")$newline"
                    else "project(':renamed').projectDir = file('app')$newline"
                proposal.insertedText shouldBe mapping
                val edited = content.substring(0, proposal.pathLiteralStart) + ":renamed" +
                    content.substring(proposal.pathLiteralEnd) + proposal.insertedText
                edited shouldBe template.replace("«target»", ":renamed") + mapping
            }
        }
        val refusals = linkedMapOf(
            "duplicate canonical project identity" to "include(\":app\", \"app\")\n",
            "destination already included" to "include(\":app\", \":renamed\")\n",
            "dynamic sibling" to "include(\":app\", sibling)\n",
            "interpolated sibling" to "include(\":app\", \"\$sibling\")\n",
            "same-line conditional" to "if (enabled) include(\":app\", \":lib\")\n",
            "multiline conditional" to "if (enabled)\n    include(\":app\", \":lib\")\n",
            "nested conditional" to "if (enabled) {\n    include(\":app\", \":lib\")\n}\n",
            "qualified call" to "settings.include(\":app\", \":lib\")\n",
            "missing target" to "include(\":library\", \":tools\")\n",
            "spread arguments" to "include(*modules)\n",
        )
        for ((profile, content) in refusals) {
            "$file refuses $profile without a literal edit proposal" {
                GradleSettingsModuleEdit.proposeRename(file, content, ":app", ":renamed") shouldBe
                    GradleSettingsModuleRenameResult.Refused("java.renameGradleModule.settingsIncludeUnsupported")
            }
        }
    }
})
