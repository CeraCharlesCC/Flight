package me.devoxin.flight.api.sync

import me.devoxin.flight.api.localization.CommandLocalizationProvider
import net.dv8tion.jda.api.interactions.commands.Command as JdaCommand

/**
 * Options used when planning or executing a command sync.
 *
 * Sync is authoritative per targeted scope. Any targeted global scope or guild scope is treated as the full desired
 * application-command set for that scope, including when the resulting payload is empty.
 */
data class CommandSyncOptions(
    val dryRun: Boolean = false,
    val includeGlobal: Boolean = true,
    val guildIds: Set<Long>? = null,
    val localizationProvider: CommandLocalizationProvider? = null
)

/**
 * A command-sync scope.
 */
sealed interface CommandSyncScope {
    /**
     * The global application-command scope.
     */
    data object Global : CommandSyncScope

    /**
     * A guild-specific application-command scope.
     */
    data class Guild(val guildId: Long) : CommandSyncScope
}

/**
 * A lightweight description of a command considered for sync.
 */
data class CommandSyncSubcommandGroup(
    val name: String,
    val subcommands: List<String>
)

/**
 * A lightweight description of a command considered for sync.
 */
data class CommandSyncCommand(
    val name: String,
    val commandType: JdaCommand.Type?,
    val description: String?,
    val guildIds: Set<Long> = emptySet(),
    val directSubcommands: List<String> = emptyList(),
    val subcommandGroups: List<CommandSyncSubcommandGroup> = emptyList()
)

/**
 * Known reasons why a command was skipped for a sync target.
 */
enum class CommandSyncSkipReason {
    NOT_APPLICATION_COMMAND,
    FILTERED_OUT,
    TARGET_ABORTED
}

/**
 * A command that was skipped for a target scope.
 */
data class CommandSyncSkippedCommand(
    val command: CommandSyncCommand,
    val reason: CommandSyncSkipReason,
    val detail: String? = null
)

/**
 * A command that failed local planning for a target scope.
 */
data class CommandSyncFailedCommand(
    val command: CommandSyncCommand,
    val message: String,
    val cause: Throwable? = null
)

/**
 * The planned payload and diagnostics for one sync target.
 */
data class CommandSyncTargetPlan(
    val scope: CommandSyncScope,
    val considered: List<CommandSyncCommand>,
    val emitted: List<CommandSyncCommand>,
    val skipped: List<CommandSyncSkippedCommand>,
    val failed: List<CommandSyncFailedCommand>
)

/**
 * A dry representation of the command sync that Flight intends to perform.
 */
data class CommandSyncPlan(
    val dryRun: Boolean,
    val targets: List<CommandSyncTargetPlan>
) {
    val consideredCount: Int
        get() = targets.sumOf { it.considered.size }

    val emittedCount: Int
        get() = targets.sumOf { it.emitted.size }

    val skippedCount: Int
        get() = targets.sumOf { it.skipped.size }

    val failedCount: Int
        get() = targets.sumOf { it.failed.size }
}

/**
 * The execution status for one sync target.
 */
enum class CommandSyncTargetState {
    DRY_RUN,
    SKIPPED,
    SYNCED,
    FAILED
}

/**
 * The final result for one sync target.
 */
data class CommandSyncTargetResult(
    val scope: CommandSyncScope,
    val considered: List<CommandSyncCommand>,
    val emitted: List<CommandSyncCommand>,
    val skipped: List<CommandSyncSkippedCommand>,
    val failed: List<CommandSyncFailedCommand>,
    val state: CommandSyncTargetState,
    val executionError: Throwable? = null
)

/**
 * The result of a command sync execution.
 */
data class CommandSyncResult(
    val dryRun: Boolean,
    val targets: List<CommandSyncTargetResult>
) {
    val consideredCount: Int
        get() = targets.sumOf { it.considered.size }

    val emittedCount: Int
        get() = targets.sumOf { it.emitted.size }

    val skippedCount: Int
        get() = targets.sumOf { it.skipped.size }

    val failedCount: Int
        get() = targets.sumOf { it.failed.size + if (it.executionError != null) 1 else 0 }
}
