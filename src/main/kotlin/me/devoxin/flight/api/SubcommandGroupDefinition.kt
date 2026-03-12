package me.devoxin.flight.api

import me.devoxin.flight.api.context.ContextType

/**
 * An in-memory subcommand group definition owned by a top-level slash command.
 */
class SubcommandGroupDefinition(
    name: String,
    val description: String,
    children: List<SubCommandFunction>
) {
    val name: String = name.lowercase()
    val normalizedName: String = this.name
    val subcommands: List<SubCommandFunction>

    private val subcommandLookup: Map<String, SubCommandFunction>

    init {
        require(this.name.isNotBlank()) { "Subcommand group names cannot be blank." }
        require(subcommandLookupSeed(children).isNotEmpty()) {
            "Subcommand group '$name' must declare at least one subcommand."
        }

        subcommands = children.sortedBy(SubCommandFunction::name)
        subcommandLookup = subcommandLookupSeed(subcommands)
    }

    fun findSubcommand(trigger: String?, contextType: ContextType? = null): SubCommandFunction? {
        val subcommand = trigger
            ?.lowercase()
            ?.let(subcommandLookup::get)
            ?: return null

        return subcommand.takeIf { contextType?.let(it::supportsContext) != false }
    }

    private fun subcommandLookupSeed(children: List<SubCommandFunction>): Map<String, SubCommandFunction> {
        val lookup = linkedMapOf<String, SubCommandFunction>()

        for (subcommand in children.sortedBy(SubCommandFunction::name)) {
            registerLookup(lookup, subcommand.name, subcommand)

            for (alias in subcommand.properties.aliases) {
                registerLookup(lookup, alias.lowercase(), subcommand)
            }
        }

        return lookup
    }

    private fun registerLookup(
        lookup: MutableMap<String, SubCommandFunction>,
        trigger: String,
        subcommand: SubCommandFunction
    ) {
        val existing = lookup[trigger]

        if (existing != null) {
            throw IllegalStateException(
                "The trigger '$trigger' for grouped sub-command '${subcommand.name}' within group '$name' is already assigned to '${existing.name}'!"
            )
        }

        lookup[trigger] = subcommand
    }
}
