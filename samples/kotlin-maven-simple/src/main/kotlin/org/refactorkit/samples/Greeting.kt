package org.refactorkit.samples

class Greeting
interface GreetingPort
enum class GreetingMode { HELLO }
annotation class GreetingMarker
object GreetingRegistry
data object GreetingDataRegistry
class GreetingOwner {
    companion object
    object NestedRegistry
    val anonymous = object {}
}
