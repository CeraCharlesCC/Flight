package me.devoxin.flight.api

import me.devoxin.flight.api.arguments.types.Invite
import me.devoxin.flight.api.arguments.types.Snowflake
import me.devoxin.flight.api.cooldown.CooldownProvider
import me.devoxin.flight.api.cooldown.DefaultCooldownProvider
import me.devoxin.flight.api.command.Cog
import me.devoxin.flight.api.help.DefaultHelpCommand
import me.devoxin.flight.api.help.DefaultHelpCommandConfig
import me.devoxin.flight.api.execution.CommandExecutionOptions
import me.devoxin.flight.api.error.CommandErrorHandler
import me.devoxin.flight.api.error.StandardCommandErrorHandler
import me.devoxin.flight.api.error.StandardCommandErrorHandlerConfig
import me.devoxin.flight.api.hooks.CommandEventAdapter
import me.devoxin.flight.api.hooks.DefaultCommandEventAdapter
import me.devoxin.flight.api.localization.CommandLocalizationProvider
import me.devoxin.flight.api.prefix.DefaultPrefixProvider
import me.devoxin.flight.api.prefix.PrefixProvider
import me.devoxin.flight.internal.arguments.ArgParser
import me.devoxin.flight.internal.parsers.*
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.emoji.CustomEmoji
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.entities.emoji.EmojiUnion
import net.dv8tion.jda.api.entities.emoji.UnicodeEmoji
import java.net.URL

class CommandClientBuilder {
    private var prefixes: List<String> = emptyList()
    private var allowMentionPrefix: Boolean = true
    private var helpCommandConfig: DefaultHelpCommandConfig = DefaultHelpCommandConfig()
    private var ignoreBots: Boolean = true
    private var prefixProvider: PrefixProvider? = null
    private var cooldownProvider: CooldownProvider? = null
    private var commandLocalizationProvider: CommandLocalizationProvider? = null
    private var executionOptions: CommandExecutionOptions = CommandExecutionOptions()
    private var errorHandler: CommandErrorHandler? = null
    private var eventListeners: MutableList<CommandEventAdapter> = mutableListOf()
    private val pendingCogs: MutableList<Cog> = mutableListOf()
    private val ownerIds: MutableSet<Long> = mutableSetOf()


    /**
     * Strings that messages must start with to trigger the bot.
     *
     * @return The builder instance. Useful for chaining.
     */
    fun setPrefixes(prefixes: List<String>): CommandClientBuilder {
        this.prefixes = prefixes
        return this
    }

    /**
     * Strings that messages must start with to trigger the bot.
     *
     * @return The builder instance. Useful for chaining.
     */
    fun setPrefixes(vararg prefixes: String): CommandClientBuilder {
        this.prefixes = prefixes.toList()
        return this
    }

    /**
     * Sets the provider used for obtaining prefixes
     */
    fun setPrefixProvider(provider: PrefixProvider): CommandClientBuilder {
        this.prefixProvider = provider
        return this
    }

    /**
     * Sets the provider used for cool-downs.
     */
    fun setCooldownProvider(provider: CooldownProvider): CommandClientBuilder {
        this.cooldownProvider = provider
        return this
    }

    /**
     * Sets the default localization provider used for application-command export and sync.
     */
    fun setCommandLocalizationProvider(provider: CommandLocalizationProvider?): CommandClientBuilder {
        this.commandLocalizationProvider = provider
        return this
    }

    /**
     * Whether the bot will allow mentions to be used as a prefix.
     *
     * @return The builder instance. Useful for chaining.
     */
    fun setAllowMentionPrefix(allowMentionPrefix: Boolean): CommandClientBuilder {
        this.allowMentionPrefix = allowMentionPrefix
        return this
    }

    /**
     * Whether the default help command should be used or not.
     *
     * @return The builder instance. Useful for chaining.
     */
    fun configureDefaultHelpCommand(config: DefaultHelpCommandConfig.() -> Unit): CommandClientBuilder {
        config(helpCommandConfig)
        return this
    }

    /**
     * Whether bots and webhooks should be ignored. The recommended option is true to prevent feedback loops.
     *
     * @return The builder instance. Useful for chaining.
     */
    fun setIgnoreBots(ignoreBots: Boolean): CommandClientBuilder {
        this.ignoreBots = ignoreBots
        return this
    }

    /**
     * Uses the given list of IDs as the owners. Any users with the given IDs
     * are then able to use commands marked with `developerOnly`.
     *
     * @return The builder instance. Useful for chaining.
     */
    fun setOwnerIds(vararg ids: Long): CommandClientBuilder {
        this.ownerIds.clear()
        this.ownerIds.addAll(ids.toTypedArray())
        return this
    }

    /**
     * Uses the given list of IDs as the owners. Any users with the given IDs
     * are then able to use commands marked with `developerOnly`.
     *
     * @return The builder instance. Useful for chaining.
     */
    fun setOwnerIds(vararg ids: String): CommandClientBuilder {
        this.ownerIds.clear()
        this.ownerIds.addAll(ids.map(String::toLong))
        return this
    }

    /**
     * Registers the provided listeners to make use of hooks
     *
     * @return The builder instance. Useful for chaining.
     */
    fun addEventListeners(vararg listeners: CommandEventAdapter): CommandClientBuilder {
        this.eventListeners.addAll(listeners)
        return this
    }

    /**
     * Queues an already-constructed cog instance for registration during [build].
     *
     * This is the primary DI-friendly registration path when your cogs are created by a container,
     * factory, or other application bootstrap logic.
     */
    fun register(cog: Cog): CommandClientBuilder {
        pendingCogs += cog
        return this
    }

    /**
     * Queues multiple already-constructed cog instances for registration during [build].
     */
    fun register(vararg cogs: Cog): CommandClientBuilder {
        pendingCogs += cogs
        return this
    }

    /**
     * Queues multiple already-constructed cog instances from an [Iterable] for registration during [build].
     */
    fun registerAll(cogs: Iterable<Cog>): CommandClientBuilder {
        pendingCogs += cogs
        return this
    }

    /**
     * Replaces the execution options used for command and autocomplete handling.
     */
    fun setExecutionOptions(options: CommandExecutionOptions): CommandClientBuilder {
        this.executionOptions = options
        return this
    }

    /**
     * Configures coroutine execution for command and autocomplete handling.
     */
    fun configureExecution(configure: CommandExecutionOptions.Builder.() -> Unit): CommandClientBuilder {
        this.executionOptions = executionOptions.toBuilder()
            .apply(configure)
            .build()
        return this
    }

    /**
     * Sets the centralized error handler used for normalized command failures.
     */
    fun setErrorHandler(handler: CommandErrorHandler?): CommandClientBuilder {
        this.errorHandler = handler
        return this
    }

    /**
     * Installs the stock centralized error handler.
     */
    fun useDefaultErrorHandler(
        configure: StandardCommandErrorHandlerConfig.() -> Unit = {}
    ): CommandClientBuilder {
        this.errorHandler = StandardCommandErrorHandler(StandardCommandErrorHandlerConfig().apply(configure))
        return this
    }

    /**
     * Registers an argument parser to the given class.
     *
     * @return The builder instance. Useful for chaining.
     */
    fun addCustomParser(klass: Class<*>, parser: Parser<*>): CommandClientBuilder {
        // This is kinda unsafe. Would use T, but nullable/boxed types revert
        // to their java.lang counterparts. E.g. Int? becomes java.lang.Integer,
        // but Int remains kotlin.Int.
        // See https://youtrack.jetbrains.com/issue/KT-35423

        ArgParser.parsers[klass] = parser
        return this
    }

    inline fun <reified T> addCustomParser(parser: Parser<T>) = addCustomParser(T::class.java, parser)

    /**
     * Registers all default argument parsers.
     *
     * @return The builder instance. Useful for chaining.
     */
    fun registerDefaultParsers(): CommandClientBuilder {
        // Kotlin types and primitives
        val booleanParser = BooleanParser()
        ArgParser.parsers[Boolean::class.java] = booleanParser
        ArgParser.parsers[java.lang.Boolean::class.java] = booleanParser

        val doubleParser = DoubleParser()
        ArgParser.parsers[Double::class.java] = doubleParser
        ArgParser.parsers[java.lang.Double::class.java] = doubleParser

        val floatParser = FloatParser()
        ArgParser.parsers[Float::class.java] = floatParser
        ArgParser.parsers[java.lang.Float::class.java] = floatParser

        val intParser = IntParser()
        ArgParser.parsers[Int::class.java] = intParser
        ArgParser.parsers[Integer::class.java] = intParser

        val longParser = LongParser()
        ArgParser.parsers[Long::class.java] = longParser
        ArgParser.parsers[java.lang.Long::class.java] = longParser

        // JDA entities
        val inviteParser = InviteParser()
        ArgParser.parsers[Invite::class.java] = inviteParser
        ArgParser.parsers[net.dv8tion.jda.api.entities.Invite::class.java] = inviteParser

        ArgParser.parsers[Member::class.java] = MemberParser()
        ArgParser.parsers[Role::class.java] = RoleParser()
        ArgParser.parsers[User::class.java] = UserParser()
        GuildChannelParsers.registerDefaults(ArgParser.parsers)

        // Custom entities
        ArgParser.parsers[Emoji::class.java] = EmojiParser.forEmoji()
        ArgParser.parsers[EmojiUnion::class.java] = EmojiParser.forEmojiUnion()
        ArgParser.parsers[UnicodeEmoji::class.java] = EmojiParser.forUnicodeEmoji()
        ArgParser.parsers[CustomEmoji::class.java] = EmojiParser.forCustomEmoji()
        ArgParser.parsers[String::class.java] = StringParser()
        ArgParser.parsers[Snowflake::class.java] = SnowflakeParser()
        ArgParser.parsers[URL::class.java] = UrlParser()

        return this
    }

    /**
     * Builds a new CommandClient instance
     *
     * @return a CommandClient instance
     */
    fun build(): CommandClient {
        if (eventListeners.isEmpty()) {
            eventListeners.add(DefaultCommandEventAdapter())
        }

        val prefixProvider = this.prefixProvider ?: DefaultPrefixProvider(prefixes, allowMentionPrefix)
        val cooldownProvider = this.cooldownProvider ?: DefaultCooldownProvider()
        val commandClient = CommandClient(
            prefixProvider, cooldownProvider, ignoreBots, eventListeners.toList(),
            executionOptions, ownerIds, commandLocalizationProvider, errorHandler
        )

        if (helpCommandConfig.enabled) {
            commandClient.commands.register(
                DefaultHelpCommand(
                    helpCommandConfig.showParameterTypes,
                    helpCommandConfig.messages
                )
            )
        }

        commandClient.commands.registerAll(pendingCogs)

        return commandClient
    }
}
