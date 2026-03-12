package me.devoxin.flight.internal.sync

import me.devoxin.flight.api.CommandFunction
import me.devoxin.flight.api.command.CommandRegistry
import me.devoxin.flight.api.localization.CommandLocalizationProvider
import me.devoxin.flight.api.sync.CommandSyncCommand
import me.devoxin.flight.api.sync.CommandSyncFailedCommand
import me.devoxin.flight.api.sync.CommandSyncOptions
import me.devoxin.flight.api.sync.CommandSyncPlan
import me.devoxin.flight.api.sync.CommandSyncScope
import me.devoxin.flight.api.sync.CommandSyncSkipReason
import me.devoxin.flight.api.sync.CommandSyncSkippedCommand
import me.devoxin.flight.api.sync.CommandSyncSubcommandGroup
import me.devoxin.flight.api.sync.CommandSyncTargetPlan
import me.devoxin.flight.api.sync.CommandSyncTargetResult
import me.devoxin.flight.api.sync.CommandSyncTargetState
import net.dv8tion.jda.api.interactions.commands.build.CommandData

internal class CommandSyncPlanner(
    private val registry: CommandRegistry,
    private val defaultLocalizationProvider: CommandLocalizationProvider? = null
) {
    fun plan(options: CommandSyncOptions = CommandSyncOptions()): PlannedCommandSync {
        val localizationProvider = options.localizationProvider ?: defaultLocalizationProvider
        val naturalTargets = CommandTargetBucketer.bucket(registry.values)
        val targets = mutableListOf<PlannedCommandSyncTarget>()

        val globalCommands = naturalTargets
            .firstOrNull { it.scope == CommandSyncScope.Global }
            ?.commands
            .orEmpty()

        if (options.includeGlobal || globalCommands.isNotEmpty()) {
            targets += planTarget(
                scope = CommandSyncScope.Global,
                commands = globalCommands,
                enabled = options.includeGlobal,
                localizationProvider = localizationProvider,
                filterDetail = "Global command sync was disabled for this run."
            )
        }

        val allowedGuildIds = options.guildIds?.toSet()
        val guildTargets = naturalTargets
            .mapNotNull { bucket ->
                val scope = bucket.scope as? CommandSyncScope.Guild
                    ?: return@mapNotNull null

                scope.guildId to bucket.commands
            }
            .toMap()

        val targetGuildIds = (guildTargets.keys + (allowedGuildIds ?: emptySet())).toSortedSet()

        for (guildId in targetGuildIds) {
            val guildCommands = guildTargets[guildId].orEmpty()
            targets += planTarget(
                scope = CommandSyncScope.Guild(guildId),
                commands = guildCommands,
                enabled = allowedGuildIds == null || guildId in allowedGuildIds,
                localizationProvider = localizationProvider,
                filterDetail = "Guild $guildId was filtered out for this run."
            )
        }

        return PlannedCommandSync(options.dryRun, targets)
    }

    private fun planTarget(
        scope: CommandSyncScope,
        commands: List<CommandFunction>,
        enabled: Boolean,
        localizationProvider: CommandLocalizationProvider?,
        filterDetail: String
    ): PlannedCommandSyncTarget {
        val considered = commands.map(::toSyncCommand)

        if (!enabled) {
            return PlannedCommandSyncTarget(
                scope = scope,
                payload = emptyList(),
                considered = considered,
                emitted = emptyList(),
                skipped = considered.map {
                    CommandSyncSkippedCommand(it, CommandSyncSkipReason.FILTERED_OUT, filterDetail)
                },
                failed = emptyList(),
                enabled = false
            )
        }

        val emitted = mutableListOf<CommandSyncCommand>()
        val skipped = mutableListOf<CommandSyncSkippedCommand>()
        val failed = mutableListOf<CommandSyncFailedCommand>()
        val payload = mutableListOf<CommandData>()

        for (command in commands) {
            val syncCommand = toSyncCommand(command)

            if (!command.isApplicationCommand) {
                skipped += CommandSyncSkippedCommand(
                    command = syncCommand,
                    reason = CommandSyncSkipReason.NOT_APPLICATION_COMMAND,
                    detail = "${command.name} cannot be synced because it is not an application command."
                )
                continue
            }

            try {
                payload += CommandDataFactory.createCommandData(command, localizationProvider)
                emitted += syncCommand
            } catch (t: Throwable) {
                failed += CommandSyncFailedCommand(
                    command = syncCommand,
                    message = "Failed to build application-command data for ${command.name}.",
                    cause = t
                )
            }
        }

        if (failed.isNotEmpty()) {
            skipped += emitted.map {
                CommandSyncSkippedCommand(
                    command = it,
                    reason = CommandSyncSkipReason.TARGET_ABORTED,
                    detail = "${scope.label()} was not synced because one or more commands failed to build."
                )
            }
            emitted.clear()
            payload.clear()
        }

        return PlannedCommandSyncTarget(
            scope = scope,
            payload = payload.toList(),
            considered = considered,
            emitted = emitted.toList(),
            skipped = skipped.toList(),
            failed = failed.toList(),
            enabled = true
        )
    }

    private fun toSyncCommand(command: CommandFunction): CommandSyncCommand {
        return CommandSyncCommand(
            name = command.name,
            commandType = command.applicationCommandType,
            description = command.properties.description,
            guildIds = command.guildIds,
            directSubcommands = command.directSubcommands.map { it.name },
            subcommandGroups = command.subcommandGroups.map { group ->
                CommandSyncSubcommandGroup(
                    name = group.name,
                    subcommands = group.subcommands.map { it.name }
                )
            }
        )
    }
}

internal data class PlannedCommandSync(
    val dryRun: Boolean,
    val targets: List<PlannedCommandSyncTarget>
) {
    fun toPlan(): CommandSyncPlan {
        return CommandSyncPlan(
            dryRun = dryRun,
            targets = targets.map(PlannedCommandSyncTarget::toPlan)
        )
    }
}

internal data class PlannedCommandSyncTarget(
    val scope: CommandSyncScope,
    val payload: List<CommandData>,
    val considered: List<CommandSyncCommand>,
    val emitted: List<CommandSyncCommand>,
    val skipped: List<CommandSyncSkippedCommand>,
    val failed: List<CommandSyncFailedCommand>,
    val enabled: Boolean = true
) {
    fun toPlan(): CommandSyncTargetPlan {
        return CommandSyncTargetPlan(
            scope = scope,
            considered = considered,
            emitted = emitted,
            skipped = skipped,
            failed = failed
        )
    }

    fun toResult(
        state: CommandSyncTargetState,
        executionError: Throwable? = null
    ): CommandSyncTargetResult {
        return CommandSyncTargetResult(
            scope = scope,
            considered = considered,
            emitted = emitted,
            skipped = skipped,
            failed = failed,
            state = state,
            executionError = executionError
        )
    }
}

private fun CommandSyncScope.label(): String {
    return when (this) {
        CommandSyncScope.Global -> "Global scope"
        is CommandSyncScope.Guild -> "Guild $guildId"
    }
}
