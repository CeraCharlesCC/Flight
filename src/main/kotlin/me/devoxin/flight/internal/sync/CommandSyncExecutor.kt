package me.devoxin.flight.internal.sync

import me.devoxin.flight.api.sync.CommandSyncResult
import me.devoxin.flight.api.sync.CommandSyncScope
import me.devoxin.flight.api.sync.CommandSyncTargetState
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

internal interface CommandSyncBackend {
    fun updateGlobal(commands: List<CommandData>): CompletableFuture<*>

    fun updateGuild(guildId: Long, commands: List<CommandData>): CompletableFuture<*>
}

internal class JdaCommandSyncBackend(
    private val jda: JDA
) : CommandSyncBackend {
    override fun updateGlobal(commands: List<CommandData>): CompletableFuture<*> {
        return jda.updateCommands()
            .addCommands(commands)
            .submit()
    }

    override fun updateGuild(guildId: Long, commands: List<CommandData>): CompletableFuture<*> {
        val guild = jda.getGuildById(guildId)
            ?: return CompletableFuture.failedFuture<Any>(
                IllegalStateException("Unable to sync guild $guildId because it is not available in the current JDA instance.")
            )

        return guild.updateCommands()
            .addCommands(commands)
            .submit()
    }
}

internal class CommandSyncExecutor(
    private val backend: CommandSyncBackend
) {
    fun execute(plan: PlannedCommandSync): CompletableFuture<CommandSyncResult> {
        if (plan.dryRun) {
            return CompletableFuture.completedFuture(
                CommandSyncResult(
                    dryRun = true,
                    targets = plan.targets.map { target ->
                        when {
                            target.failed.isNotEmpty() -> target.toResult(CommandSyncTargetState.FAILED)
                            target.enabled -> target.toResult(CommandSyncTargetState.DRY_RUN)
                            else -> target.toResult(CommandSyncTargetState.SKIPPED)
                        }
                    }
                )
            )
        }

        var chain = CompletableFuture.completedFuture(mutableListOf<me.devoxin.flight.api.sync.CommandSyncTargetResult>())

        for (target in plan.targets) {
            chain = chain.thenCompose { results ->
                executeTarget(target).thenApply { result ->
                    results += result
                    results
                }
            }
        }

        return chain.thenApply { results ->
            CommandSyncResult(
                dryRun = false,
                targets = results.toList()
            )
        }
    }

    private fun executeTarget(target: PlannedCommandSyncTarget): CompletableFuture<me.devoxin.flight.api.sync.CommandSyncTargetResult> {
        if (target.failed.isNotEmpty()) {
            return CompletableFuture.completedFuture(target.toResult(CommandSyncTargetState.FAILED))
        }

        if (!target.enabled) {
            return CompletableFuture.completedFuture(target.toResult(CommandSyncTargetState.SKIPPED))
        }

        val action = when (val scope = target.scope) {
            CommandSyncScope.Global -> backend.updateGlobal(target.payload)
            is CommandSyncScope.Guild -> backend.updateGuild(scope.guildId, target.payload)
        }

        return action.handle { _, throwable ->
            if (throwable == null) {
                target.toResult(CommandSyncTargetState.SYNCED)
            } else {
                target.toResult(CommandSyncTargetState.FAILED, throwable.unwrapCompletion())
            }
        }
    }
}

private fun Throwable.unwrapCompletion(): Throwable {
    return when (this) {
        is CompletionException -> cause ?: this
        else -> this
    }
}
