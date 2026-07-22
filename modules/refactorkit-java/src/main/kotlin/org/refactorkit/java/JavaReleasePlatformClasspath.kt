package org.refactorkit.java

import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.internal.compiler.batch.FileSystem
import org.eclipse.jdt.internal.core.dom.ICompilationUnitResolver
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Path

/**
 * JDT environment bridge for one hash-attested Java release platform.
 *
 * ASTParser's public environment API recognizes a current JRT image as the
 * system library, but it does not expose ECJ's JEP 247 release classpath. For a
 * historical release we therefore let ASTParser validate the attested JRT and
 * replace only that JRT entry at the resolver boundary with ECJ's ct.sym-backed
 * older-system-release view. Project source and dependency classpaths remain
 * untouched.
 */
internal class JavaReleasePlatformClasspath private constructor(
    val environmentPaths: List<Path>,
    private val platformHome: Path,
    private val release: Int,
    private val historical: Boolean,
) : AutoCloseable {
    fun prepare(parser: ASTParser) {
        if (!historical) return
        val olderSystemRelease = FileSystem.getOlderSystemRelease(
            platformHome.toString(),
            release.toString(),
            null,
        ) ?: error("JDT could not construct the Java $release ct.sym classpath")
        val field = ASTParser::class.java.getDeclaredField("unitResolver").apply { isAccessible = true }
        val delegate = field.get(parser) as ICompilationUnitResolver
        val proxy = Proxy.newProxyInstance(
            ICompilationUnitResolver::class.java.classLoader,
            arrayOf(ICompilationUnitResolver::class.java),
        ) { _, method, arguments ->
            val prepared = arguments?.clone()
            if (prepared != null) {
                prepared.indices.forEach { index ->
                    val values = prepared[index] as? List<*> ?: return@forEach
                    if (values.none { it is FileSystem.Classpath }) return@forEach
                    var replaced = false
                    prepared[index] = values.map { entry ->
                        if (entry is FileSystem.Classpath && entry.javaClass.simpleName.startsWith("ClasspathJrt")) {
                            check(!replaced) { "Multiple JRT system libraries were supplied to JDT" }
                            replaced = true
                            olderSystemRelease
                        } else entry
                    }.also {
                        check(replaced) { "JDT did not expose the attested JRT system library for Java $release" }
                    }
                }
            }
            try {
                method.invoke(delegate, *(prepared ?: emptyArray()))
            } catch (failure: InvocationTargetException) {
                throw failure.cause ?: failure
            }
        }
        field.set(parser, proxy)
    }

    override fun close() = Unit

    companion object {
        fun materialize(authority: JavaReleasePlatformAuthority): JavaReleasePlatformClasspath {
            val jrt = authority.platformHome.resolve("lib/jrt-fs.jar")
            check(java.nio.file.Files.isRegularFile(jrt)) { "Attested Java platform JRT filesystem is unavailable" }
            if (authority.release == authority.platformRelease) {
                check(authority.currentSystemModules != null && authority.currentSystemModulesSha256 != null) {
                    "Current-release system module evidence is unavailable"
                }
            }
            return JavaReleasePlatformClasspath(
                environmentPaths = listOf(jrt),
                platformHome = authority.platformHome,
                release = authority.release,
                historical = authority.release != authority.platformRelease,
            )
        }
    }
}
