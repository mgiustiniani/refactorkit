package org.refactorkit.java

/** Recognizes build inputs whose only project references are explicit dependency declarations. */
internal object GradleProjectRenameScript {
    data class ProjectDependency(val configuration: String, val path: String, val range: IntRange)

    private const val identifier = "[A-Za-z_][A-Za-z0-9_]*"
    private const val segment = "[A-Za-z0-9][A-Za-z0-9._-]*"
    private const val projectPath = ":$segment(?::$segment)*"
    private const val plugin = "(?:java|java-library|application|base)"
    private const val settingsPath = ":?$segment(?::$segment)*"
    private const val quotedValue = """(?:"[A-Za-z0-9._:/+=-]+"|'[A-Za-z0-9._:/+=-]+')"""
    private const val doublePath = """"(?<path>$projectPath)""""
    private const val quotedPath = """(?<quote>['"])(?<path>$projectPath)\k<quote>"""
    private const val configuration =
        "(?<configuration>implementation|api|compileOnlyApi|compileOnly|runtimeOnly|" +
            "testImplementation|testCompileOnly|testRuntimeOnly|annotationProcessor|testAnnotationProcessor)"
    private const val coordinate = "$segment:$segment:[A-Za-z0-9][A-Za-z0-9._+-]*(?::$segment)?"

    private val rootName = Regex("""rootProject\.name\s*=\s*$quotedValue""")
    private val groovyInclude = Regex("""include\b""")
    private val kotlinInclude = Regex("""include\s*\(""")
    private val includeDouble = Regex(""""$settingsPath"""")
    private val includeQuoted = Regex("""(?<quote>['"])$settingsPath\k<quote>""")
    private val openParenthesis = Regex("""\(""")
    private val closeParenthesis = Regex("""\)""")
    private val comma = Regex(",")
    private val closeBrace = Regex("}")

    private val imports = Regex(
        """import\s+org\.gradle\.api\.tasks\.(?:compile\.JavaCompile|testing\.Test)\b""",
    )
    private val coordinates = Regex("""(?:group|version)\s*=\s*$quotedValue""")
    private val repositories = Regex("""repositories\s*\{\s*mavenCentral\s*\(\s*\)\s*;?\s*}""")
    private val application = Regex(
        """application\s*\{\s*mainClass(?:\s*=\s*$quotedValue|\.set\s*\(\s*$quotedValue\s*\))\s*;?\s*}""",
    )
    private val kotlinPlugins = Regex("""plugins\s*\{\s*(?:java|`java-library`|application|base|id\s*\(\s*"$plugin"\s*\))\s*;?\s*}""")
    private val groovyPluginId = """(?:"$plugin"|'$plugin')"""
    private val groovyPlugins = Regex("""plugins\s*\{\s*id(?:\s+$groovyPluginId|\s*\(\s*$groovyPluginId\s*\))\s*;?\s*}""")
    private val groovyApply = Regex("""apply\s+plugin\s*:\s*$groovyPluginId""")
    private val gradleProperty = Regex(
        """(?:def|val)\s+(?<local>$identifier)\s*=\s*providers\.gradleProperty\s*\(\s*$quotedValue\s*\)\.getOrElse\s*\(\s*$quotedValue\s*\)""",
    )
    private val sharedBlock = Regex("""subprojects\s*\{""")
    private val javaBlock = Regex("""java\s*\{""")
    private val javaOptions = listOf(
        Regex("""(?:sourceCompatibility|targetCompatibility)\s*=\s*(?:JavaVersion\.VERSION_[0-9_]+|$quotedValue)"""),
        Regex("""withSourcesJar\s*\(\s*\)"""),
        Regex("""toolchain\s*\{\s*languageVersion\s*=\s*JavaLanguageVersion\.of\s*\(\s*[0-9]+\s*\)\s*;?\s*}"""),
    )
    private val dependencyBlock = Regex("""dependencies\s*\{""")
    private val kotlinProject = Regex("""$configuration\s*\(\s*project\s*\(\s*$doublePath\s*\)\s*\)""")
    private val groovyProject = Regex("""$configuration\s+project\s*\(\s*$quotedPath\s*\)""")
    private val groovyProjectCall = Regex("""$configuration\s*\(\s*project\s*\(\s*$quotedPath\s*\)\s*\)""")
    private val kotlinCoordinate = Regex("""$configuration\s*\(\s*"$coordinate"\s*\)""")
    private val groovyCoordinateValue = """(?:"$coordinate"|'$coordinate')"""
    private val groovyCoordinate = Regex("""$configuration(?:\s+$groovyCoordinateValue|\s*\(\s*$groovyCoordinateValue\s*\))""")
    private val compileTask = Regex(
        """tasks\.(?:withType\s*(?:\(\s*JavaCompile\s*\)|<JavaCompile>\s*\(\s*\))\.configureEach|named\s*\(\s*(?:"compileJava"|'compileJava')\s*\))\s*\{""",
    )
    private val testTask = Regex(
        """tasks\.(?:withType\s*(?:\(\s*Test\s*\)|<Test>\s*\(\s*\))\.configureEach|named\s*\(\s*(?:"test"|'test')\s*\))\s*\{""",
    )
    private val compilerRelease = Regex("""options\.release(?:\s*=\s*[0-9]+|\.set\s*\(\s*[0-9]+\s*\))""")
    private val compilerArg = Regex(
        """options\.compilerArgs\.add\s*\(\s*(?:"-(?:proc:none|A$identifier(?:\.$identifier)*=)"|'-(?:proc:none|A$identifier(?:\.$identifier)*=)')(?:\s*\+\s*(?<local>$identifier))?\s*\)""",
    )
    private val testFramework = Regex("""useJUnit(?:Platform)?\s*\(\s*\)""")
    private val testProperty = Regex(
        """systemProperty(?:\s*\(\s*$quotedValue\s*,\s*$quotedValue\s*\)|\s+$quotedValue\s*,\s*(?<local>$identifier))""",
    )

    fun settingsAreLocal(fileName: String, source: String): Boolean {
        val cursor = Cursor(source)
        val kotlin = fileName.endsWith(".kts")
        while (!cursor.atEnd()) {
            if (cursor.take(rootName) != null) continue
            if (cursor.take(if (kotlin) kotlinInclude else groovyInclude) == null) return false
            val parenthesized = kotlin || cursor.take(openParenthesis) != null
            do {
                if (cursor.take(if (kotlin) includeDouble else includeQuoted) == null) return false
            } while (cursor.take(comma) != null)
            if (parenthesized && cursor.take(closeParenthesis) == null) return false
        }
        return true
    }

    fun dependencies(fileName: String, source: String): List<ProjectDependency>? {
        val cursor = Cursor(source)
        val kotlin = fileName.endsWith(".kts")
        val result = mutableListOf<ProjectDependency>()
        val locals = mutableSetOf<String>()
        while (!cursor.atEnd()) {
            val property = cursor.take(gradleProperty)
            when {
                property != null -> if (!locals.add(property.groups["local"]!!.value)) return null
                cursor.take(dependencyBlock) != null -> {
                    val declarations = readDependencies(cursor, kotlin) ?: return null
                    result.addAll(declarations)
                }
                cursor.take(sharedBlock) != null -> {
                    while (cursor.take(closeBrace) == null) {
                        if (!readBuildSetting(cursor, kotlin, locals)) return null
                    }
                }
                !readBuildSetting(cursor, kotlin, locals) -> return null
            }
        }
        return result
    }

    private fun readDependencies(cursor: Cursor, kotlin: Boolean): List<ProjectDependency>? {
        val result = mutableListOf<ProjectDependency>()
        while (cursor.take(closeBrace) == null) {
            val project = if (kotlin) cursor.take(kotlinProject) else cursor.take(groovyProject) ?: cursor.take(groovyProjectCall)
            if (project != null) {
                val path = project.groups["path"]!!
                result += ProjectDependency(project.groups["configuration"]!!.value, path.value, path.range)
            } else if (cursor.take(if (kotlin) kotlinCoordinate else groovyCoordinate) == null) return null
        }
        return result
    }

    private fun readBuildSetting(cursor: Cursor, kotlin: Boolean, locals: Set<String>): Boolean {
        val plugins = if (kotlin) cursor.take(kotlinPlugins) else cursor.take(groovyPlugins) ?: cursor.take(groovyApply)
        if (plugins != null || cursor.take(coordinates) != null || cursor.take(repositories) != null ||
            cursor.take(application) != null || kotlin && cursor.take(imports) != null
        ) return true

        if (cursor.take(javaBlock) != null) return cursor.readBlock { javaOptions.any { cursor.take(it) != null } }
        if (cursor.take(compileTask) != null) {
            return cursor.readBlock {
                cursor.take(compilerRelease) != null || cursor.take(compilerArg).usesKnownLocal(locals)
            }
        }
        if (cursor.take(testTask) != null) {
            return cursor.readBlock {
                cursor.take(testFramework) != null || cursor.take(testProperty).usesKnownLocal(locals)
            }
        }
        return false
    }

    private fun MatchResult?.usesKnownLocal(locals: Set<String>): Boolean =
        this != null && groups["local"]?.value?.let(locals::contains) != false

    private class Cursor(private val source: String) {
        private var offset = 0

        fun atEnd(): Boolean {
            skipTrivia()
            return offset == source.length
        }

        fun take(pattern: Regex): MatchResult? {
            skipTrivia()
            val match = pattern.matchAt(source, offset) ?: return null
            offset = match.range.last + 1
            return match
        }

        fun readBlock(next: () -> Boolean): Boolean {
            while (take(closeBrace) == null) {
                if (!next()) return false
            }
            return true
        }

        private fun skipTrivia() {
            while (offset < source.length) {
                when {
                    source[offset].isWhitespace() || source[offset] == ';' -> offset++
                    source.startsWith("//", offset) -> {
                        val end = source.indexOf('\n', offset + 2)
                        offset = if (end < 0) source.length else end
                    }
                    source.startsWith("/*", offset) -> {
                        val end = source.indexOf("*/", offset + 2)
                        if (end < 0) return
                        offset = end + 2
                    }
                    else -> return
                }
            }
        }
    }
}
