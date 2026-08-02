package org.refactorkit.java

import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.internal.compiler.batch.ClasspathJar
import org.eclipse.jdt.internal.core.dom.ICompilationUnitResolver
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Owns archive classpaths created by one standalone JDT analysis call.
 *
 * JDT 3.44 passes these resources to its internal compilation-unit resolver,
 * but an ASTParser created without an Eclipse Java project exposes no public
 * close operation. ClasspathJar.reset() is JDT's lifecycle operation for
 * closing the ZipFile opened during binding resolution.
 */
internal class JdtClasspathResourceScope : AutoCloseable {
    private val identities = Collections.newSetFromMap(IdentityHashMap<ClasspathJar, Boolean>())
    private val archives = mutableListOf<ClasspathJar>()
    private var closed = false

    fun prepare(parser: ASTParser) {
        check(!closed) { "JDT classpath resource scope is already closed" }
        val field = ASTParser::class.java.getDeclaredField("unitResolver").apply { isAccessible = true }
        val delegate = field.get(parser) as ICompilationUnitResolver
        val proxy = Proxy.newProxyInstance(
            ICompilationUnitResolver::class.java.classLoader,
            arrayOf(ICompilationUnitResolver::class.java),
        ) { _, method, arguments ->
            captureArchives(arguments)
            try {
                method.invoke(delegate, *(arguments ?: emptyArray()))
            } catch (failure: InvocationTargetException) {
                throw failure.cause ?: failure
            }
        }
        field.set(parser, proxy)
    }

    private fun captureArchives(arguments: Array<out Any?>?) {
        arguments.orEmpty().forEach { argument ->
            val values = argument as? List<*> ?: return@forEach
            values.filterIsInstance<ClasspathJar>().forEach { archive ->
                synchronized(this) {
                    check(!closed) { "JDT supplied a classpath archive after its analysis scope closed" }
                    if (identities.add(archive)) archives += archive
                }
            }
        }
    }

    override fun close() {
        val owned = synchronized(this) {
            if (closed) return
            closed = true
            archives.asReversed().toList().also {
                archives.clear()
                identities.clear()
            }
        }
        var resetFailure: Throwable? = null
        owned.forEach { archive ->
            try {
                archive.reset()
            } catch (failure: Throwable) {
                resetFailure?.addSuppressed(failure) ?: run { resetFailure = failure }
            }
        }
        resetFailure?.let { throw it }
    }
}
