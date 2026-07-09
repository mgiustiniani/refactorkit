package org.refactorkit.java.recipe

/**
 * Parsed representation of a RefactorKit YAML recipe.
 *
 * Example recipe:
 * ```yaml
 * id: java.rename-package
 * name: Rename Java package
 * language: java
 * parameters:
 *   oldPackage: string
 *   newPackage: string
 * steps:
 *   - type: movePackage
 *     from: "{{ oldPackage }}"
 *     to: "{{ newPackage }}"
 *   - type: organizeImports
 *   - type: runDiagnostics
 * ```
 */
data class RecipeDefinition(
    val id: String,
    val name: String,
    val language: String = "java",
    val description: String = "",
    val parameters: Map<String, ParameterDef> = emptyMap(),
    val steps: List<StepDef>,
)

data class ParameterDef(
    val type: String = "string",
    val description: String = "",
    val default: String? = null,
)

data class StepDef(
    val type: String,
    val params: Map<String, String> = emptyMap(),
)

/**
 * Result of substituting parameters into a step.
 * All `{{ paramName }}` placeholders are replaced with actual values.
 */
fun StepDef.substitute(params: Map<String, String>): StepDef {
    val substituted = this.params.mapValues { (_, v) -> substituteTemplate(v, params) }
    return copy(params = substituted)
}

private fun substituteTemplate(template: String, params: Map<String, String>): String {
    var result = template
    for ((key, value) in params) {
        result = result.replace("{{ $key }}", value).replace("{{$key}}", value)
    }
    return result
}
