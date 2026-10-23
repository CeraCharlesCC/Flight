# Flight 5 for JDA 6

Flight is a Kotlin command framework for JDA 6 with an interaction-first runtime model, explicit command registration, command sync utilities, typed autocomplete handlers, and optional message-command parsing.

This guide describes the current source tree. Use a release containing the APIs you need; older tags may differ. The current Flight 5 model includes:

- explicit cog registration is the primary workflow
- slash commands and context menus are first-class
- generic command code should prefer `Context.respond(...)`
- command sync is explicit and deterministic
- subcommands support explicit `parent` and `group` wiring
- centralized command failure handling is available at the builder level

## Requirements

- Java 17+
- Kotlin/JVM targeting Java 17
- JDA 6
- `AnnotatedEventManager` for event dispatch

## Installation

Choose one publication source. Replace `VERSION` with a published release tag or package version. Add JDA separately because Flight declares it as a compile-only dependency.

### JitPack (public, no token required)

```kotlin
// build.gradle.kts
repositories {
  mavenCentral()
  maven("https://m2.dv8tion.net/releases")
  maven("https://jitpack.io")
}

dependencies {
  implementation("com.github.CeraCharlesCC:Flight:VERSION")
  implementation("net.dv8tion:JDA:6.1.2")
}
```

Use the exact owner/repository casing shown above. JitPack maps this single-module project to `com.github.CeraCharlesCC:Flight`, even though the Gradle publication uses `flight-jda6`. These coordinates are confirmed by the [5.1.1 POM](https://jitpack.io/com/github/CeraCharlesCC/Flight/5.1.1/Flight-5.1.1.pom) and [module metadata](https://jitpack.io/com/github/CeraCharlesCC/Flight/5.1.1/Flight-5.1.1.module). See JitPack's [single-module coordinate rules](https://docs.jitpack.io/building/#multi-module-projects).

### GitHub Packages

```kotlin
// build.gradle.kts
repositories {
  mavenCentral()
  maven("https://m2.dv8tion.net/releases")
  maven("https://maven.pkg.github.com/ceracharlescc/Flight") {
    credentials {
      username = System.getenv("GITHUB_USER")
      password = System.getenv("GITHUB_TOKEN")
    }
    content { includeGroup("io.github.ceracharlescc") }
  }
}

dependencies {
  implementation("io.github.ceracharlescc:flight-jda6:VERSION")
  implementation("net.dv8tion:JDA:6.1.2")
}
```

Set `GITHUB_USER` to your GitHub username and `GITHUB_TOKEN` to a personal access token (classic) with `read:packages`. GitHub's Maven/Gradle registry [requires authentication even for public packages](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry#authenticating-to-github-packages). Choose a version available in GitHub Packages; a Git tag alone does not create that publication.

## Quick start

Create a `CommandClient`, register your cogs explicitly, then add the client as a JDA listener.

```kotlin
import me.devoxin.flight.api.CommandClient
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.hooks.AnnotatedEventManager
import net.dv8tion.jda.api.requests.GatewayIntent

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
  .enableIntents(GatewayIntent.MESSAGE_CONTENT) // for the prefix commands below
  .setEventManager(AnnotatedEventManager())
  .addEventListeners(commandClient)
  .build()
  .awaitReady()

val syncResult = commandClient.syncCommands(jda).join()
```

Enable the Message Content Intent in the Discord Developer Portal for the prefix-command example above. Register every cog before syncing. Sync replaces the full application-command set in each targeted scope; review `planCommandSync()` first if necessary.

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

Flight 5 uses an explicit `parent` / `group` model.

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

After JDA is ready and all cogs are registered:

```kotlin
jda.awaitReady()
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
    includeGlobal = false,
    guildIds = setOf(123456789012345678L)
  )
)
```

`guildIds` filters guild scopes; it does not disable global sync or copy global commands into a guild. Use `@GuildIds` on commands that should belong to a guild. A guild-only operation requires `includeGlobal = false`.

Important sync behavior:

- sync is authoritative per targeted scope
- targeted global/guild scopes are replaced in full
- command export already respects `@GuildIds`
- permission-bearing application commands are exported as guild-context commands

## Error handling

Flight 5 has a builder-level centralized error-handler API.

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

### Private interaction errors

Request ephemeral error responses without wrapping the standard handler:

```kotlin
val client = CommandClient.builder()
  .useDefaultErrorHandler {
    ephemeralResponses = true
  }
  .build()
```

This applies to slash commands and context menus. Prefix-command errors still use
the message channel. Initial interaction replies and follow-up errors request the
configured visibility. When the original response is edited after deferral, it
keeps the visibility chosen by `defer` / `deferAsync`; `ephemeralResponses` cannot
make a public deferred response private. Defer privately when the original error
response must remain private.

### Custom response rendering

Keep Flight's failure routing, message formatters, and logging while replacing
only the delivery step. For example, an application can render errors with
Components V2:

```kotlin
val client = CommandClient.builder()
  .useDefaultErrorHandler {
    cooldownMessage = { failure ->
      val language = failure.context.locale?.toLocale()?.language
      if (language == "ja") "しばらく待ってから再試行してください。"
      else "Please wait before trying again."
    }
    responseSender = { context, message ->
      context.respond {
        useComponentsV2(true)
        setComponents(listOf(net.dv8tion.jda.api.components.textdisplay.TextDisplay.of(message)))
      }
    }
  }
  .build()
```

`responseSender` receives the context and the formatted message for argument,
check, cooldown, permission, parse, and execution failures. Return the send future
so Flight can log asynchronous failures; synchronous send exceptions are logged
as well. A custom sender controls its own visibility and does not automatically
apply `ephemeralResponses`. Return `null` from `checkFailureMessage` to suppress
that check response entirely.

Unknown-command responses keep their separate opt-in message-channel path.
Framework and autocomplete failures remain log-only.

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

8. **JDA invite arguments need an explicit choice**
   - use `me.devoxin.flight.api.arguments.types.Invite` with Flight's default parsers
   - call `resolveAsync()` when you need the resolved JDA invite
   - a parameter typed as `net.dv8tion.jda.api.entities.Invite` requires your own parser

## Building and release versions

Run `./gradlew clean build` to compile and run the tests. Push and pull-request CI runs the build and validates local Maven publication; JitPack also runs the build before publishing to its local Maven repository.

The GitHub Packages workflow runs when a release is published. Its numeric tag supplies the Maven version, with an optional leading `v` removed (`5.1.1` and `v5.1.1` both publish `5.1.1`). JitPack uses its exact requested tag or commit as `VERSION`. Both paths override Gradle's version with `-Pversion=...`, including the embedded Flight version. The value in `gradle.properties` remains the fallback for ordinary local builds.

To inspect the Maven publication locally without uploading it:

```sh
./gradlew build publishToMavenLocal -Pversion=5.1.1
```
