package org.refactorkit.webimporter

import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.RiskLevel
import org.refactorkit.java.JavaProjectScanner
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExternalJavaClassImporterTest {

    private val importer = ExternalJavaClassImporter()

    @Test
    fun previewBasicClass() {
        val code = """
            public class Foo {
                public String name() { return "Foo"; }
            }
        """.trimIndent()
        val plan = importer.preview(ImportRequest(code = code, targetPackage = "com.example.util"))
        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertTrue(plan.summary.contains("Foo"))
        val creates = plan.workspaceEdit.edits.filterIsInstance<FileEdit.Create>()
        assertEquals(1, creates.size)
        assertTrue(creates[0].path.toString().contains("Foo.java"))
        assertTrue(creates[0].content.contains("package com.example.util;"))
    }

    @Test
    fun stripsMarkdownFences() {
        val code = """
            ```java
            public class Bar {
            }
            ```
        """.trimIndent()
        val plan = importer.preview(ImportRequest(code = code, targetPackage = "com.example"))
        assertEquals(PatchStatus.PREVIEW, plan.status)
        val creates = plan.workspaceEdit.edits.filterIsInstance<FileEdit.Create>()
        assertEquals(1, creates.size)
        assertTrue(creates[0].content.contains("package com.example;"))
    }

    @Test
    fun stripsNonJavaFenceFromSurroundingProse() {
        val code = """
            Here is the class:

            ```text
            public class FromFence {}
            ```

            Use it carefully.
        """.trimIndent()
        val plan = importer.preview(ImportRequest(code = code, targetPackage = "com.example", licensePolicy = LicensePolicy.ALLOW))
        val create = plan.workspaceEdit.edits.filterIsInstance<FileEdit.Create>().single()

        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertTrue(create.content.contains("public class FromFence"))
        assertTrue(!create.content.contains("Here is the class"))
        assertTrue(!create.content.contains("```"))
    }

    @Test
    fun refusedWhenNoPublicType() {
        val code = "class PackagePrivate {}"
        val plan = importer.preview(ImportRequest(code = code, targetPackage = "com.example"))
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun refusedWhenNoTypeAtAll() {
        val code = "// just a comment"
        val plan = importer.preview(ImportRequest(code = code, targetPackage = "com.example"))
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun detectsMitLicense() {
        val code = """
            // MIT License
            // Permission is hereby granted, free of charge
            public class Licensed {}
        """.trimIndent()
        val license = LicenseDetector.detect(code)
        assertEquals("MIT", license.detected)
        assertEquals(LicenseRisk.LOW, license.risk)
    }

    @Test
    fun provenanceWarningIncludesSourceLicenseAndHashFragment() {
        val code = """
            // MIT License
            public class Foo {}
        """.trimIndent()
        val plan = importer.preview(ImportRequest(
            code = code,
            targetPackage = "com.example",
            sourceKind = SourceKind.URL,
            sourceUrl = "https://example.invalid/Foo.java",
            licensePolicy = LicensePolicy.ALLOW,
        ))

        val provenance = plan.warnings.single { it.startsWith("Provenance:") }
        assertTrue(provenance.contains("URL"))
        assertTrue(provenance.contains("https://example.invalid/Foo.java"))
        assertTrue(provenance.contains("license=MIT"))
        assertTrue(Regex("hash=[0-9a-f]{16}").containsMatchIn(provenance))
    }

    @Test
    fun gplLicenseProducesHighRiskWarningAndPlanRisk() {
        val code = """
            // GNU General Public License
            public class Copyleft {}
        """.trimIndent()
        val plan = importer.preview(ImportRequest(code = code, targetPackage = "com.example", licensePolicy = LicensePolicy.WARN))

        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertEquals(RiskLevel.HIGH, plan.riskLevel)
        assertTrue(plan.warnings.any { it.contains("GPL") && it.contains("HIGH risk") && it.contains("copyleft") })
    }

    @Test
    fun warnForUnknownLicense() {
        val code = "public class Foo {}"
        val plan = importer.preview(ImportRequest(code = code, targetPackage = "com.example", licensePolicy = LicensePolicy.WARN))
        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertTrue(plan.warnings.any { it.contains("No license detected") || it.contains("unknown") })
    }

    @Test
    fun blockedWhenPolicyIsBlockUnknown() {
        val code = "public class Foo {}"
        val plan = importer.preview(ImportRequest(code = code, targetPackage = "com.example", licensePolicy = LicensePolicy.BLOCK_UNKNOWN))
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun rewritesPackageDeclaration() {
        val code = "package com.old.pkg;\npublic class Foo {}"
        val plan = importer.preview(ImportRequest(code = code, targetPackage = "com.example.new", licensePolicy = LicensePolicy.ALLOW))
        val create = plan.workspaceEdit.edits.filterIsInstance<FileEdit.Create>().first()
        assertTrue(create.content.contains("package com.example.new;"))
        assertTrue(!create.content.contains("package com.old.pkg;"))
    }

    @Test
    fun keepsOnePublicTypeWithPackagePrivateHelperInSingleFile() {
        val code = """
            package old.pkg;
            public class Foo { Helper helper; }
            class Helper {}
        """.trimIndent()
        val plan = importer.preview(ImportRequest(code = code, targetPackage = "com.example", licensePolicy = LicensePolicy.ALLOW))
        val creates = plan.workspaceEdit.edits.filterIsInstance<FileEdit.Create>()

        assertEquals(1, creates.size)
        assertEquals("Foo.java", creates.single().path.fileName.toString())
        assertTrue(creates.single().content.contains("package com.example;"))
        assertTrue(creates.single().content.contains("class Helper"))
    }

    @Test
    fun splitsTwoPublicTypes() {
        val code = """
            public class Foo {}
            public class Bar {}
        """.trimIndent()
        val plan = importer.preview(ImportRequest(code = code, targetPackage = "com.example", licensePolicy = LicensePolicy.ALLOW))
        val creates = plan.workspaceEdit.edits.filterIsInstance<FileEdit.Create>()
        assertEquals(2, creates.size)
        val names = creates.map { it.path.fileName.toString() }.toSet()
        assertTrue("Foo.java" in names)
        assertTrue("Bar.java" in names)
    }

    @Test
    fun splitPublicTypesPreservesImportsAndRewritesPackageInEachFile() {
        val code = """
            package old.pkg;
            import java.util.List;
            import org.acme.External;
            public class Foo { List<External> values; }
            public class Bar { List<String> names; }
        """.trimIndent()
        val plan = importer.preview(ImportRequest(code = code, targetPackage = "com.example", licensePolicy = LicensePolicy.ALLOW))
        val creates = plan.workspaceEdit.edits.filterIsInstance<FileEdit.Create>()

        assertEquals(2, creates.size)
        creates.forEach { create ->
            assertTrue(create.content.contains("package com.example;"))
            assertTrue(!create.content.contains("package old.pkg;"))
            assertTrue(create.content.contains("import java.util.List;"))
            assertTrue(create.content.contains("import org.acme.External;"))
        }
    }

    @Test
    fun splitterExtractsClass() {
        val code = """
            public class Alpha {
                private int x;
                public Alpha() {}
            }
            class Beta {}
        """.trimIndent()
        val types = JavaClassSplitter.split(code)
        assertEquals(2, types.size)
        assertTrue(types[0].isPublic && types[0].name == "Alpha")
        assertTrue(!types[1].isPublic && types[1].name == "Beta")
    }

    @Test
    fun splitterHandlesNestedBraces() {
        val code = """
            public class Outer {
                public class Inner {}
                public void m() { if (true) { int x = 0; } }
            }
        """.trimIndent()
        val types = JavaClassSplitter.split(code)
        assertEquals(1, types.size)
        assertEquals("Outer", types[0].name)
    }

    @Test
    fun refusesInvalidTargetPackage() {
        val plan = importer.preview(ImportRequest(code = "public class Foo {}", targetPackage = "com.123bad"))
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("Invalid target package"))
    }

    @Test
    fun usesTargetModuleSourceRoot() {
        val root = Files.createTempDirectory("rk-importer-test")
        Files.createDirectories(root.resolve("app/src/main/java"))
        Files.createDirectories(root.resolve("lib/src/main/java"))
        root.resolve("app/src/main/java/com/example/App.java").apply {
            Files.createDirectories(parent)
            writeText("package com.example;\npublic class App {}\n")
        }
        root.resolve("lib/src/main/java/com/example/Lib.java").apply {
            Files.createDirectories(parent)
            writeText("package com.example;\npublic class Lib {}\n")
        }
        val snapshot = JavaProjectScanner().scan(root)

        val plan = importer.preview(ImportRequest(
            code = "public class Imported {}",
            targetPackage = "com.example.util",
            targetModule = "lib",
            licensePolicy = LicensePolicy.ALLOW,
            snapshot = snapshot,
        ))

        val create = plan.workspaceEdit.edits.filterIsInstance<FileEdit.Create>().single()
        assertEquals("lib/src/main/java/com/example/util/Imported.java", create.path.toString().replace('\\', '/'))
    }

    @Test
    fun detectsConflictOnDiskEvenWhenNotInSnapshot() {
        val root = Files.createTempDirectory("rk-importer-conflict")
        val existing = root.resolve("src/main/java/com/example/Foo.java")
        Files.createDirectories(existing.parent)
        existing.writeText("not java")
        val snapshot = JavaProjectScanner().scan(root)

        val plan = importer.preview(ImportRequest(
            code = "public class Foo {}",
            targetPackage = "com.example",
            licensePolicy = LicensePolicy.ALLOW,
            snapshot = snapshot,
        ))

        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("Naming conflict"))
    }

    @Test
    fun organizesImportsAndRemovesSamePackageImports() {
        val code = """
            package old.pkg;
            import com.z.Zed;
            import java.util.List;
            import com.example.Foo;
            import java.util.List;
            import static java.util.Collections.emptyList;
            public class Foo {}
        """.trimIndent()

        val plan = importer.preview(ImportRequest(code = code, targetPackage = "com.example", licensePolicy = LicensePolicy.ALLOW))
        val content = plan.workspaceEdit.edits.filterIsInstance<FileEdit.Create>().single().content

        assertTrue(!content.contains("import com.example.Foo;"))
        assertEquals(1, Regex("import java\\.util\\.List;").findAll(content).count())
        assertTrue(content.indexOf("import java.util.List;") < content.indexOf("import com.z.Zed;"))
        assertTrue(content.indexOf("import com.z.Zed;") < content.indexOf("import static java.util.Collections.emptyList;"))
    }

    @Test
    fun warnsAboutPotentialUnresolvedExternalImports() {
        val code = """
            import org.acme.External;
            public class Foo { External external; }
        """.trimIndent()
        val plan = importer.preview(ImportRequest(code = code, targetPackage = "com.example", licensePolicy = LicensePolicy.ALLOW))
        assertTrue(plan.warnings.any { it.contains("Potential unresolved external imports") && it.contains("org.acme.External") })
    }
}
