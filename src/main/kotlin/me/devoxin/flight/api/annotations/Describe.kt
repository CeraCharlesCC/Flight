package me.devoxin.flight.api.annotations

/**
 * Describes an argument for a slash-exportable command option.
 * This is only used when Flight exports or synchronizes application commands.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class Describe(
    val value: String = ""
)
