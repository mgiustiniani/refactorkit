package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path

/** C03/C08/C15: literal target flag origins are consumed; compilation alone is insufficient. */
class CMakeIncludeRootTransitionSpec : StringSpec({
    val api = Path.of("component/api.c")
    val main = Path.of("src/main.c")
    val nextApi = Path.of("library/api.c")
    val include = Path.of("component/include")
    val nextInclude = Path.of("library/include")
    val extra = Path.of("support/include")
    val units = setOf(api, main)
    val apiOwner = CMakeLiteralTargetOwner("api", "add_library", "STATIC")
    val mainOwner = CMakeLiteralTargetOwner("app", "add_executable", null)
    val owners = mapOf(api to listOf(apiOwner), main to listOf(mainOwner))
    val roots = listOf(CMakeLiteralIncludeRoot("api", include), CMakeLiteralIncludeRoot("app", include))
    val stagedRoots = roots.map { it.copy(path = nextInclude) }
    fun analyze(
        sourceSet: Set<Path> = units,
        flags: Map<Path, List<Path>> = mapOf(api to listOf(include), main to listOf(include)),
        sourceOwners: Map<Path, List<CMakeLiteralTargetOwner>> = owners,
        before: List<CMakeLiteralIncludeRoot> = roots,
        after: List<CMakeLiteralIncludeRoot> = stagedRoots,
        moves: Map<Path, Path> = mapOf(api to nextApi),
        rootMoves: Map<Path, Path> = mapOf(include to nextInclude),
    ) = CMakeIncludeRootTransition.analyze(sourceSet, flags, sourceOwners, before, after, moves, rootMoves)

    "one target root binds moved and stationary consumers to exact old and staged paths" {
        val f = analyze().shouldBeInstanceOf<CMakeIncludeRootReadout.Found>().facts
        f.before shouldBe mapOf(api to listOf(include), main to listOf(include))
        f.after shouldBe mapOf(nextApi to listOf(nextInclude), main to listOf(nextInclude))
    }
    "unmoved roots retain their position in the compiler search order" {
        val all = roots + CMakeLiteralIncludeRoot("api", extra)
        val staged = stagedRoots + CMakeLiteralIncludeRoot("api", extra)
        val f = analyze(flags = mapOf(api to listOf(include, extra), main to listOf(include)), before = all, after = staged)
            .shouldBeInstanceOf<CMakeIncludeRootReadout.Found>().facts
        f.after.getValue(nextApi) shouldBe listOf(nextInclude, extra)
    }
    "unchanged roots retain every TU include context" {
        val f = analyze(after = roots, moves = emptyMap(), rootMoves = emptyMap())
            .shouldBeInstanceOf<CMakeIncludeRootReadout.Found>().facts
        f.before shouldBe f.after
    }
    "clean compilation with a missing manifest include flag cannot grant origin authority" {
        analyze(flags = mapOf(api to emptyList(), main to listOf(include))) shouldBe
            CMakeIncludeRootReadout.Refused(CMakeIncludeRootRefusal.INCLUDE_ROOT_MISMATCH)
    }
    "an unmodeled compilation include cannot become an implicit target origin" {
        analyze(flags = mapOf(api to listOf(include, extra), main to listOf(include))) shouldBe
            CMakeIncludeRootReadout.Refused(CMakeIncludeRootRefusal.INCLUDE_ROOT_MISMATCH)
    }
    "missing translation units are unavailable rather than zero consumers" {
        analyze(flags = mapOf(api to listOf(include))) shouldBe
            CMakeIncludeRootReadout.Refused(CMakeIncludeRootRefusal.SOURCE_SET_MISMATCH)
    }
    "multiple literal target owners cannot select arbitrary include roots" {
        analyze(sourceOwners = owners + (api to listOf(apiOwner, mainOwner))) shouldBe
            CMakeIncludeRootReadout.Refused(CMakeIncludeRootRefusal.OWNER_UNAVAILABLE)
    }
    "unknown target origins cannot be ignored when their include root is not consumed" {
        analyze(before = roots + CMakeLiteralIncludeRoot("unknown", extra)) shouldBe
            CMakeIncludeRootReadout.Refused(CMakeIncludeRootRefusal.OWNER_UNAVAILABLE)
    }
    "target root order must exactly match captured compiler search order" {
        val all = roots + CMakeLiteralIncludeRoot("api", extra)
        val staged = stagedRoots + CMakeLiteralIncludeRoot("api", extra)
        analyze(flags = mapOf(api to listOf(extra, include), main to listOf(include)), before = all, after = staged) shouldBe
            CMakeIncludeRootReadout.Refused(CMakeIncludeRootRefusal.INCLUDE_ROOT_MISMATCH)
    }
    "a staged target root cannot change without an admitted path transition" {
        analyze(after = stagedRoots + CMakeLiteralIncludeRoot("api", extra)) shouldBe
            CMakeIncludeRootReadout.Refused(CMakeIncludeRootRefusal.ROOT_TRANSITION_MISMATCH)
    }
    "root moves cannot invent unobserved old roots" {
        analyze(rootMoves = mapOf(include to nextInclude, extra to Path.of("other/include"))) shouldBe
            CMakeIncludeRootReadout.Refused(CMakeIncludeRootRefusal.ROOT_TRANSITION_MISMATCH)
    }
    "staged source paths must remain unique" {
        analyze(moves = mapOf(api to main)) shouldBe
            CMakeIncludeRootReadout.Refused(CMakeIncludeRootRefusal.SOURCE_SET_MISMATCH)
    }
    "workspace escapes are refused as paths rather than compiler flags" {
        analyze(rootMoves = mapOf(include to Path.of("../include"))) shouldBe
            CMakeIncludeRootReadout.Refused(CMakeIncludeRootRefusal.PATH_UNAVAILABLE)
    }
    "retained include contexts are detached immutable data" {
        val flags = linkedMapOf(api to mutableListOf(include), main to mutableListOf(include))
        val f = analyze(flags = flags).shouldBeInstanceOf<CMakeIncludeRootReadout.Found>().facts
        flags.getValue(api).clear(); flags.clear()
        f.before shouldBe mapOf(api to listOf(include), main to listOf(include))
        runCatching { (f.before as MutableMap).clear() }.isFailure shouldBe true
        runCatching { (f.after.getValue(nextApi) as MutableList).clear() }.isFailure shouldBe true
    }
})
