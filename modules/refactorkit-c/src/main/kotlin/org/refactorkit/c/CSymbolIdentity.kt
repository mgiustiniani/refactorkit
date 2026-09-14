package org.refactorkit.c

/**
 * C lookup spaces. C distinguishes these identifier namespaces; this is NOT a
 * C++ namespace construct. The same spelling can denote different symbols in
 * different namespaces (e.g. a tag `struct point` and an ordinary `point`).
 */
enum class CIdentifierNamespace {
    ORDINARY,
    TAG,
    LABEL,
    MEMBER,
    ENUMERATOR,
    TYPEDEF,
}

/** Per-translation-unit visibility of a C symbol. */
enum class CVisibility {
    LOCAL,
    FILE_STATIC,
    GLOBAL,
    EXPORTED,
}

/** Semantic identity of a C symbol within a translation unit. */
data class CSymbolIdentity(
    val namespace: CIdentifierNamespace,
    val name: String,
    val scope: String,
    val visibility: CVisibility,
) {
    init {
        require(name.isNotBlank() && name.length <= 512) { "C symbol name is invalid" }
        require(scope.length <= 512) { "C symbol scope is invalid" }
    }

    /** Stable identity key distinguishing namespace + name + scope. */
    fun key(): String = "$namespace:$scope:$name"
}

/** Bounded C symbol index over a single translation unit. */
data class CTranslationUnitIndex(
    val file: String,
    val symbols: List<CSymbolIdentity>,
) {
    init {
        require(file.isNotBlank()) { "translation unit file is invalid" }
        require(symbols.size <= MAX_SYMBOLS) { "translation unit symbol count exceeds the bounded limit" }
        require(symbols.map { it.key() }.distinct().size == symbols.size) { "translation unit contains duplicate symbol identities" }
    }

    fun search(query: String): List<CSymbolIdentity> = symbols.filter {
        it.name.contains(query, ignoreCase = true) || it.key().contains(query, ignoreCase = true)
    }.take(MAX_RESULTS)

    companion object {
        const val MAX_SYMBOLS = 16_384
        const val MAX_RESULTS = 256
    }
}

/** Bounded C symbol index across translation units. */
data class CSymbolIndex(
    val units: List<CTranslationUnitIndex>,
) {
    init {
        require(units.size <= MAX_UNITS) { "C symbol index unit count exceeds the bounded limit" }
        require(units.map { it.file }.distinct().size == units.size) { "C symbol index contains duplicate translation units" }
    }

    /** Adds a unit, enforcing the bounded unit count. */
    fun add(unit: CTranslationUnitIndex) {
        require(units.size + 1 <= MAX_UNITS) { "C symbol index unit count exceeds the bounded limit" }
        require(units.none { it.file == unit.file }) { "C symbol index contains duplicate translation units" }
    }

    fun search(query: String): List<CSymbolIdentity> = units.flatMap { it.search(query) }.take(MAX_RESULTS)

    fun definitions(namespace: CIdentifierNamespace, name: String): List<CSymbolIdentity> =
        units.flatMap { it.symbols }.filter { it.namespace == namespace && it.name == name }.take(MAX_RESULTS)

    companion object {
        const val MAX_UNITS = 4_096
        const val MAX_RESULTS = 256
    }
}
