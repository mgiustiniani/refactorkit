package org.refactorkit.c

import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import java.nio.file.Path
import java.util.Collections

internal enum class CQuotedIncludeRefusal {
    INPUT_LIMIT, UNSUPPORTED_SOURCE, PATH_UNAVAILABLE, INCOMPLETE_TUS,
    UNOWNED_INCLUDE, UNOBSERVED_INCLUDE, CYCLE, INCONSISTENT_REWRITE,
}
internal data class CQuotedIncludeObservation(val translationUnit: Path, val file: Path, val line: Int,
    val start: Int, val end: Int, val spelling: String, val target: Path, val origin: Int)
internal data class CQuotedIncludeEdit(val file: Path, val edit: TextEdit)
internal class CQuotedIncludeFacts(before: List<CQuotedIncludeObservation>, after: List<CQuotedIncludeObservation>,
    edits: List<CQuotedIncludeEdit>) {
    val before: List<CQuotedIncludeObservation> = Collections.unmodifiableList(ArrayList(before))
    val after: List<CQuotedIncludeObservation> = Collections.unmodifiableList(ArrayList(after))
    val edits: List<CQuotedIncludeEdit> = Collections.unmodifiableList(ArrayList(edits))
}
internal sealed interface CQuotedIncludeReadout {
    data class Found(val facts: CQuotedIncludeFacts) : CQuotedIncludeReadout
    data class Refused(val reason: CQuotedIncludeRefusal) : CQuotedIncludeReadout
}
internal object CQuotedIncludeClosure {
    fun analyze(sources: Map<Path, String>, roots: Map<Path, List<Path>>, moves: Map<Path, Path> = emptyMap(),
        afterRoots: Map<Path, List<Path>> = roots): CQuotedIncludeReadout = try {
        fun valid(path: Path) = !path.isAbsolute && path == path.normalize() && !path.startsWith("..")
        if (roots.isEmpty() || roots.size > 16 || afterRoots.size > 16 ||
            (roots.values + afterRoots.values).any { it.size > 64 }) reject(CQuotedIncludeRefusal.INPUT_LIMIT)
        when (val admission = CHeaderFieldSourcePolicy.check(sources)) {
            CHeaderFieldSourceAdmission.Admitted -> Unit
            is CHeaderFieldSourceAdmission.Refused -> reject(if (admission.reason == CHeaderFieldRefusal.INPUT_LIMIT)
                CQuotedIncludeRefusal.INPUT_LIMIT else CQuotedIncludeRefusal.UNSUPPORTED_SOURCE)
        }
        // Core text ranges count LF; CRLF retains those coordinates, bare CR does not.
        if (sources.values.any { Regex("\r(?!\n)").containsMatchIn(it) }) reject(CQuotedIncludeRefusal.UNSUPPORTED_SOURCE)
        val paths = sources.keys + roots.keys + roots.values.flatten() + afterRoots.keys + afterRoots.values.flatten() + moves.keys + moves.values
        if (paths.any { !valid(it) } || moves.any { (old, next) -> old !in sources || old == next ||
                old.fileName.toString().substringAfterLast('.') != next.fileName.toString().substringAfterLast('.') }) {
            reject(CQuotedIncludeRefusal.PATH_UNAVAILABLE)
        }
        val stagedPaths = sources.keys.map { moves[it] ?: it }.toSet()
        if (stagedPaths.size != sources.size) reject(CQuotedIncludeRefusal.PATH_UNAVAILABLE)
        val before = read(sources, roots)
        val grouped = before.groupBy { it.file to it.line }
        val edits = mutableListOf<CQuotedIncludeEdit>()
        grouped.forEach { (site, observations) ->
            val file = moves[site.first] ?: site.first
            val options = linkedSetOf(observations.first().spelling)
            observations.forEach { observation ->
                val tu = moves[observation.translationUnit] ?: observation.translationUnit
                val target = moves[observation.target] ?: observation.target
                val directories = listOf(file.parent ?: Path.of("")) + afterRoots[tu].orEmpty()
                directories.getOrNull(observation.origin)?.let { options += it.relativize(target).toString().replace('\\', '/') }
                options += (file.parent ?: Path.of("")).relativize(target).toString().replace('\\', '/')
            }
            val spelling = options.firstOrNull { option ->
                option.matches(Regex("[A-Za-z_0-9./-]{1,512}")) && observations.all { observation ->
                    val tu = moves[observation.translationUnit] ?: observation.translationUnit
                    runCatching { resolve(stagedPaths, file, option, afterRoots[tu].orEmpty()).first }.getOrNull() ==
                        (moves[observation.target] ?: observation.target)
                }
            } ?: reject(CQuotedIncludeRefusal.INCONSISTENT_REWRITE)
            if (spelling != observations.first().spelling) {
                val observation = observations.first()
                edits += CQuotedIncludeEdit(file, TextEdit(SourceRange(SourcePosition(site.second - 1, observation.start),
                    SourcePosition(site.second - 1, observation.end)), spelling))
            }
        }
        val stagedSources = sources.map { (file, text) ->
            val next = moves[file] ?: file
            next to TextEdits.apply(text, edits.filter { it.file == next }.map { it.edit })
        }.toMap()
        val after = read(stagedSources, afterRoots)
        fun key(o: CQuotedIncludeObservation) = listOf(o.translationUnit, o.file, o.line, o.target)
        val expected = before.map { it.copy(translationUnit = moves[it.translationUnit] ?: it.translationUnit,
            file = moves[it.file] ?: it.file, target = moves[it.target] ?: it.target) }.map(::key).toSet()
        if (expected != after.map(::key).toSet() || before.size != after.size) reject(CQuotedIncludeRefusal.INCONSISTENT_REWRITE)
        CQuotedIncludeReadout.Found(CQuotedIncludeFacts(before, after, edits))
    } catch (failure: Rejected) { CQuotedIncludeReadout.Refused(failure.reason) }

    private data class Directive(val line: Int, val start: Int, val end: Int, val spelling: String)
    private fun directives(text: String): List<Directive> {
        val active = CTokenizer().tokenize(text).groupBy { it.line }.filterValues { it.first().text == "#" }.keys
        return text.split('\n').mapIndexedNotNull { index, line ->
            if (index + 1 !in active) return@mapIndexedNotNull null
            val match = INCLUDE.matchEntire(line) ?: return@mapIndexedNotNull null
            val target = requireNotNull(match.groups[1])
            Directive(index + 1, target.range.first, target.range.last + 1, target.value)
        }
    }
    private fun read(sources: Map<Path, String>, roots: Map<Path, List<Path>>): List<CQuotedIncludeObservation> {
        if (roots.keys != sources.keys.filter { it.toString().endsWith(".c") }.toSet()) reject(CQuotedIncludeRefusal.INCOMPLETE_TUS)
        val directives = sources.mapValues { directives(it.value) }
        if (directives.values.any { it.size > 128 }) reject(CQuotedIncludeRefusal.INPUT_LIMIT)
        val result = mutableListOf<CQuotedIncludeObservation>()
        roots.entries.sortedBy { it.key.toString() }.forEach { (tu, includes) ->
            val visited = mutableSetOf<Path>()
            fun visit(file: Path, stack: Set<Path>) {
                if (file in stack) reject(CQuotedIncludeRefusal.CYCLE)
                if (!visited.add(file)) return
                directives.getValue(file).forEach { directive ->
                    val (target, origin) = resolve(sources.keys, file, directive.spelling, includes)
                    result += CQuotedIncludeObservation(tu, file, directive.line, directive.start, directive.end, directive.spelling, target, origin)
                    if (result.size > 8192) reject(CQuotedIncludeRefusal.INPUT_LIMIT)
                    visit(target, stack + setOf(file))
                }
            }
            visit(tu, emptySet())
        }
        val observed = result.map { it.file to it.line }.toSet()
        if (directives.any { (file, values) -> values.any { file to it.line !in observed } }) reject(CQuotedIncludeRefusal.UNOBSERVED_INCLUDE)
        return result
    }
    private fun resolve(sources: Set<Path>, file: Path, spelling: String, roots: List<Path>): Pair<Path, Int> {
        val candidates = (listOf(file.parent ?: Path.of("")) + roots).map { it.resolve(spelling).normalize() }
        if (candidates.any { it.isAbsolute || it.startsWith("..") }) reject(CQuotedIncludeRefusal.PATH_UNAVAILABLE)
        val index = candidates.indexOfFirst { it in sources }
        if (index < 0) reject(CQuotedIncludeRefusal.UNOWNED_INCLUDE)
        return candidates[index] to index
    }
    private val INCLUDE = Regex("\\s*#\\s*include\\s+\"([A-Za-z_0-9./-]+)\"\\s*")
    private fun reject(reason: CQuotedIncludeRefusal): Nothing = throw Rejected(reason)
    private class Rejected(val reason: CQuotedIncludeRefusal) : RuntimeException()
}
