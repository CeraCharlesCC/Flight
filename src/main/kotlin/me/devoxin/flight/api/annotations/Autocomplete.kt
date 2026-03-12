package me.devoxin.flight.api.annotations

import me.devoxin.flight.api.autocomplete.AutocompleteHandler
import kotlin.reflect.KClass

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class Autocomplete(
    /** The typed autocomplete handler for this parameter. */
    val value: KClass<out AutocompleteHandler<*>>
)
