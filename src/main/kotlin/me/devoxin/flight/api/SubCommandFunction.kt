package me.devoxin.flight.api

import me.devoxin.flight.api.annotations.SubCommand
import me.devoxin.flight.api.annotations.Timeout
import me.devoxin.flight.api.command.Cog
import me.devoxin.flight.api.context.ContextType
import me.devoxin.flight.internal.arguments.Argument
import me.devoxin.flight.internal.entities.Executable
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter

class SubCommandFunction(
    name: String,
    val properties: SubCommand,
    timeout: Timeout?,
    val parentCommandName: String,
    val groupName: String?,
    val supportedContextTypes: Set<ContextType>,
    // Executable properties
    method: KFunction<*>,
    cog: Cog,
    arguments: List<Argument>,
    contextParameter: KParameter
) : Executable(name, method, cog, arguments, contextParameter, timeout) {
    val normalizedName: String = name.lowercase()
    val normalizedParentCommandName: String = parentCommandName.lowercase()
    val normalizedGroupName: String? = groupName?.lowercase()

    val isGrouped: Boolean
        get() = normalizedGroupName != null

    val isSlashCapable: Boolean
        get() = ContextType.SLASH in supportedContextTypes

    val isPrefixCapable: Boolean
        get() = ContextType.MESSAGE in supportedContextTypes

    fun supportsContext(contextType: ContextType): Boolean {
        return contextType in supportedContextTypes
    }
}
