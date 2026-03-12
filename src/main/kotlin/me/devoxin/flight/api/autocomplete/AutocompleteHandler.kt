package me.devoxin.flight.api.autocomplete

import me.devoxin.flight.api.command.Cog
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import kotlin.reflect.KClass
import kotlin.reflect.full.allSupertypes
import kotlin.reflect.jvm.jvmErasure

/**
 * Typed handler contract for slash-command autocomplete.
 *
 * Handlers receive the concrete cog instance that declared the parameter as well as the
 * autocomplete event. Implementations may be Kotlin [object] singletons or classes with a no-arg constructor.
 */
interface AutocompleteHandler<C : Cog> {
    suspend fun complete(cog: C, event: CommandAutoCompleteInteractionEvent)
}

internal suspend fun AutocompleteHandler<*>.completeUnchecked(
    cog: Cog,
    event: CommandAutoCompleteInteractionEvent
) {
    @Suppress("UNCHECKED_CAST")
    (this as AutocompleteHandler<Cog>).complete(cog, event)
}

internal fun expectedCogTypeOf(handlerType: KClass<out AutocompleteHandler<*>>): KClass<out Cog>? {
    val autocompleteSupertype = handlerType.allSupertypes.firstOrNull {
        it.jvmErasure == AutocompleteHandler::class
    } ?: return null

    val projectedType = autocompleteSupertype.arguments.firstOrNull()?.type ?: return null
    val jvmErasure = projectedType.jvmErasure

    @Suppress("UNCHECKED_CAST")
    return jvmErasure.takeIf { Cog::class.java.isAssignableFrom(it.java) } as? KClass<out Cog>
}
