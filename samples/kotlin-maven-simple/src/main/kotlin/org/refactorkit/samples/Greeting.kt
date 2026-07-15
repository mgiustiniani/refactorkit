package org.refactorkit.samples

fun topLevelGreeting(name: String): String = "Hello $name"

class Greeting {
    fun render(name: String): String = topLevelGreeting(name)
    private val normalizer: (String) -> String = { value -> value.trim() }
}
interface GreetingPort {
    fun greet(name: String): String
}
enum class GreetingMode { HELLO }
annotation class GreetingMarker
object GreetingRegistry {
    fun lookup(): String = "registry"
}
data object GreetingDataRegistry
class GreetingOwner {
    companion object
    object NestedRegistry
    val anonymous = object {}
}
class GreetingConsumer(val greeting: Greeting)
private class InternalGreeting
private fun internalGreeting(value: InternalGreeting): InternalGreeting = InternalGreeting()
