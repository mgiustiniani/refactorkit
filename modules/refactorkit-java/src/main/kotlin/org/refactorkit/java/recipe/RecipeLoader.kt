package org.refactorkit.java.recipe

import org.yaml.snakeyaml.Yaml
import java.io.InputStream
import java.nio.file.Path
import kotlin.io.path.inputStream

/**
 * Loads [RecipeDefinition] from YAML.
 */
object RecipeLoader {

    fun load(input: InputStream): RecipeDefinition {
        val yaml = Yaml()
        @Suppress("UNCHECKED_CAST")
        val map = yaml.load<Map<String, Any>>(input)
        return parseRecipe(map)
    }

    fun load(path: Path): RecipeDefinition = path.inputStream().use { load(it) }

    @Suppress("UNCHECKED_CAST")
    private fun parseRecipe(map: Map<String, Any>): RecipeDefinition {
        val id = map["id"]?.toString() ?: error("Recipe missing 'id'")
        val name = map["name"]?.toString() ?: id
        val language = map["language"]?.toString() ?: "java"
        val description = map["description"]?.toString() ?: ""

        val parameters = (map["parameters"] as? Map<String, Any>)?.mapValues { (_, v) ->
            when (v) {
                is String -> ParameterDef(type = v)
                is Map<*, *> -> ParameterDef(
                    type = v["type"]?.toString() ?: "string",
                    description = v["description"]?.toString() ?: "",
                    default = v["default"]?.toString(),
                )
                else -> ParameterDef()
            }
        } ?: emptyMap()

        val steps = (map["steps"] as? List<Map<String, Any>>)?.map { stepMap ->
            val type = stepMap["type"]?.toString() ?: error("Step missing 'type'")
            val params = stepMap.filterKeys { it != "type" }.mapValues { it.value?.toString() ?: "" }
            StepDef(type = type, params = params)
        } ?: emptyList()

        return RecipeDefinition(
            id = id,
            name = name,
            language = language,
            description = description,
            parameters = parameters,
            steps = steps,
        )
    }
}
