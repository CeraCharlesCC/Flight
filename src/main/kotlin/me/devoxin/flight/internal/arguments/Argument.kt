package me.devoxin.flight.internal.arguments

import kotlinx.coroutines.runBlocking
import me.devoxin.flight.api.annotations.Choices
import me.devoxin.flight.api.annotations.Describe
import me.devoxin.flight.api.annotations.Range
import me.devoxin.flight.api.autocomplete.AutocompleteHandler
import me.devoxin.flight.api.autocomplete.completeUnchecked
import me.devoxin.flight.api.command.Cog
import me.devoxin.flight.internal.utils.EnumUtils
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.interactions.commands.Command.Choice
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import kotlin.reflect.KParameter

class Argument(
    /** The argument's parameter name */
    val name: String,
    /** The argument's description, as given in the [Describe] annotation */
    val description: String,
    val range: Range?,
    val choices: Choices?,
    /** The parameter type for this argument */
    val type: Class<*>,
    val greedy: Boolean,
    val optional: Boolean, // Denotes that a parameter has a default value.
    val isNullable: Boolean,
    val isTentative: Boolean,
    val autocompleteHandler: AutocompleteHandler<*>?,
    internal val cog: Cog,
    val parameter: KParameter
) {
    val slashFriendlyName = name.replace(SLASH_NAME_REGEX, "_$1").lowercase()
    val autocompleteSupported = autocompleteHandler != null
    val isEnum = type.isEnum
    val isSuspendAutocompleteHandler: Boolean
        get() = autocompleteHandler != null

    /**
     * Returns this argument as a [Pair]<[OptionType], [Boolean]>. The [OptionType] represents the
     * type of this argument. The [Boolean] represents whether the argument is required. True if it
     * is, false otherwise.
     */
    fun asSlashCommandType(): OptionData {
        val binding = SlashOptionSupport.bindingFor(type)

        val option =
            OptionData(
                binding.optionType,
                slashFriendlyName,
                description,
                !isNullable && !optional,
                autocompleteSupported
            )

        if (binding.channelTypes.isNotEmpty()) {
            option.setChannelTypes(binding.channelTypes)
        }

        range?.let {
            it.double.takeIf(DoubleArray::isNotEmpty)?.let { range ->
                option.setMinValue(range[0])
                range.elementAtOrNull(1)?.let(option::setMaxValue)
            }

            it.long.takeIf(LongArray::isNotEmpty)?.let { range ->
                option.setMinValue(range[0])
                range.elementAtOrNull(1)?.let(option::setMaxValue)
            }

            it.string.takeIf(IntArray::isNotEmpty)?.let { range ->
                option.setMinLength(range[0])
                range.elementAtOrNull(1)?.let(option::setMaxLength)
            }
        }

        choices?.let { choices ->
            choices.double.takeIf { it.isNotEmpty() }?.let {
                option.addChoices(it.map { c -> Choice(c.key, c.value) })
            }
            choices.long.takeIf { it.isNotEmpty() }?.let {
                option.addChoices(it.map { c -> Choice(c.key, c.value) })
            }
            choices.string.takeIf { it.isNotEmpty() }?.let {
                option.addChoices(it.map { c -> Choice(c.key, c.value) })
            }
        }

        // Auto-generate choices for enum types if no explicit choices were provided
        if (isEnum && choices == null) {
            val enumChoices = EnumUtils.getEnumChoices(type)
            option.addChoices(enumChoices.map { Choice(it.key, it.value) })
        }

        return option
    }

    fun getEntityFromOptionMapping(mapping: OptionMapping): Pair<KParameter, Any?> {
        return parameter to SlashOptionSupport.resolve(type, mapping)
    }

    fun format(withType: Boolean): String {
        return buildString {
            if (optional || isNullable) {
                append('[')
            } else {
                append('<')
            }

            append(name)

            if (withType) {
                append(": ")
                append(type.simpleName)
            }

            if (optional || isNullable) {
                append(']')
            } else {
                append('>')
            }
        }
    }

    fun invokeAutocomplete(event: CommandAutoCompleteInteractionEvent) {
        runBlocking {
            invokeAutocompleteSuspend(event)
        }
    }

    suspend fun invokeAutocompleteSuspend(event: CommandAutoCompleteInteractionEvent) {
        val handler = autocompleteHandler
            ?: throw IllegalStateException(
                "Cannot process autocomplete event as $name does not have a registered handler!"
            )

        try {
            handler.completeUnchecked(cog, event)
        } catch (throwable: Throwable) {
            throw unwrapInvocationFailure(throwable)
        }
    }

    fun unwrapInvocationFailure(throwable: Throwable): Throwable {
        return throwable.cause ?: throwable
    }

    companion object {
        val SLASH_NAME_REGEX = "((?<=[a-z])[A-Z]|[A-Z](?=[a-z]))".toRegex()
    }
}
