package me.devoxin.flight.api.command

import me.devoxin.flight.api.CommandFunction
import me.devoxin.flight.api.localization.CommandLocalizationProvider
import me.devoxin.flight.api.sync.CommandSyncScope
import me.devoxin.flight.api.util.ObjectStorage
import me.devoxin.flight.internal.entities.Jar
import me.devoxin.flight.internal.sync.CommandDataFactory
import me.devoxin.flight.internal.sync.CommandTargetBucketer
import me.devoxin.flight.internal.utils.CogCommandLoader
import me.devoxin.flight.internal.utils.Indexer
import net.dv8tion.jda.api.interactions.commands.Command as JdaCommand
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import org.slf4j.LoggerFactory

class CommandRegistry {
    val objectStorage = ObjectStorage()

    private val cogCommandLoader = CogCommandLoader()

    private val registeredCommands = mutableListOf<CommandFunction>()
    private val commandsByName = linkedMapOf<String, MutableList<CommandFunction>>()
    private val messageTriggers = linkedMapOf<String, CommandFunction>()
    private val applicationCommands = linkedMapOf<ApplicationCommandKey, CommandFunction>()

    val values: List<CommandFunction>
        get() = registeredCommands.toList()

    val size: Int
        get() = registeredCommands.size

    operator fun get(name: String): CommandFunction? {
        return findCommandByName(name)
    }

    fun containsKey(name: String): Boolean {
        return findCommandByName(name) != null
    }

    /**
     * Returns the globally scoped application commands as [CommandData].
     */
    fun toDiscordCommands(
        localizationProvider: CommandLocalizationProvider? = null
    ): List<CommandData> {
        return toDiscordCommandTargets(localizationProvider)
            .firstOrNull { it.scope == CommandSyncScope.Global }
            ?.commands
            .orEmpty()
    }

    /**
     * Returns the registered application commands grouped by their natural deployment scope.
     */
    fun toDiscordCommandTargets(
        localizationProvider: CommandLocalizationProvider? = null
    ): List<DiscordCommandTarget> {
        return CommandTargetBucketer.bucket(registeredCommands).mapNotNull { bucket ->
            val payload = bucket.commands.asSequence()
                .filter(CommandFunction::isApplicationCommand)
                .map { toCommandData(it, localizationProvider) }
                .toList()

            payload.takeIf { it.isNotEmpty() }
                ?.let { DiscordCommandTarget(bucket.scope, it) }
        }
    }

    fun toCommandData(
        command: CommandFunction,
        localizationProvider: CommandLocalizationProvider? = null
    ): CommandData {
        return CommandDataFactory.createCommandData(command, localizationProvider)
    }

    fun clear() {
        val cogs = registeredCommands.map(CommandFunction::cog)
        registeredCommands.clear()
        commandsByName.clear()
        messageTriggers.clear()
        applicationCommands.clear()
        doUnload(cogs)
    }

    fun findCommandByName(name: String): CommandFunction? {
        val matches = commandsByName[name.lowercase()].orEmpty()
        return matches.firstOrNull(CommandFunction::isPrefixCapable)
            ?: matches.firstOrNull { it.isSlashCapable }
            ?: matches.firstOrNull()
    }

    fun findCommandByAlias(alias: String): CommandFunction? {
        val normalized = alias.lowercase()
        return messageTriggers[normalized]?.takeIf { normalized in it.properties.aliases }
    }

    fun findMessageCommand(trigger: String): CommandFunction? {
        return messageTriggers[trigger.lowercase()]
    }

    fun findApplicationCommand(name: String, type: JdaCommand.Type): CommandFunction? {
        return applicationCommands[ApplicationCommandKey.of(name, type)]
    }

    fun findSlashCommand(name: String): CommandFunction? {
        return findApplicationCommand(name, JdaCommand.Type.SLASH)
    }

    fun findUserCommand(name: String): CommandFunction? {
        return findApplicationCommand(name, JdaCommand.Type.USER)
    }

    fun findMessageContextCommand(name: String): CommandFunction? {
        return findApplicationCommand(name, JdaCommand.Type.MESSAGE)
    }

    fun findCogByName(name: String): Cog? {
        return registeredCommands.firstOrNull { it.cog.name() == name || it.cog::class.simpleName == name }?.cog
    }

    fun findCommandsByCog(cog: Cog): List<CommandFunction> {
        return registeredCommands.filter { it.cog == cog }
    }

    fun unload(commandFunction: CommandFunction) {
        registeredCommands.remove(commandFunction)
        rebuildIndexes()
        doUnload(commandFunction.cog)
    }

    fun unload(cog: Cog) {
        val commands = registeredCommands.filter { it.cog == cog }
        registeredCommands.removeAll(commands)
        rebuildIndexes()

        commands.map(CommandFunction::cog).let(::doUnload)

        val jar = commands.firstOrNull { it.jar != null }?.jar
            ?: return

        val canCloseLoader = registeredCommands.none { it.jar == jar }

        if (canCloseLoader) {
            jar.close()
        }
    }

    fun unload(jar: Jar) {
        val commands = registeredCommands.filter { it.jar == jar }
        registeredCommands.removeAll(commands)
        rebuildIndexes()

        commands.map(CommandFunction::cog).let(::doUnload)

        jar.close()
    }

    fun register(packageName: String) {
        val indexer = Indexer(packageName)

        for (cog in indexer.getCogs(objectStorage)) {
            register(cog, indexer)
        }
    }

    fun register(jarPath: String, packageName: String) {
        val indexer = Indexer(packageName, jarPath)

        for (cog in indexer.getCogs(objectStorage)) {
            register(cog, indexer)
        }
    }

    fun register(vararg cogs: Cog) {
        registerAll(cogs.asList())
    }

    fun registerAll(cogs: Iterable<Cog>) {
        for (cog in cogs) {
            register(cog)
        }
    }

    fun register(cog: Cog, indexer: Indexer? = null) {
        try {
            val commands = indexer?.loadCommands(cog) ?: cogCommandLoader.loadCommands(cog)
            registerAtomically(commands)
        } catch (t: Throwable) {
            throw wrapCogRegistrationFailure(cog, t)
        }
    }

    private fun registerAtomically(commands: List<CommandFunction>) {
        validateRegistration(commands)

        for (command in commands) {
            registeredCommands += command
            index(command)
        }
    }

    private fun validateRegistration(commands: Iterable<CommandFunction>) {
        val stagedMessageTriggers = LinkedHashMap(messageTriggers)
        val stagedApplicationCommands = LinkedHashMap(applicationCommands)

        for (command in commands) {
            validateRegistration(command, stagedMessageTriggers, stagedApplicationCommands)
            stageIndexes(command, stagedMessageTriggers, stagedApplicationCommands)
        }
    }

    private fun validateRegistration(
        command: CommandFunction,
        stagedMessageTriggers: Map<String, CommandFunction>,
        stagedApplicationCommands: Map<ApplicationCommandKey, CommandFunction>
    ) {
        if (command.isPrefixCapable) {
            val triggers = linkedSetOf(command.normalizedName)
            triggers += command.properties.aliases

            for (trigger in triggers) {
                val existing = stagedMessageTriggers[trigger]

                if (existing != null) {
                    throw RuntimeException(
                        "Cannot register command ${command.name} because the message trigger '$trigger' is already registered to ${existing.name}."
                    )
                }
            }
        }

        command.applicationCommandType?.let { type ->
            val key = ApplicationCommandKey.of(command.name, type)
            val existing = stagedApplicationCommands[key]

            if (existing != null) {
                throw RuntimeException(
                    "Cannot register command ${command.name} because the ${type.name.lowercase()} application command name is already registered."
                )
            }

            try {
                CommandDataFactory.createCommandData(command, null)
            } catch (t: Throwable) {
                throw IllegalStateException(
                    "Failed to register application command '${command.name}': ${t.message}",
                    t
                )
            }
        }
    }

    private fun stageIndexes(
        command: CommandFunction,
        stagedMessageTriggers: MutableMap<String, CommandFunction>,
        stagedApplicationCommands: MutableMap<ApplicationCommandKey, CommandFunction>
    ) {
        if (command.isPrefixCapable) {
            stagedMessageTriggers[command.normalizedName] = command

            for (alias in command.properties.aliases) {
                stagedMessageTriggers[alias] = command
            }
        }

        command.applicationCommandType?.let { type ->
            stagedApplicationCommands[ApplicationCommandKey.of(command.name, type)] = command
        }
    }

    private fun wrapCogRegistrationFailure(cog: Cog, throwable: Throwable): IllegalStateException {
        val cause = throwable.cause ?: throwable

        return IllegalStateException(
            "Failed to register cog ${describeCog(cog)}: ${cause.message ?: cause::class.simpleName}",
            throwable
        )
    }

    private fun describeCog(cog: Cog): String {
        val className = cog::class.qualifiedName ?: cog::class.simpleName ?: "UnknownCog"
        return "$className ($cog)"
    }

    private fun index(command: CommandFunction) {
        commandsByName.getOrPut(command.normalizedName) { mutableListOf() } += command

        if (command.isPrefixCapable) {
            messageTriggers[command.normalizedName] = command

            for (alias in command.properties.aliases) {
                messageTriggers[alias] = command
            }
        }

        command.applicationCommandType?.let { type ->
            applicationCommands[ApplicationCommandKey.of(command.name, type)] = command
        }
    }

    private fun rebuildIndexes() {
        commandsByName.clear()
        messageTriggers.clear()
        applicationCommands.clear()

        registeredCommands.forEach(::index)
    }

    private fun doUnload(cogs: Iterable<Cog>) {
        val uniqueCogs = cogs.distinctBy(Cog::name)

        for (cog in uniqueCogs) {
            doUnload(cog)
        }
    }

    private fun doUnload(cog: Cog) {
        try {
            cog.unload()
        } catch (t: Throwable) {
            log.error("An error occurred whilst unloading cog \"{}\"", cog.name() ?: cog::class.java.simpleName, t)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(CommandRegistry::class.java)
    }
}
