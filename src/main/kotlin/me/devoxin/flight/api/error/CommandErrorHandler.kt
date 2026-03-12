package me.devoxin.flight.api.error

fun interface CommandErrorHandler {
    fun handle(failure: CommandFailure)
}
