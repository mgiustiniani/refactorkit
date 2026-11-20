package org.refactorkit.c

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

/** Spec BDD for compiler command and database snapshot boundaries. */
class CCompilationModelsImmutabilitySpec : StringSpec({
    "compilation units snapshot compiler inputs and preserve data-class value behavior" {
        val file = Path.of("src/main.c")
        val directory = Path.of(".")
        val suppliedArguments = mutableListOf("clang", "-std=c11", "src/main.c")
        val suppliedDefines = mutableListOf("FEATURE=1")
        val suppliedIncludes = mutableListOf(Path.of("include"))
        val unit = CCompilationUnit(
            file = file,
            directory = directory,
            arguments = suppliedArguments,
            output = null,
            standard = "c11",
            defines = suppliedDefines,
            includeDirectories = suppliedIncludes,
            target = null,
            sysroot = null,
        )

        suppliedArguments.clear()
        suppliedDefines.clear()
        suppliedIncludes.clear()

        unit.arguments shouldBe listOf("clang", "-std=c11", "src/main.c")
        unit.defines shouldBe listOf("FEATURE=1")
        unit.includeDirectories shouldBe listOf(Path.of("include"))
        unit.copy() shouldBe unit
        unit.hashCode() shouldBe unit.copy().hashCode()
        unit.toString() shouldBe "CCompilationUnit(file=src/main.c, directory=., arguments=[clang, -std=c11, src/main.c], output=null, standard=c11, defines=[FEATURE=1], includeDirectories=[include], target=null, sysroot=null)"
        val (destructuredFile, _, arguments, _, _, defines, includeDirectories) = unit
        destructuredFile shouldBe file
        arguments shouldBe unit.arguments
        defines shouldBe unit.defines
        includeDirectories shouldBe unit.includeDirectories

        val copiedArguments = mutableListOf("clang", "-O2")
        val copiedDefines = mutableListOf("DEBUG=1")
        val copiedIncludes = mutableListOf(Path.of("private"))
        val copied = unit.copy(
            arguments = copiedArguments,
            defines = copiedDefines,
            includeDirectories = copiedIncludes,
        )
        copiedArguments.clear()
        copiedDefines.clear()
        copiedIncludes.clear()
        copied.arguments shouldBe listOf("clang", "-O2")
        copied.defines shouldBe listOf("DEBUG=1")
        copied.includeDirectories shouldBe listOf(Path.of("private"))

        shouldThrow<UnsupportedOperationException> { (unit.arguments as MutableList<String>).clear() }
        shouldThrow<UnsupportedOperationException> { (unit.defines as MutableList<String>).clear() }
        shouldThrow<UnsupportedOperationException> { (unit.includeDirectories as MutableList<Path>).clear() }
    }

    "compilation databases snapshot translation units and preserve data-class value behavior" {
        val first = unit("src/main.c")
        val second = unit("src/test.c")
        val suppliedUnits = mutableListOf(first, second)
        val database = CCompilationDatabase(Path.of("/workspace"), suppliedUnits)

        suppliedUnits.clear()

        database.units shouldBe listOf(first, second)
        database.copy() shouldBe database
        database.hashCode() shouldBe database.copy().hashCode()
        database.toString() shouldBe "CCompilationDatabase(workspaceRoot=/workspace, units=[$first, $second])"
        val (workspaceRoot, destructuredUnits) = database
        workspaceRoot shouldBe Path.of("/workspace")
        destructuredUnits shouldBe listOf(first, second)

        val copiedUnits = mutableListOf(first)
        val copied = database.copy(units = copiedUnits)
        copiedUnits.clear()
        copied.units shouldBe listOf(first)
        shouldThrow<UnsupportedOperationException> { (database.units as MutableList<CCompilationUnit>).clear() }
    }
}) {
    companion object {
        private fun unit(file: String) = CCompilationUnit(
            file = Path.of(file),
            directory = Path.of("."),
            arguments = listOf("clang", file),
            output = null,
            standard = null,
            defines = emptyList(),
            includeDirectories = emptyList(),
            target = null,
            sysroot = null,
        )
    }
}
