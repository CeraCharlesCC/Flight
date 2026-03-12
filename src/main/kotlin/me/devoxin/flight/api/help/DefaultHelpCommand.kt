package me.devoxin.flight.api.help

import me.devoxin.flight.api.CommandFunction
import me.devoxin.flight.api.annotations.Command
import me.devoxin.flight.api.command.Cog
import me.devoxin.flight.api.context.MessageContext
import me.devoxin.flight.internal.utils.TextUtils

open class DefaultHelpCommand(
    private val showParameterTypes: Boolean,
    private val messages: HelpMessages = HelpMessages()
) : Cog {
    override fun name() = "No Category"

    @Command(aliases = ["commands", "cmds"], description = "Displays bot help.")
    open suspend fun help(ctx: MessageContext, command: String?) {
        val pages = command?.let {
            val commands = ctx.commandClient.commands
            val cmd = commands.findMessageCommand(it)
                ?: commands.findCommandByName(it)?.takeIf(CommandFunction::isPrefixCapable)

            when {
                cmd != null -> buildCommandHelp(ctx, cmd)
                else -> commands.findCogByName(command)?.let { cog -> buildCogHelp(ctx, cog) }
            } ?: return ctx.send(messages.noCommandOrCogFound)

        } ?: buildCommandList(ctx)

        sendPages(ctx, pages)
    }

    open fun buildCommandList(ctx: MessageContext): List<String> {
        val helpMenu = StringBuilder()
        val commands = ctx.commandClient.commands.values.filter { it.isPrefixCapable && !it.properties.hidden }
        if (commands.isEmpty()) {
            return listOf(messages.noCommandOrCogFound)
        }

        val padLength = commands.maxOf { it.name.length }
        val categories = commands.groupBy { it.category.lowercase() }.mapValues { it.value.toSet() }

        for (entry in categories.entries.sortedBy { it.key }) {
            helpMenu.append(TextUtils.toTitleCase(entry.key)).append("\n")

            for (cmd in entry.value.sortedBy { it.name }) {
                val description = cmd.properties.description ?: "No description available"

                helpMenu.apply {
                    append("  ")
                    append(cmd.name.padEnd(padLength + 1, ' '))
                    append(" ")
                    append(TextUtils.truncate(description, 100))
                    append("\n")
                }
            }
        }

        return TextUtils.split(helpMenu.toString().trim(), 1990)
    }

    open fun buildCommandHelp(ctx: MessageContext, command: CommandFunction): List<String> {
        val builder = StringBuilder()

        val trigger =
            if (ctx.trigger.matches("<@!?${ctx.jda.selfUser.id}> ".toRegex())) "@${ctx.jda.selfUser.name} " else ctx.trigger
        builder.append(trigger)

        val properties = command.properties

        if (properties.aliases.isNotEmpty()) {
            builder.append("[")
                .append(command.name)
                .append(properties.aliases.joinToString("|", prefix = "|"))
                .append("] ")
        } else {
            builder.append(command.name).append(" ")
        }

        for (arg in command.arguments) {
            builder.append(arg.format(showParameterTypes)).append(" ")
        }

        builder.append("\n\n").append(properties.description ?: "No description available")
        return listOf(builder.toString())
    }

    open fun buildCogHelp(ctx: MessageContext, cog: Cog): List<String> {
        val title = messages.formatCommandsInCog(cog::class.simpleName ?: "Unknown")
        val builder = StringBuilder(title).append("\n")
        val commands = ctx.commandClient.commands.findCommandsByCog(cog)
            .filter { it.isPrefixCapable && !it.properties.hidden }

        if (commands.isEmpty()) {
            return listOf(title)
        }

        val padLength = commands.maxOf { it.name.length }

        for (command in commands) {
            builder.apply {
                append(" ")
                append(command.name.padEnd(padLength + 1, ' '))
                append(TextUtils.truncate(command.properties.description ?: "No description available", 100))
                append("\n")
            }
        }

        return TextUtils.split(builder.toString(), 1990)
    }

    open suspend fun sendPages(ctx: MessageContext, pages: Collection<String>) {
        for (page in pages) {
            ctx.sendAsync(messages.formatPage(page))
        }
    }
}
