# Flight 5.0 for JDA 6

Flight is a Kotlin command framework for JDA 6 with an interaction-first runtime model, explicit command registration, command sync utilities, typed autocomplete handlers, and optional message-command parsing.

This branch targets the current Flight 5.0 model:

- explicit cog registration is the primary workflow
- slash commands and context menus are first-class
- generic command code should prefer `Context.respond(...)`
- command sync is explicit and deterministic
- subcommands support explicit `parent` and `group` wiring
- centralized command failure handling is available at the builder level

## Requirements

- Java 17+
- Kotlin/JVM 17+
- JDA 6
- `AnnotatedEventManager` for event dispatch

## Installation

The published artifact name is `flight-jda6`.

```kotlin
repositories {
  mavenCentral()
  maven("https://m2.dv8tion.net/releases")
  maven("https://jitpack.io")
}

dependencies {
  implementation("io.github.ceracharlescc:flight-jda6:5.0.0")
}
```

If you consume a private or fork-specific publication, keep the same artifact name and use the repository coordinates appropriate for that publication source.

## Quick start

Create a `CommandClient`, register your cogs explicitly, then add the client as a JDA listener.

```kotlin
import me.devoxin.flight.api.CommandClient
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.hooks.AnnotatedEventManager

val commandClient = CommandClient.builder()
  .setPrefixes("!", "?")
  .registerDefaultParsers() // needed for message-command argument parsing
  .register(
    UtilityCog(),
    ModerationCog(moderationService)
  )
  .useDefaultErrorHandler()
  .build()

val jda = JDABuilder.createDefault(token)
  .setEventManager(AnnotatedEventManager())
  .addEventListeners(commandClient)
  .build()
```

### Why explicit registration is the default path

Explicit registration works best with dependency injection, service containers, custom factories, and test setup.

```kotlin
val cogs: List<Cog> = container.resolveAll()

val client = CommandClient.builder()
  .setPrefixes("!")
  .registerAll(cogs)
  .build()
```

Package scanning still exists as an optional convenience:

```kotlin
client.commands.register("my.bot.commands")
```

Use scanning only when you want Flight to construct cog instances for you.

## Writing commands

Commands live inside a `Cog`.

### Interaction-first slash command

Use `SlashContext` when the command is slash-only, or `Context` when you want one handler to work for both message and slash invocation.

```kotlin
class FlightInfoCog : Cog {
  @Command(description = "Look up a flight")
  suspend fun flight(ctx: SlashContext, flightNumber: String) {
    val flight = flightService.lookup(flightNumber)
    ctx.respond("${flight.number}: ${flight.status}")
  }
}
```

### Shared handler for slash + prefix commands

```kotlin
class PingCog : Cog {
  @Command(description = "Check if the bot is alive")
  fun ping(ctx: Context) {
    ctx.respond("Pong!")
  }
}
```

`Context.respond(...)` is the recommended default because it safely handles both message replies and interaction responses.

## Context menus

Flight supports Discord user and message context-menu commands.

```kotlin
class ReviewCog : Cog {
  @UserCommand(name = "Inspect User")
  fun inspectUser(ctx: UserCommandContext, target: User) {
    ctx.respond("User id: ${target.id}")
  }

  @MessageCommand(name = "Quote Message")
  fun quoteMessage(ctx: MessageCommandContext, target: Message) {
    ctx.respond(target.contentDisplay)
  }
}
```

For context menus, Flight can inject the selected `User` or `Message` target directly into your handler.

## Subcommands and groups

Flight 5.0 uses an explicit `parent` / `group` model.

```kotlin
class AdminCog : Cog {
  @Command(description = "Administration commands")
  @SubCommandGroup(name = "roles", description = "Role management")
  fun admin(ctx: SlashContext) {
    ctx.respond("Choose a subcommand.")
  }

  @SubCommand(parent = "admin", description = "Ban a member")
  suspend fun ban(ctx: SlashContext, user: User) {
    moderationService.ban(user.idLong)
    ctx.respond("Banned ${user.asTag}")
  }

  @SubCommand(parent = "admin", group = "roles", description = "Grant a role")
  suspend fun grant(ctx: SlashContext, user: User, role: Role) {
    moderationService.grantRole(user.idLong, role.idLong)
    ctx.respond("Granted ${role.name} to ${user.asTag}")
  }
}
```

Notes:

- top-level slash commands may have root options **or** subcommands/groups, but not both
- grouped subcommands use `group = "..."`
- direct subcommands use only `parent = "..."`
- subcommands can be slash-only or message+slash depending on their context parameter type

## Typed autocomplete handlers

`@Autocomplete` now takes a handler type instead of a string method name.

```kotlin
import me.devoxin.flight.api.autocomplete.AutocompleteHandler

object AirportAutocomplete : AutocompleteHandler<AirportCog> {
  override suspend fun complete(cog: AirportCog, event: CommandAutoCompleteInteractionEvent) {
    val query = event.focusedOption.value
    val matches = cog.airportService.search(query)
      .take(25)
      .map { net.dv8tion.jda.api.interactions.commands.Command.Choice(it.name, it.code) }

    event.replyChoices(matches).queue()
  }
}

class AirportCog(
  val airportService: AirportService
) : Cog {
  @Command(description = "Search airports")
  fun airport(
    ctx: SlashContext,
    @Autocomplete(AirportAutocomplete::class) query: String
  ) = Unit
}
```

Handler rules:

- handlers can be Kotlin `object`s or no-arg classes
- handlers are resolved during cog registration, not at first runtime use
- the handler receives the concrete cog type declared in its generic parameter
- registration fails fast if the handler cannot be instantiated or is attached to the wrong cog type

## Prefix commands and argument parsing

Message commands are still supported. Prefix parsing is opt-in through builder prefixes, and argument parsing uses registered parsers.

```kotlin
class MathCog : Cog {
  @Command(description = "Add two numbers")
  fun add(ctx: Context, left: Int, right: Int) {
    ctx.respond("${left + right}")
  }
}

val client = CommandClient.builder()
  .setPrefixes("!")
  .registerDefaultParsers()
  .register(MathCog())
  .build()
```

Flight resolves ambiguous prefixes deterministically by choosing the longest matching prefix.

## Command sync

Application-command registration is explicit. Flight can either plan a sync or execute it.

### Inspect a sync plan

```kotlin
val plan = commandClient.planCommandSync()

for (target in plan.targets) {
  println("Scope: ${target.scope}")
  println("Emit: ${target.emitted.map { it.name }}")
  println("Skip: ${target.skipped.map { it.command.name to it.reason }}")
}
```

### Execute the sync

```kotlin
val result = commandClient.syncCommands(jda)
  .join()

for (target in result.targets) {
  println("${target.scope}: ${target.state}")
}
```

### Targeted sync

```kotlin
import me.devoxin.flight.api.sync.CommandSyncOptions

val guildOnlyPlan = commandClient.planCommandSync(
  CommandSyncOptions(
    dryRun = true,
    guildIds = setOf(123456789012345678L)
  )
)
```

Important sync behavior:

- sync is authoritative per targeted scope
- targeted global/guild scopes are replaced in full
- command export already respects `@GuildIds`
- permission-bearing application commands are exported as guild-context commands

## Error handling

Flight 5.0 has a builder-level centralized error-handler API.

### Recommended: install the standard handler

```kotlin
val client = CommandClient.builder()
  .setPrefixes("!")
  .registerDefaultParsers()
  .register(MyCog())
  .useDefaultErrorHandler {
    enableUnknownCommandResponses = false
  }
  .build()
```

The standard handler:

- responds to user-facing failures with `Context.respond(...)`
- sends generic responses for parse/execution failures that still have a `Context`
- logs framework/autocomplete failures without trying to message a user
- keeps unknown-command replies disabled by default to avoid noisy bots

### Custom centralized handler

```kotlin
val client = CommandClient.builder()
  .setErrorHandler(CommandErrorHandler { failure ->
    when (failure) {
      is CommandFailure.BadArgumentFailure ->
        failure.context.respond("Bad argument: ${failure.error.message}")

      is CommandFailure.CheckFailure ->
        failure.context.respond("You can't use that command here.")

      is CommandFailure.CommandExecutionFailure -> {
        logger.error("Command failed", failure.error)
        failure.context.respond("Something went wrong.")
      }

      is CommandFailure.FrameworkFailure ->
        logger.error("Framework failure", failure.error)

      else -> Unit
    }
  })
  .build()
```

### Cog-local execution override

`Cog.onCommandError(...)` still runs first for execution failures.

If it returns `true`, the centralized handler is skipped for that execution failure. Adapter callbacks still run afterward for logging/observability.

### CommandEventAdapter vs CommandErrorHandler

Use them for different jobs:

- `CommandErrorHandler`: the primary application-facing failure strategy
- `CommandEventAdapter`: lower-level hooks for metrics, tracing, logging, and lifecycle observation

The default adapter is intentionally quiet and no longer prints stack traces on its own.

## Permission behavior

Flight aligns runtime behavior with exported Discord metadata:

- commands with `userPermissions` export `DefaultMemberPermissions`
- commands with `userPermissions` or `botPermissions` are treated as requiring guild context
- invoking permission-bearing commands outside a guild fails through the guild-check path
- bot permissions remain a runtime-only check because Discord does not expose equivalent registration metadata

Example:

```kotlin
class ModerationCog : Cog {
  @Command(
    description = "Purge recent messages",
    userPermissions = [Permission.MESSAGE_MANAGE],
    botPermissions = [Permission.MESSAGE_MANAGE]
  )
  suspend fun purge(ctx: SlashContext, amount: Int) {
    moderationService.purge(ctx.guildChannel!!, amount)
    ctx.respond("Purged $amount messages.")
  }
}
```

## Migration notes

If you are updating older Flight usage, the biggest changes are:

1. **Explicit registration is preferred**
   - use `builder().register(...)` / `registerAll(...)`
   - package scanning is still available, but no longer the default narrative

2. **Flight is interaction-first**
   - slash commands and context menus are first-class
   - prefer `Context.respond(...)` for generic command responses

3. **Command sync is explicit**
   - use `planCommandSync(...)` and `syncCommands(...)`
   - application-command export is no longer an implicit side effect

4. **Autocomplete is typed**
   - old `@Autocomplete(method = "...")` usage is gone
   - replace it with `@Autocomplete(MyHandler::class)`

5. **Subcommands use explicit ownership metadata**
   - use `@SubCommand(parent = "...")`
   - grouped subcommands use `group = "..."`
   - the old “exactly one top-level command in the cog” guidance no longer applies

6. **Centralized error handling moved up to the builder**
   - prefer `setErrorHandler(...)` or `useDefaultErrorHandler(...)`
   - treat `CommandEventAdapter` as a lower-level hook surface

7. **Default adapter behavior is intentionally quiet**
   - install your own centralized handler or the standard handler for user-facing responses
   