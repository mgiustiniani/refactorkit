package org.refactorkit.treesitter

/**
 * Extension point for native Tree-sitter language bindings.
 *
 * Implement this interface and register it with [TreeSitterAdapter.setNativeBinding]
 * to enable parse-tree-accurate structural analysis that replaces the regex/heuristic
 * fallback in [GenericOutline] and [CommentLiteralFilter].
 *
 * ## How to plug in native bindings
 *
 * 1. Add a JNI or JNA library that wraps the Tree-sitter C library and one or more
 *    language grammars (e.g., `tree-sitter-java`, `tree-sitter-python`).
 * 2. Implement [TreeSitterNativeBinding].
 * 3. Register the binding before first use:
 *    ```kotlin
 *    val adapter = TreeSitterAdapter()
 *    adapter.setNativeBinding(MyNativeBinding())
 *    ```
 * 4. Alternatively, set the system property `refactorkit.treesitter.native=true` to
 *    signal availability externally (e.g., for testing without real bindings).
 *
 * ## Contract
 *
 * - [supports] must return quickly (no I/O).
 * - [outline] and [findIdentifier] must be thread-safe if used from multiple threads.
 * - [findIdentifier] must exclude occurrences inside comments and string literals
 *   using the parse tree (not heuristics).
 * - Methods must not throw for unsupported languages; return empty lists instead.
 */
interface TreeSitterNativeBinding {

    /**
     * Returns true if native bindings are loaded and the grammar for [languageId]
     * is available on this system.
     */
    fun supports(languageId: String): Boolean

    /**
     * Parse [content] for [languageId] and return a language-neutral structural outline.
     * The returned items must be sorted by line number (ascending).
     *
     * Must return an empty list (not throw) for unsupported languages.
     */
    fun outline(content: String, languageId: String): List<GenericOutline.OutlineItem>

    /**
     * Find all whole-identifier occurrences of [identifier] in [content] that are
     * NOT inside comments or string/char literals, as determined by the parse tree.
     *
     * This is the accurate replacement for [CommentLiteralFilter] + [GenericStructuralSearch].
     *
     * Must return an empty list (not throw) for unsupported languages.
     */
    fun findIdentifier(
        content: String,
        identifier: String,
        languageId: String,
    ): List<GenericStructuralSearch.SearchMatch>
}
