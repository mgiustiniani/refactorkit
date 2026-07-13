package org.refactorkit.typescript

import org.refactorkit.core.Diagnostic
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TypeScriptProjectModelTest {
    @Test
    fun buildsEffectiveJsoncProjectsReferencesAliasesAndPackageType() {
        val root = workspace(
            "package.json" to """{"name":"workspace","type":"module","exports":{".":"./dist/index.js"},"types":"./dist/index.d.ts"}""",
            "configs/base.json" to """
                {
                  // inherited compiler policy
                  "compilerOptions": {
                    "strict": true,
                    "baseUrl": "..",
                    "rootDir": "../src",
                    "paths": { "@app/*": ["src/*",], },
                    "moduleResolution": "bundler",
                  },
                  "include": ["../src/**/*.ts",],
                }
            """.trimIndent(),
            "apps/web/tsconfig.json" to """
                {
                  "extends": "../../configs/base.json",
                  "compilerOptions": {"outDir":"../../dist/web", "jsx":"react-jsx", "composite":true},
                  "include": ["src/**/*.ts", "src/**/*.tsx"],
                  "references": [{"path":"../../packages/lib"}],
                }
            """.trimIndent(),
            "packages/lib/tsconfig.json" to """
                {"compilerOptions":{"declaration":true,"outDir":"../../dist/lib"},"files":["src/index.ts"]}
            """.trimIndent(),
        )

        val model = TypeScriptProjectModelBuilder().build(root)

        assertEquals(TypeScriptProjectModelStatus.AVAILABLE, model.status)
        assertEquals(listOf("apps/web/tsconfig.json", "packages/lib/tsconfig.json"), model.projects.map { slash(it.configPath) })
        val web = model.projects.first()
        assertEquals(listOf("configs/base.json"), web.extendsConfigs.map(::slash))
        assertEquals(listOf("packages/lib/tsconfig.json"), web.references.map(::slash))
        assertEquals("", slash(web.compilerOptions.baseUrl!!))
        assertEquals("src", slash(web.compilerOptions.rootDirectory!!))
        assertEquals("dist/web", slash(web.compilerOptions.outputDirectory!!))
        assertEquals(mapOf("@app/*" to listOf("src/*")), web.compilerOptions.paths)
        assertEquals("bundler", web.compilerOptions.moduleResolution)
        assertEquals(true, web.compilerOptions.composite)
        assertEquals(listOf("src/**/*.ts", "src/**/*.tsx"), web.include.map { it.pattern })
        assertEquals(listOf("apps/web", "apps/web"), web.include.map { slash(it.baseDirectory) })
        assertEquals(JavaScriptPackageType.MODULE, web.packageType)
        assertEquals("package.json", slash(web.packageManifest!!))
        assertTrue(web.packageExportsDeclared)
        assertTrue(web.packageTypesDeclared)
        assertEquals(4, model.evidence.size)
        assertTrue(model.evidence.all { it.sha256.length == 64 && it.size > 0 })
        assertEquals(64, model.projectionHash.length)
    }

    @Test
    fun includesCustomReferencedConfigButNotExtendsOnlyBaseAsProject() {
        val root = workspace(
            "base.json" to """{"compilerOptions":{"allowJs":true}}""",
            "tsconfig.json" to """{"extends":"./base.json","references":[{"path":"./configs/library.json"}]}""",
            "configs/library.json" to """{"compilerOptions":{"composite":true}}""",
        )
        val model = TypeScriptProjectModelBuilder().build(root)
        assertEquals(TypeScriptProjectModelStatus.AVAILABLE, model.status)
        assertEquals(listOf("configs/library.json", "tsconfig.json"), model.projects.map { slash(it.configPath) })
        assertEquals(true, model.projects.single { slash(it.configPath) == "tsconfig.json" }.compilerOptions.allowJs)
    }

    @Test
    fun projectionHashChangesWithConfigOrPackageEvidence() {
        val root = workspace(
            "package.json" to """{"type":"module"}""",
            "tsconfig.json" to """{"compilerOptions":{"checkJs":false}}""",
        )
        val builder = TypeScriptProjectModelBuilder()
        val first = builder.build(root)
        root.resolve("tsconfig.json").writeText("""{"compilerOptions":{"checkJs":true}}""")
        val second = builder.build(root)
        assertNotEquals(first.projectionHash, second.projectionHash)
        root.resolve("package.json").writeText("""{"type":"commonjs"}""")
        val third = builder.build(root)
        assertNotEquals(second.projectionHash, third.projectionHash)
        assertEquals(JavaScriptPackageType.COMMONJS, third.projects.single().packageType)
    }

    @Test
    fun jsconfigAndCommentsInsideStringsAreHandledWithoutExecution() {
        val root = workspace(
            "jsconfig.json" to """
                {
                  "compilerOptions": {"allowJs":true, "baseUrl":"https://not-a-path.example"},
                  "include": ["src/http://literal.js"],
                  "note": "// this remains a string /* too */",
                }
            """.trimIndent(),
        )
        val model = TypeScriptProjectModelBuilder().build(root)
        assertCodes(model, "typescript.compilerPathInvalid")

        root.resolve("jsconfig.json").writeText("""
            {"compilerOptions":{"allowJs":true},"include":["src/**/*.js"],"note":"// string /* remains */",}
        """.trimIndent())
        val valid = TypeScriptProjectModelBuilder().build(root)
        assertEquals(TypeScriptProjectModelStatus.AVAILABLE, valid.status)
        assertEquals(TypeScriptConfigKind.JAVASCRIPT, valid.projects.single().kind)
        assertEquals(true, valid.projects.single().compilerOptions.allowJs)
    }

    @Test
    fun refusesPackageExtendsMissingReferencesAndEscapingPaths() {
        assertCodes(TypeScriptProjectModelBuilder().build(workspace(
            "tsconfig.json" to """{"extends":"@tsconfig/node22/tsconfig.json"}""",
        )), "typescript.extendsUnsupported")

        assertCodes(TypeScriptProjectModelBuilder().build(workspace(
            "tsconfig.json" to """{"references":[{"path":"./missing"}]}""",
        )), "typescript.configReferenceMissing")

        assertCodes(TypeScriptProjectModelBuilder().build(workspace(
            "tsconfig.json" to """{"compilerOptions":{"outDir":"../outside"}}""",
        )), "typescript.compilerPathInvalid")

        assertCodes(TypeScriptProjectModelBuilder().build(workspace(
            "tsconfig.json" to """{"files":["../outside.ts"]}""",
        )), "typescript.filePathInvalid")
    }

    @Test
    fun refusesExtendsAndProjectReferenceCycles() {
        assertCodes(TypeScriptProjectModelBuilder().build(workspace(
            "tsconfig.json" to """{"extends":"./base.json"}""",
            "base.json" to """{"extends":"./tsconfig.json"}""",
        )), "typescript.extendsCycle")

        assertCodes(TypeScriptProjectModelBuilder().build(workspace(
            "a/tsconfig.json" to """{"references":[{"path":"../b"}]}""",
            "b/tsconfig.json" to """{"references":[{"path":"../a"}]}""",
        )), "typescript.referenceCycle")
    }

    @Test
    fun refusesMalformedShapesLimitsAndPackageMetadata() {
        assertCodes(TypeScriptProjectModelBuilder().build(workspace(
            "tsconfig.json" to """{"compilerOptions":[],}""",
        )), "typescript.configShapeInvalid")

        assertCodes(TypeScriptProjectModelBuilder().build(workspace(
            "tsconfig.json" to """{/* unterminated""",
        )), "typescript.configSyntax")

        assertCodes(TypeScriptProjectModelBuilder(TypeScriptProjectModelPolicy(maxConfigs = 1)).build(workspace(
            "a/tsconfig.json" to "{}", "b/tsconfig.json" to "{}",
        )), "typescript.configLimit")

        assertCodes(TypeScriptProjectModelBuilder().build(workspace(
            "package.json" to """{"type":"invalid"}""", "tsconfig.json" to "{}",
        )), "typescript.packageTypeInvalid")
    }

    @Test
    fun releaseSamplesCoverReferencesCheckedDynamicAndMixedProjects() {
        val references = TypeScriptProjectModelBuilder().build(releaseSample("typescript-project-references"))
        assertEquals(TypeScriptProjectModelStatus.AVAILABLE, references.status, references.diagnostics.toString())
        assertEquals(3, references.projects.size)
        assertTrue(references.projects.any { it.references.isNotEmpty() })

        val checked = TypeScriptProjectModelBuilder().build(releaseSample("javascript-checked"))
        assertEquals(TypeScriptProjectModelStatus.AVAILABLE, checked.status, checked.diagnostics.toString())
        assertTrue(checked.projects.single().compilerOptions.checkJs == true)

        val dynamic = TypeScriptProjectModelBuilder().build(releaseSample("javascript-dynamic"))
        assertEquals(TypeScriptProjectModelStatus.AVAILABLE, dynamic.status, dynamic.diagnostics.toString())
        assertTrue(dynamic.projects.single().compilerOptions.checkJs == false)

        val mixed = TypeScriptProjectModelBuilder().build(releaseSample("typescript-javascript-mixed"))
        assertEquals(TypeScriptProjectModelStatus.AVAILABLE, mixed.status, mixed.diagnostics.toString())
        assertTrue(mixed.projects.single().compilerOptions.allowJs == true)
    }

    @Test
    fun ignoresNodeModulesConfigsAndRejectsSymlinkTraversal() {
        val root = workspace(
            "tsconfig.json" to "{}",
            "node_modules/dependency/tsconfig.json" to """{"compilerOptions":{"checkJs":true}}""",
        )
        val model = TypeScriptProjectModelBuilder().build(root)
        assertEquals(1, model.projects.size)

        val outside = Files.createTempDirectory("refactorkit-ts-outside")
        outside.resolve("base.json").writeText("{}")
        val link = root.resolve("linked")
        runCatching { Files.createSymbolicLink(link, outside) }.onSuccess {
            root.resolve("tsconfig.json").writeText("""{"extends":"./linked/base.json"}""")
            assertCodes(TypeScriptProjectModelBuilder().build(root), "typescript.extendsUnsupported")
        }
    }

    private fun releaseSample(name: String): Path = Path.of("../..", "samples", name).toAbsolutePath().normalize()

    private fun assertCodes(model: TypeScriptProjectModel, vararg expected: String) {
        assertEquals(TypeScriptProjectModelStatus.REFUSED, model.status)
        val codes = model.diagnostics.mapNotNull(Diagnostic::code)
        expected.forEach { assertTrue(it in codes, "missing $it in $codes") }
    }

    private fun workspace(vararg files: Pair<String, String>): Path {
        val root = Files.createTempDirectory("refactorkit-ts-model")
        files.forEach { (relative, content) ->
            val path = root.resolve(relative)
            path.parent.createDirectories()
            path.writeText(content)
        }
        return root
    }

    private fun slash(path: Path): String = path.normalize().toString().replace('\\', '/')
}
