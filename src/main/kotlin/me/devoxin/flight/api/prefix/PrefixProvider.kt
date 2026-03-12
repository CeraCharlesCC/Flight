package me.devoxin.flight.api.prefix

import net.dv8tion.jda.api.entities.Message

/**
 * Supplies candidate command prefixes for a message.
 *
 * Flight resolves prefixes by selecting the longest candidate that matches the message content.
 * Provider order is only used as a deterministic tie-breaker when multiple matching prefixes have
 * the same length.
 */
interface PrefixProvider {
    fun provide(message: Message): List<String>
}
