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
import me.devoxin.flight.api.context.MessageContext
import me.devoxin.flight.api.context.SlashContext
import me.devoxin.flight.api.context.UserCommandContext
import me.devoxin.flight.internal.arguments.Argument
import me.devoxin.flight.internal.entities.Jar
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.interactions.commands.Command as JdaCommand
import org.slf4j.LoggerFactory
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.*
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.jvmErasure

internal class CogCommandLoader(
    private val jar: Jar? = null
) {
    fun loadCommands(cog: Cog): List<CommandFunction> {
        val commandDefinitions = getCommands(cog).map { loadCommandDefinition(it, cog) }
        val subcommandCandidates = getSubCommandCandidates(cog)
        val subcommandsByOwner = linkedMapOf<String, MutableList<SubCommandFunction>>()
        val slashOwners = commandDefinitions.filter { it.applicationCommandType == JdaCommand.Type.SLASH }

        for (candidate in subcommandCandidates) {
            val owner = resolveSubcommandOwner(cog, candidate, commandDefinitions, slashOwners)

            if (!candidate.supportsContext(ContextType.SLASH)) {
                throw IllegalStateException(
                    "Sub-command '${candidate.name}' must declare SlashContext or Context because it belongs to slash command '${owner.name}'."
                )
            }

            val declaredGroups = owner.declaredSubcommandGroups.associateBy(CogDeclaredSubcommandGroup::normalizedName)
            val resolvedGroupName = candidate.requestedGroupName?.let { groupName ->
                declaredGroups[groupName]?.name
                    ?: throw IllegalStateException(
                        "Sub-command '${candidate.name}' references undeclared group '$groupName' on command '${owner.name}'."
                    )
            }

            val subcommand = candidate.toSubCommandFunction(owner.name, resolvedGroupName, cog)
            subcommandsByOwner.getOrPut(owner.normalizedName) { mutableListOf() } += subcommand
        }

        return commandDefinitions.map { definition ->
            val subcommands = subcommandsByOwner[definition.normalizedName].orEmpty()
            val directSubcommands = subcommands.filterNot(SubCommandFunction::isGrouped)
            val groupedSubcommands = subcommands.filter(SubCommandFunction::isGrouped)
            val groups = definition.declaredSubcommandGroups.map { declaredGroup ->
                val children = groupedSubcommands.filter { it.normalizedGroupName == declaredGroup.normalizedName }

                if (children.isEmpty()) {
                    throw IllegalStateException(
                        "Subcommand group '${declaredGroup.name}' declared on command '${definition.name}' must contain at least one sub-command."
                    )
                }

                SubcommandGroupDefinition(declaredGroup.name, declaredGroup.description, children)
            }

            definition.toCommandFunction(cog, jar, directSubcommands, groups)
        }
    }

    fun loadCommand(meth: KFunction<*>, cog: Cog): CommandFunction {
        return loadCommands(cog)
            .firstOrNull { it.method == meth }
            ?: throw IllegalStateException("${meth.name} could not be loaded from ${cog::class.simpleName}")
    }

    fun getSubCommands(cog: Cog): List<SubCommandFunction> {
        return loadCommands(cog).flatMap(CommandFunction::allSubcommands)
    }

    private fun getCommands(cog: Cog): List<KFunction<*>> {
        log.debug("Scanning ${cog::class.simpleName} for commands...")

        val commands = cog::class.members
            .filterIsInstance<KFunction<*>>()
            .filter(::isTopLevelCommand)

        log.debug("Found ${commands.size} commands in cog ${cog::class.simpleName}")
        return commands.toList()
    }

    private fun getSubCommandCandidates(cog: Cog): List<CogLoadedSubCommandCandidate> {
        log.debug("Scanning ${cog::class.simpleName} for sub-commands...")

        val subcommands = cog::class.members
            .filterIsInstance<KFunction<*>>()
            .filter { it.hasAnnotation<SubCommand>() }
            .map { loadSubCommandCandidate(it, cog) }

        log.debug("Found ${subcommands.size} sub-commands in cog ${cog::class.simpleName}")
        return subcommands.toList()
    }

    private fun loadSubCommandCandidate(meth: KFunction<*>, cog: Cog): CogLoadedSubCommandCandidate {
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

        return CogLoadedSubCommandCandidate(
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

    private fun isTopLevelCommand(meth: KFunction<*>): Boolean {
        return getCommandKind(meth) != null
    }

    private fun getCommandKind(meth: KFunction<*>): CogCommandKind? {
        return when {
            meth.hasAnnotation<Command>() -> CogCommandKind.STANDARD
            meth.hasAnnotation<UserCommand>() -> CogCommandKind.USER_CONTEXT
            meth.hasAnnotation<MessageCommand>() -> CogCommandKind.MESSAGE_CONTEXT
            else -> null
        }
    }

    private fun resolveCommandName(meth: KFunction<*>, kind: CogCommandKind): String {
        val resolved = when (kind) {
            CogCommandKind.STANDARD -> meth.name.lowercase()
            CogCommandKind.USER_CONTEXT -> meth.findAnnotation<UserCommand>()!!.name.ifBlank { meth.name }
            CogCommandKind.MESSAGE_CONTEXT -> meth.findAnnotation<MessageCommand>()!!.name.ifBlank { meth.name }
        }.trim()

        require(resolved.isNotEmpty()) { "${meth.name} resolves to a blank command name!" }
        return resolved
    }

    private fun resolveCommandProperties(meth: KFunction<*>, kind: CogCommandKind): CommandProperties {
        return when (kind) {
            CogCommandKind.STANDARD -> CommandProperties.from(meth.findAnnotation<Command>()!!)
            CogCommandKind.USER_CONTEXT -> CommandProperties.from(meth.findAnnotation<UserCommand>()!!)
            CogCommandKind.MESSAGE_CONTEXT -> CommandProperties.from(meth.findAnnotation<MessageCommand>()!!)
        }
    }

    private fun loadCommandDefinition(meth: KFunction<*>, cog: Cog): CogLoadedCommandDefinition {
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
            CogCommandKind.STANDARD -> loadParameters(cog, parameters)
            CogCommandKind.USER_CONTEXT,
            CogCommandKind.MESSAGE_CONTEXT -> emptyList()
        }
        val selectedTargetParameter = when (commandKind) {
            CogCommandKind.STANDARD -> null
            CogCommandKind.USER_CONTEXT -> validateSelectedTargetParameter(meth, parameters, User::class.java)
            CogCommandKind.MESSAGE_CONTEXT -> validateSelectedTargetParameter(meth, parameters, Message::class.java)
        }
        val declaredSubcommandGroups = resolveDeclaredSubcommandGroups(meth, commandKind, signature.applicationCommandType)

        return CogLoadedCommandDefinition(
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
        kind: CogCommandKind,
        meth: KFunction<*>,
        ctxParam: KParameter
    ): CogLoadedCommandSignature {
        return when (kind) {
            CogCommandKind.STANDARD -> resolveStandardSignature(meth, ctxParam)
            CogCommandKind.USER_CONTEXT -> {
                require(ctxParam.type.isSubtypeOf(UserCommandContext::class.starProjectedType)) {
                    "${meth.name} must declare a ${UserCommandContext::class.simpleName} parameter."
                }

                CogLoadedCommandSignature(setOf(ContextType.USER_COMMAND), JdaCommand.Type.USER)
            }

            CogCommandKind.MESSAGE_CONTEXT -> {
                require(ctxParam.type.isSubtypeOf(MessageCommandContext::class.starProjectedType)) {
                    "${meth.name} must declare a ${MessageCommandContext::class.simpleName} parameter."
                }

                CogLoadedCommandSignature(setOf(ContextType.MESSAGE_COMMAND), JdaCommand.Type.MESSAGE)
            }
        }
    }

    private fun resolveStandardSignature(meth: KFunction<*>, ctxParam: KParameter): CogLoadedCommandSignature {
        val jvmCtx = ctxParam.type

        return when {
            jvmCtx.isSubtypeOf(SlashContext::class.starProjectedType) -> {
                CogLoadedCommandSignature(setOf(ContextType.SLASH), JdaCommand.Type.SLASH)
            }

            jvmCtx.isSubtypeOf(MessageContext::class.starProjectedType) -> {
                CogLoadedCommandSignature(setOf(ContextType.MESSAGE), null)
            }

            jvmCtx.isSubtypeOf(Context::class.starProjectedType) -> {
                CogLoadedCommandSignature(setOf(ContextType.MESSAGE, ContextType.SLASH), JdaCommand.Type.SLASH)
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
        kind: CogCommandKind,
        applicationCommandType: JdaCommand.Type?
    ): List<CogDeclaredSubcommandGroup> {
        val annotations = meth.findAnnotations<SubCommandGroup>()
        if (annotations.isEmpty()) {
            return emptyList()
        }

        require(kind == CogCommandKind.STANDARD && applicationCommandType == JdaCommand.Type.SLASH) {
            "@SubCommandGroup can only be declared on slash-capable @Command handlers."
        }

        return annotations.map { annotation ->
            val name = annotation.name.trim().lowercase()
            require(name.isNotBlank()) { "${meth.name} declares a blank subcommand group name!" }

            CogDeclaredSubcommandGroup(
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
        candidate: CogLoadedSubCommandCandidate,
        commandDefinitions: List<CogLoadedCommandDefinition>,
        slashOwners: List<CogLoadedCommandDefinition>
    ): CogLoadedCommandDefinition {
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

    companion object {
        private val log = LoggerFactory.getLogger(CogCommandLoader::class.java)
    }
}

private data class CogLoadedCommandDefinition(
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
    val declaredSubcommandGroups: List<CogDeclaredSubcommandGroup>
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

private data class CogDeclaredSubcommandGroup(
    val name: String,
    val description: String
) {
    val normalizedName: String = name.lowercase()
}

private data class CogLoadedSubCommandCandidate(
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

private data class CogLoadedCommandSignature(
    val supportedContextTypes: Set<ContextType>,
    val applicationCommandType: JdaCommand.Type?
)

private enum class CogCommandKind {
    STANDARD,
    USER_CONTEXT,
    MESSAGE_CONTEXT
}
