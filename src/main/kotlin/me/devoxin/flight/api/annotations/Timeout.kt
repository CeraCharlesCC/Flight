package me.devoxin.flight.api.annotations

import java.util.concurrent.TimeUnit

/**
 * Declares a maximum execution time for a command handler.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Timeout(
    val duration: Long,
    val timeUnit: TimeUnit = TimeUnit.MILLISECONDS
)
