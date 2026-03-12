package me.devoxin.flight.internal.utils

import me.devoxin.flight.api.CommandFunction
import me.devoxin.flight.api.SubCommandFunction
import me.devoxin.flight.api.SubcommandGroupDefinition
import me.devoxin.flight.api.annotations.*
import me.devoxin.flight.api.command.CommandProperties
import me.devoxin.flight.api.command.Cog
import me.devoxin.flight.api.context.Context
import me.devoxin.flight.api.context.ContextType
import me.devoxin.flight.api.context.MessageCommandContext
import me.devoxin.flight.api.util.ObjectStorage
import me.devoxin.flight.api.context.MessageContext
import me.devoxin.flight.api.context.SlashContext
import me.devoxin.flight.api.context.UserCommandContext
import me.devoxin.flight.internal.arguments.Argument
import me.devoxin.flight.internal.entities.Jar
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.interactions.commands.Command as JdaCommand
import org.reflections.Reflections
import org.reflections.scanners.MethodParameterNamesScanner
import org.reflections.scanners.Scanners
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.reflect.Modifier
import java.net.URL
import java.net.URLClassLoader
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.*
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.jvmErasure

class Indexer {
    private val jar: Jar?
    private val packageName: String
    private val reflections: Reflections
    private val classLoader: URLClassLoader?
    private val commandLoader by lazy { CogCommandLoader(jar) }

    constructor(packageName: String) {
        this.packageName = packageName
        this.classLoader = null
        this.jar = null
        reflections = Reflections(packageName, MethodParameterNamesScanner(), Scanners.SubTypes)
    }

    constructor(packageName: String, jarPath: String) {
        this.packageName = packageName

        val commandJar = File(jarPath)
        check(commandJar.exists()) { "jarPath points to a non-existent file." }
        check(commandJar.extension == "jar") { "jarPath leads to a file which is not a jar." }

        val path = URL("jar:file:${commandJar.absolutePath}!/")
        this.classLoader = URLClassLoader.newInstance(arrayOf(path))
        this.jar = Jar(commandJar.nameWithoutExtension, commandJar.absolutePath, packageName, classLoader)
        reflections = Reflections(packageName, this.classLoader, MethodParameterNamesScanner(), Scanners.SubTypes)
    }

    fun getCogs(objectStorage: ObjectStorage): List<Cog> {
        val cogs = reflections.getSubTypesOf(Cog::class.java)
        log.debug("Discovered ${cogs.size} cogs in $packageName")

        return cogs
            .filter { !Modifier.isAbstract(it.modifiers) && !it.isInterface && Cog::class.java.isAssignableFrom(it) }
            .map { construct(it, objectStorage) }
    }

    fun getCommands(cog: Cog): List<KFunction<*>> {
        log.debug("Scanning ${cog::class.simpleName} for commands...")

        val cogClass = cog::class
        val commands = cogClass.members
            .filterIsInstance<KFunction<*>>()
            .filter(::isTopLevelCommand)

        log.debug("Found ${commands.size} commands in cog ${cog::class.simpleName}")
        return commands.toList()
    }

    fun loadCommands(cog: Cog): List<CommandFunction> {
        return commandLoader.loadCommands(cog)
    }

    fun loadCommand(meth: KFunction<*>, cog: Cog): CommandFunction {
        return commandLoader.loadCommand(meth, cog)
    }

    fun getSubCommands(cog: Cog): List<SubCommandFunction> {
        return commandLoader.getSubCommands(cog)
    }

    private fun getSubCommandCandidates(cog: Cog): List<LoadedSubCommandCandidate> {
        log.debug("Scanning ${cog::class.simpleName} for sub-commands...")

        val cogClass = cog::class
        val subcommands = cogClass.members
            .filterIsInstance<KFunction<*>>()
            .filter { it.hasAnnotation<SubCommand>() }
            .map { loadSubCommandCandidate(it, cog) }

        log.debug("Found ${subcommands.size} sub-commands in cog ${cog::class.simpleName}")
        return subcommands.toList()
    }

    private fun loadSubCommandCandidate(meth: KFunction<*>, cog: Cog): LoadedSubCommandCandidate {
        require(meth.javaMethod!!.declaringClass == cog::class.java) { "${meth.name} is not from ${cog::class.simpleName}" }
        require(meth.hasAnnotation<SubCommand>()) { "${meth.name} is not annotated with SubCommand!" }
        require(!meth.hasAnnotation<GuildIds>()) {
            "@GuildIds is only supported on top-level @Command/@UserCommand/@MessageCommand handlers."
        }

        val properties = meth.findAnnotation<SubCommand>()!!
        val ctxParam = meth.valueParameters.firstOrNull { it.type.isSubtypeOf(Context::class.starProjectedType) }

        require(ctxParam != null) { "${meth.name} is missing the Context parameter!" }

        val name = properties.name.ifBlank { meth.name }.trim().lowercase()
        require(name.isNotBlank()) { "${meth.name} resolves to a blank sub-command name!" }

        val supportedContextTypes = resolveSubcommandSignature(meth, ctxParam)
        val parameters = meth.valueParameters.filter { it != ctxParam }
        val arguments = loadParameters(cog, parameters)
        val timeout = meth.findAnnotation<Timeout>()?.also { validateTimeoutAnnotation(meth.name, it) }

        return LoadedSubCommandCandidate(
            method = meth,
            name = name,
            properties = properties,
            timeout = timeout,
            requestedParentName = properties.parent.trim().ifBlank { null }?.lowercase(),
            requestedGroupName = properties.group.trim().ifBlank { null }?.lowercase(),
            supportedContextTypes = supportedContextTypes,
            arguments = arguments,
            contextParameter = ctxParam
        )
    }

    private fun loadParameters(cog: Cog, parameters: List<KParameter>): List<Argument> {
        return CommandParameterLoader.loadParameters(cog, parameters)
    }

    private fun construct(cls: Class<out Cog>, objectStorage: ObjectStorage): Cog {
        return try {
            cls.getDeclaredConstructor(ObjectStorage::class.java).newInstance(objectStorage)
        } catch (t: NoSuchMethodException) {
            cls.getDeclaredConstructor().newInstance()
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(Indexer::class.java)
    }

    private fun isTopLevelCommand(meth: KFunction<*>): Boolean {
        return getCommandKind(meth) != null
    }

    private fun getCommandKind(meth: KFunction<*>): CommandKind? {
        return when {
            meth.hasAnnotation<Command>() -> CommandKind.STANDARD
            meth.hasAnnotation<UserCommand>() -> CommandKind.USER_CONTEXT
            meth.hasAnnotation<MessageCommand>() -> CommandKind.MESSAGE_CONTEXT
            else -> null
        }
    }

    private fun resolveCommandName(meth: KFunction<*>, kind: CommandKind): String {
        val resolved = when (kind) {
            CommandKind.STANDARD -> meth.name.lowercase()
            CommandKind.USER_CONTEXT -> meth.findAnnotation<UserCommand>()!!.name.ifBlank { meth.name }
            CommandKind.MESSAGE_CONTEXT -> meth.findAnnotation<MessageCommand>()!!.name.ifBlank { meth.name }
        }.trim()

        require(resolved.isNotEmpty()) { "${meth.name} resolves to a blank command name!" }
        return resolved
    }

    private fun resolveCommandProperties(meth: KFunction<*>, kind: CommandKind): CommandProperties {
        return when (kind) {
            CommandKind.STANDARD -> CommandProperties.from(meth.findAnnotation<Command>()!!)
            CommandKind.USER_CONTEXT -> CommandProperties.from(meth.findAnnotation<UserCommand>()!!)
            CommandKind.MESSAGE_CONTEXT -> CommandProperties.from(meth.findAnnotation<MessageCommand>()!!)
        }
    }

    private fun loadCommandDefinition(meth: KFunction<*>, cog: Cog): LoadedCommandDefinition {
        require(meth.javaMethod!!.declaringClass == cog::class.java) { "${meth.name} is not from ${cog::class.simpleName}" }
        val commandKind = getCommandKind(meth)
        require(commandKind != null) { "${meth.name} is not annotated with a supported command annotation!" }

        val categoryOriginal = cog.name()
            ?: cog::class.java.`package`.name.split('.').last().replace('_', ' ')
        val category = TextUtils.capitalise(categoryOriginal)
        val name = resolveCommandName(meth, commandKind)
        val properties = resolveCommandProperties(meth, commandKind)
        val cooldown = meth.findAnnotation<Cooldown>()
        val timeout = meth.findAnnotation<Timeout>()?.also { validateTimeoutAnnotation(meth.name, it) }
        val guildIds = resolveGuildIds(meth)
        val ctxParam = meth.valueParameters.firstOrNull { it.type.isSubtypeOf(Context::class.starProjectedType) }

        require(ctxParam != null) { "${meth.name} is missing the Context parameter!" }

        val signature = resolveCommandSignature(commandKind, meth, ctxParam)
        val parameters = meth.valueParameters.filter { it != ctxParam }
        val arguments = when (commandKind) {
            CommandKind.STANDARD -> loadParameters(cog, parameters)
            CommandKind.USER_CONTEXT,
            CommandKind.MESSAGE_CONTEXT -> emptyList()
        }
        val selectedTargetParameter = when (commandKind) {
            CommandKind.STANDARD -> null
            CommandKind.USER_CONTEXT -> validateSelectedTargetParameter(meth, parameters, User::class.java)
            CommandKind.MESSAGE_CONTEXT -> validateSelectedTargetParameter(meth, parameters, Message::class.java)
        }
        val declaredSubcommandGroups = resolveDeclaredSubcommandGroups(meth, commandKind, signature.applicationCommandType)

        return LoadedCommandDefinition(
            method = meth,
            name = name,
            category = category,
            properties = properties,
            cooldown = cooldown,
            timeout = timeout,
            guildIds = guildIds,
            supportedContextTypes = signature.supportedContextTypes,
            applicationCommandType = signature.applicationCommandType,
            selectedTargetParameter = selectedTargetParameter,
            arguments = arguments,
            contextParameter = ctxParam,
            declaredSubcommandGroups = declaredSubcommandGroups
        )
    }

    private fun resolveCommandSignature(
        kind: CommandKind,
        meth: KFunction<*>,
        ctxParam: KParameter
    ): LoadedCommandSignature {
        return when (kind) {
            CommandKind.STANDARD -> resolveStandardSignature(meth, ctxParam)
            CommandKind.USER_CONTEXT -> {
                require(ctxParam.type.isSubtypeOf(UserCommandContext::class.starProjectedType)) {
                    "${meth.name} must declare a ${UserCommandContext::class.simpleName} parameter."
                }

                LoadedCommandSignature(setOf(ContextType.USER_COMMAND), JdaCommand.Type.USER)
            }

            CommandKind.MESSAGE_CONTEXT -> {
                require(ctxParam.type.isSubtypeOf(MessageCommandContext::class.starProjectedType)) {
                    "${meth.name} must declare a ${MessageCommandContext::class.simpleName} parameter."
                }

                LoadedCommandSignature(setOf(ContextType.MESSAGE_COMMAND), JdaCommand.Type.MESSAGE)
            }
        }
    }

    private fun resolveStandardSignature(meth: KFunction<*>, ctxParam: KParameter): LoadedCommandSignature {
        val jvmCtx = ctxParam.type

        return when {
            jvmCtx.isSubtypeOf(SlashContext::class.starProjectedType) -> {
                LoadedCommandSignature(setOf(ContextType.SLASH), JdaCommand.Type.SLASH)
            }

            jvmCtx.isSubtypeOf(MessageContext::class.starProjectedType) -> {
                LoadedCommandSignature(setOf(ContextType.MESSAGE), null)
            }

            jvmCtx.isSubtypeOf(Context::class.starProjectedType) -> {
                LoadedCommandSignature(setOf(ContextType.MESSAGE, ContextType.SLASH), JdaCommand.Type.SLASH)
            }

            else -> throw IllegalStateException(
                "${meth.name} must declare MessageContext, SlashContext, or Context for @Command handlers."
            )
        }
    }

    private fun resolveSubcommandSignature(meth: KFunction<*>, ctxParam: KParameter): Set<ContextType> {
        val jvmCtx = ctxParam.type

        return when {
            jvmCtx.isSubtypeOf(SlashContext::class.starProjectedType) -> setOf(ContextType.SLASH)
            jvmCtx.isSubtypeOf(MessageContext::class.starProjectedType) -> setOf(ContextType.MESSAGE)
            jvmCtx.isSubtypeOf(Context::class.starProjectedType) -> setOf(ContextType.MESSAGE, ContextType.SLASH)
            else -> throw IllegalStateException(
                "${meth.name} must declare MessageContext, SlashContext, or Context for @SubCommand handlers."
            )
        }
    }

    private fun resolveDeclaredSubcommandGroups(
        meth: KFunction<*>,
        kind: CommandKind,
        applicationCommandType: JdaCommand.Type?
    ): List<DeclaredSubcommandGroup> {
        val annotations = meth.findAnnotations<SubCommandGroup>()
        if (annotations.isEmpty()) {
            return emptyList()
        }

        require(kind == CommandKind.STANDARD && applicationCommandType == JdaCommand.Type.SLASH) {
            "@SubCommandGroup can only be declared on slash-capable @Command handlers."
        }

        return annotations.map { annotation ->
            val name = annotation.name.trim().lowercase()
            require(name.isNotBlank()) { "${meth.name} declares a blank subcommand group name!" }

            DeclaredSubcommandGroup(
                name = name,
                description = annotation.description
            )
        }
    }

    private fun validateSelectedTargetParameter(
        meth: KFunction<*>,
        parameters: List<KParameter>,
        expectedType: Class<*>
    ): KParameter? {
        require(parameters.size <= 1) {
            "${meth.name} may declare at most one non-context parameter, and it must be a ${expectedType.simpleName}."
        }

        val parameter = parameters.singleOrNull() ?: return null
        require(parameter.type.jvmErasure.java == expectedType) {
            "${meth.name} may only inject a ${expectedType.simpleName} target parameter for this command type."
        }

        return parameter
    }

    private fun validateTimeoutAnnotation(name: String, timeout: Timeout) {
        require(timeout.duration > 0) {
            "@${Timeout::class.simpleName} on '$name' must declare a duration greater than 0."
        }
    }

    private fun resolveGuildIds(meth: KFunction<*>): Set<Long> {
        val annotation = meth.findAnnotation<GuildIds>() ?: return emptySet()

        require(annotation.value.isNotEmpty()) {
            "@GuildIds on '${meth.name}' must declare at least one guild id."
        }

        require(annotation.value.all { it > 0 }) {
            "@GuildIds on '${meth.name}' may only contain positive guild ids."
        }

        return annotation.value.asSequence()
            .distinct()
            .sorted()
            .toSet()
    }

    private fun resolveSubcommandOwner(
        cog: Cog,
        candidate: LoadedSubCommandCandidate,
        commandDefinitions: List<LoadedCommandDefinition>,
        slashOwners: List<LoadedCommandDefinition>
    ): LoadedCommandDefinition {
        val requestedParentName = candidate.requestedParentName

        if (requestedParentName == null) {
            if (slashOwners.size != 1) {
                throw IllegalStateException(
                    "Sub-command '${candidate.name}' in ${cog::class.simpleName} must declare parent because the cog does not have exactly one slash-capable top-level @Command."
                )
            }

            return slashOwners.single()
        }

        val directMatch = slashOwners.firstOrNull { it.normalizedName == requestedParentName }
        if (directMatch != null) {
            return directMatch
        }

        val nonSlashMatch = commandDefinitions.firstOrNull { it.normalizedName == requestedParentName }
        if (nonSlashMatch != null) {
            throw IllegalStateException(
                "Sub-command '${candidate.name}' cannot use parent '${candidate.requestedParentName}' because that command is not slash-capable."
            )
        }

        throw IllegalStateException(
            "Sub-command '${candidate.name}' references unknown parent '${candidate.requestedParentName}'."
        )
    }
}

private data class LoadedCommandDefinition(
    val method: KFunction<*>,
    val name: String,
    val category: String,
    val properties: CommandProperties,
    val cooldown: Cooldown?,
    val timeout: Timeout?,
    val guildIds: Set<Long>,
    val supportedContextTypes: Set<ContextType>,
    val applicationCommandType: JdaCommand.Type?,
    val selectedTargetParameter: KParameter?,
    val arguments: List<Argument>,
    val contextParameter: KParameter,
    val declaredSubcommandGroups: List<DeclaredSubcommandGroup>
) {
    val normalizedName: String = name.lowercase()

    fun toCommandFunction(
        cog: Cog,
        jar: Jar?,
        directSubcommands: List<SubCommandFunction>,
        subcommandGroups: List<SubcommandGroupDefinition>
    ): CommandFunction {
        return CommandFunction(
            name = name,
            category = category,
            properties = properties,
            cooldown = cooldown,
            timeout = timeout,
            jar = jar,
            guildIds = guildIds,
            supportedContextTypes = supportedContextTypes,
            applicationCommandType = applicationCommandType,
            selectedTargetParameter = selectedTargetParameter,
            directSubcommands = directSubcommands,
            subcommandGroups = subcommandGroups,
            method = method,
            cog = cog,
            arguments = arguments,
            contextParameter = contextParameter
        )
    }
}

private data class DeclaredSubcommandGroup(
    val name: String,
    val description: String
) {
    val normalizedName: String = name.lowercase()
}

private data class LoadedSubCommandCandidate(
    val method: KFunction<*>,
    val name: String,
    val properties: SubCommand,
    val timeout: Timeout?,
    val requestedParentName: String?,
    val requestedGroupName: String?,
    val supportedContextTypes: Set<ContextType>,
    val arguments: List<Argument>,
    val contextParameter: KParameter
) {
    fun supportsContext(contextType: ContextType): Boolean {
        return contextType in supportedContextTypes
    }

    fun toSubCommandFunction(parentCommandName: String, groupName: String?, cog: Cog): SubCommandFunction {
        return SubCommandFunction(
            name = name,
            properties = properties,
            timeout = timeout,
            parentCommandName = parentCommandName,
            groupName = groupName,
            supportedContextTypes = supportedContextTypes,
            method = method,
            cog = cog,
            arguments = arguments,
            contextParameter = contextParameter
        )
    }
}

private data class LoadedCommandSignature(
    val supportedContextTypes: Set<ContextType>,
    val applicationCommandType: JdaCommand.Type?
)

private enum class CommandKind {
    STANDARD,
    USER_CONTEXT,
    MESSAGE_CONTEXT
}
