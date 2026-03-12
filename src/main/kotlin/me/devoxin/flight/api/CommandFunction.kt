package me.devoxin.flight.api

import me.devoxin.flight.api.annotations.Cooldown
import me.devoxin.flight.api.annotations.Timeout
import me.devoxin.flight.api.command.CommandProperties
import me.devoxin.flight.api.command.Cog
import me.devoxin.flight.api.context.ContextType
import me.devoxin.flight.internal.arguments.Argument
import me.devoxin.flight.internal.entities.Executable
import me.devoxin.flight.internal.entities.Jar
import net.dv8tion.jda.api.interactions.commands.Command as JdaCommand
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter

class CommandFunction(
    name: String,
    val category: String,
    val properties: CommandProperties,
    val cooldown: Cooldown?,
    timeout: Timeout?,
    val jar: Jar?,
    val guildIds: Set<Long>,
    val supportedContextTypes: Set<ContextType>,
    val applicationCommandType: JdaCommand.Type?,
    val selectedTargetParameter: KParameter?,

    directSubcommands: List<SubCommandFunction>,
    subcommandGroups: List<SubcommandGroupDefinition>,
    // Executable properties
    method: KFunction<*>,
    cog: Cog,
    arguments: List<Argument>,
    contextParameter: KParameter
) : Executable(name, method, cog, arguments, contextParameter, timeout) {
    val normalizedName: String = name.lowercase()
    val directSubcommands: List<SubCommandFunction>
    val subcommandGroups: List<SubcommandGroupDefinition>
    val allSubcommands: List<SubCommandFunction>

    private val directSubcommandLookup: Map<String, SubCommandFunction>
    private val subcommandGroupLookup: Map<String, SubcommandGroupDefinition>

    val isApplicationCommand: Boolean
        get() = applicationCommandType != null

    val isPrefixCapable: Boolean
        get() = ContextType.MESSAGE in supportedContextTypes

    val isSlashCapable: Boolean
        get() = applicationCommandType == JdaCommand.Type.SLASH

    init {
        if (!isSlashCapable && (directSubcommands.isNotEmpty() || subcommandGroups.isNotEmpty())) {
            throw IllegalStateException("Sub-commands can only be declared on slash-capable commands.")
        }

        if (isSlashCapable && arguments.isNotEmpty() && (directSubcommands.isNotEmpty() || subcommandGroups.isNotEmpty())) {
            throw IllegalStateException(
                "Slash command '$name' cannot declare both root command options and subcommands/subcommand groups."
            )
        }

        this.directSubcommands = directSubcommands.sortedBy(SubCommandFunction::name)
        this.subcommandGroups = subcommandGroups.sortedBy(SubcommandGroupDefinition::name)

        val groupLookup = linkedMapOf<String, SubcommandGroupDefinition>()

        for (group in this.subcommandGroups) {
            val existing = groupLookup[group.normalizedName]

            if (existing != null) {
                throw IllegalStateException(
                    "Subcommand group '${group.name}' within command '$name' is already declared."
                )
            }

            val belongsToCommand = group.subcommands.all { it.normalizedParentCommandName == normalizedName }
            if (!belongsToCommand) {
                throw IllegalStateException(
                    "Subcommand group '${group.name}' contains subcommands that do not belong to command '$name'."
                )
            }

            groupLookup[group.normalizedName] = group
        }

        val directLookup = linkedMapOf<String, SubCommandFunction>()

        for (subcommand in this.directSubcommands) {
            if (subcommand.normalizedParentCommandName != normalizedName) {
                throw IllegalStateException(
                    "Direct sub-command '${subcommand.name}' does not belong to command '$name'."
                )
            }

            registerDirectLookup(directLookup, subcommand.name, subcommand)

            for (alias in subcommand.properties.aliases) {
                registerDirectLookup(directLookup, alias.lowercase(), subcommand)
            }
        }

        for (group in this.subcommandGroups) {
            if (directLookup.containsKey(group.normalizedName)) {
                throw IllegalStateException(
                    "The group name '${group.name}' within command '$name' conflicts with a direct sub-command trigger."
                )
            }
        }

        allSubcommands = buildList {
            addAll(this@CommandFunction.directSubcommands)
            this@CommandFunction.subcommandGroups.forEach { addAll(it.subcommands) }
        }

        directSubcommandLookup = directLookup
        subcommandGroupLookup = groupLookup
    }

    fun supportsContext(contextType: ContextType): Boolean {
        return contextType in supportedContextTypes
    }

    fun findSubcommand(name: String?, contextType: ContextType? = null): SubCommandFunction? {
        val subcommand = name
            ?.lowercase()
            ?.let(directSubcommandLookup::get)
            ?: return null

        return subcommand.takeIf { contextType == null || it.supportsContext(contextType) }
    }

    fun findSubcommand(group: String?, name: String?, contextType: ContextType? = null): SubCommandFunction? {
        if (group.isNullOrBlank()) {
            return findSubcommand(name, contextType)
        }

        return subcommandGroupLookup[group.lowercase()]
            ?.findSubcommand(name, contextType)
    }

    fun hasSubcommandGroup(name: String?): Boolean {
        return !name.isNullOrBlank() && subcommandGroupLookup.containsKey(name.lowercase())
    }

    fun findSubcommandPath(
        firstToken: String?,
        secondToken: String?,
        contextType: ContextType? = null
    ): SubCommandFunction? {
        if (firstToken.isNullOrBlank()) {
            return null
        }

        findSubcommand(firstToken, contextType)?.let { return it }

        if (secondToken.isNullOrBlank()) {
            return null
        }

        return subcommandGroupLookup[firstToken.lowercase()]
            ?.findSubcommand(secondToken, contextType)
    }

    private fun registerDirectLookup(
        lookup: MutableMap<String, SubCommandFunction>,
        trigger: String,
        subcommand: SubCommandFunction
    ) {
        val existing = lookup[trigger]

        if (existing != null) {
            throw IllegalStateException(
                "The trigger '$trigger' for direct sub-command '${subcommand.name}' within command '$name' is already assigned to '${existing.name}'!"
            )
        }

        lookup[trigger] = subcommand
    }
}
