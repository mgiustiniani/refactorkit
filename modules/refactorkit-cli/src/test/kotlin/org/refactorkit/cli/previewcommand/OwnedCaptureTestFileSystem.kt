package org.refactorkit.cli.previewcommand

import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URI
import java.nio.channels.SeekableByteChannel
import java.nio.file.*
import java.nio.file.attribute.*
import java.nio.file.spi.FileSystemProvider

/** Scenario-owned scheduling adapter; every attribute and descriptor comes from real NIO. */
internal class OwnedCaptureTestFileSystem(private val beforeDirectoryOpen: (Path) -> Unit) : FileSystem() {
    private val actual = FileSystems.getDefault()
    private val adapter = Provider()
    override fun provider(): FileSystemProvider = adapter
    override fun close() = Unit
    override fun isOpen() = actual.isOpen
    override fun isReadOnly() = actual.isReadOnly
    override fun getSeparator() = actual.separator
    override fun getRootDirectories(): Iterable<Path> = actual.rootDirectories.map(::wrap)
    override fun getFileStores(): Iterable<FileStore> = actual.fileStores
    override fun supportedFileAttributeViews(): Set<String> = actual.supportedFileAttributeViews()
    override fun getPath(first: String, vararg more: String): Path = wrap(actual.getPath(first, *more))
    override fun getPathMatcher(syntaxAndPattern: String): PathMatcher {
        val matcher = actual.getPathMatcher(syntaxAndPattern)
        return PathMatcher { matcher.matches(unwrap(it)) }
    }
    override fun getUserPrincipalLookupService(): UserPrincipalLookupService = actual.userPrincipalLookupService
    override fun newWatchService(): WatchService = actual.newWatchService()

    private inner class PathHandler(val value: Path) : InvocationHandler {
        override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? = when (method.name) {
            "getFileSystem" -> this@OwnedCaptureTestFileSystem
            "equals" -> args?.singleOrNull()?.let { other ->
                other is Path && Proxy.isProxyClass(other.javaClass) &&
                    (Proxy.getInvocationHandler(other) as? OwnedCaptureTestFileSystem.PathHandler)?.let {
                        it.owner() === this@OwnedCaptureTestFileSystem && value == it.value
                    } == true
            } ?: false
            "hashCode" -> 31 * System.identityHashCode(this@OwnedCaptureTestFileSystem) + value.hashCode()
            else -> invokeReal(method, value, args).let { if (it is Path) wrap(it) else it }
        }
        fun owner() = this@OwnedCaptureTestFileSystem
    }

    private fun wrap(path: Path): Path = Proxy.newProxyInstance(Path::class.java.classLoader,
        arrayOf(Path::class.java), PathHandler(path)) as Path
    private fun unwrap(path: Path): Path = if (Proxy.isProxyClass(path.javaClass))
        (Proxy.getInvocationHandler(path) as? OwnedCaptureTestFileSystem.PathHandler)?.value ?: path else path
    private fun invokeReal(method: java.lang.reflect.Method, target: Any, args: Array<out Any?>?): Any? = try {
        method.invoke(target, *(args?.map { if (it is Path) unwrap(it) else it }?.toTypedArray() ?: emptyArray()))
    } catch (failure: InvocationTargetException) { throw (failure.cause ?: failure) }

    private fun secure(stream: SecureDirectoryStream<Path>, directory: Path): SecureDirectoryStream<Path> {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(SecureDirectoryStream::class.java.classLoader,
            arrayOf(SecureDirectoryStream::class.java), InvocationHandler { _, method, args ->
                if (method.name == "newDirectoryStream") {
                    val name = unwrap(args!![0] as Path)
                    val target = directory.resolve(name)
                    beforeDirectoryOpen(target)
                    @Suppress("UNCHECKED_CAST")
                    val options = args[1] as Array<LinkOption>
                    secure(stream.newDirectoryStream(name, *options), target)
                } else invokeReal(method, stream, args)
            }) as SecureDirectoryStream<Path>
    }

    private inner class Provider : FileSystemProvider() {
        private val delegate = actual.provider()
        override fun getScheme() = "owned-capture-test"
        override fun newFileSystem(uri: URI, env: MutableMap<String, *>): FileSystem = this@OwnedCaptureTestFileSystem
        override fun getFileSystem(uri: URI): FileSystem = this@OwnedCaptureTestFileSystem
        override fun getPath(uri: URI): Path = wrap(Path.of(uri))
        override fun newDirectoryStream(dir: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> {
            val native = unwrap(dir)
            val stream = delegate.newDirectoryStream(native) { filter.accept(wrap(it)) }
            check(stream is SecureDirectoryStream<Path>) { "Qualification requires real secure NIO directory handles" }
            return secure(stream, native)
        }
        override fun newByteChannel(path: Path, options: MutableSet<out OpenOption>, vararg attrs: FileAttribute<*>): SeekableByteChannel =
            delegate.newByteChannel(unwrap(path), options, *attrs)
        override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) = delegate.createDirectory(unwrap(dir), *attrs)
        override fun delete(path: Path) = delegate.delete(unwrap(path))
        override fun copy(source: Path, target: Path, vararg options: CopyOption) = delegate.copy(unwrap(source), unwrap(target), *options)
        override fun move(source: Path, target: Path, vararg options: CopyOption) = delegate.move(unwrap(source), unwrap(target), *options)
        override fun isSameFile(path: Path, path2: Path) = delegate.isSameFile(unwrap(path), unwrap(path2))
        override fun isHidden(path: Path) = delegate.isHidden(unwrap(path))
        override fun getFileStore(path: Path): FileStore = delegate.getFileStore(unwrap(path))
        override fun checkAccess(path: Path, vararg modes: AccessMode) = delegate.checkAccess(unwrap(path), *modes)
        override fun <V : FileAttributeView> getFileAttributeView(path: Path, type: Class<V>, vararg options: LinkOption): V? =
            delegate.getFileAttributeView(unwrap(path), type, *options)
        override fun <A : BasicFileAttributes> readAttributes(path: Path, type: Class<A>, vararg options: LinkOption): A =
            delegate.readAttributes(unwrap(path), type, *options)
        override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): MutableMap<String, Any> =
            delegate.readAttributes(unwrap(path), attributes, *options)
        override fun setAttribute(path: Path, attribute: String, value: Any, vararg options: LinkOption) =
            delegate.setAttribute(unwrap(path), attribute, value, *options)
    }
}
