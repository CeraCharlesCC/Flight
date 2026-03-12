package me.devoxin.flight.internal.sync

import me.devoxin.flight.api.CommandFunction
import me.devoxin.flight.api.sync.CommandSyncScope

internal object CommandTargetBucketer {
    private val commandOrder = compareBy<CommandFunction>(
        { it.name.lowercase() },
        { it.applicationCommandType?.ordinal ?: Int.MAX_VALUE }
    )

    fun bucket(commands: Collection<CommandFunction>): List<Bucket> {
        if (commands.isEmpty()) {
            return emptyList()
        }

        val sortedCommands = commands.sortedWith(commandOrder)
        val globalCommands = sortedCommands.filter { it.guildIds.isEmpty() }
        val guildBuckets = sortedCommands
            .filter { it.guildIds.isNotEmpty() }
            .flatMap { command -> command.guildIds.map { guildId -> guildId to command } }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()

        return buildList {
            if (globalCommands.isNotEmpty()) {
                add(Bucket(CommandSyncScope.Global, globalCommands))
            }

            for ((guildId, bucketCommands) in guildBuckets) {
                add(Bucket(CommandSyncScope.Guild(guildId), bucketCommands.sortedWith(commandOrder)))
            }
        }
    }

    internal data class Bucket(
        val scope: CommandSyncScope,
        val commands: List<CommandFunction>
    )
}