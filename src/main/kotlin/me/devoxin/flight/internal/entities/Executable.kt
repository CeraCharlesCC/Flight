package me.devoxin.flight.internal.entities

import me.devoxin.flight.api.annotations.Timeout
import me.devoxin.flight.api.command.Cog
import me.devoxin.flight.api.context.Context
import me.devoxin.flight.internal.arguments.Argument
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.callSuspendBy
import kotlin.reflect.full.instanceParameter

abstract class Executable(
    val name: String,
    val method: KFunction<*>,
    val cog: Cog,
    val arguments: List<Argument>,
    val contextParameter: KParameter,
    val timeout: Timeout?
) {
    val isSuspendHandler: Boolean
        get() = method.isSuspend

    fun resolveArguments(options: List<OptionMapping>): HashMap<KParameter, Any?> {
        val mapping = hashMapOf<KParameter, Any?>()

        for (argument in arguments) {
            val option = options.firstOrNull { it.name == argument.slashFriendlyName }

            if (option == null) {
                if (argument.isNullable && !argument.optional) {
                    mapping += argument.parameter to null
                    continue
                }

                if (argument.optional) {
                    continue
                }

                throw IllegalStateException("Missing option for argument ${argument.name}")
            }

            mapping += argument.getEntityFromOptionMapping(option)
        }

        return mapping
    }

    fun bindArguments(
        ctx: Context,
        args: HashMap<KParameter, Any?>
    ): HashMap<KParameter, Any?> {
        return HashMap(args).apply {
            method.instanceParameter?.let { put(it, cog) }
            put(contextParameter, ctx)
        }
    }

    fun invokeBlocking(args: Map<KParameter, Any?>) {
        try {
            method.callBy(args)
        } catch (throwable: Throwable) {
            throw unwrapInvocationFailure(throwable)
        }
    }

    suspend fun invokeSuspend(args: Map<KParameter, Any?>) {
        try {
            method.callSuspendBy(args)
        } catch (throwable: Throwable) {
            throw unwrapInvocationFailure(throwable)
        }
    }

    fun unwrapInvocationFailure(throwable: Throwable): Throwable {
        return throwable.cause ?: throwable
    }
}
