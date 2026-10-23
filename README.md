# Flight-jda6

[![Release](https://jitpack.io/v/CeraCharlesCC/Flight.svg)](https://jitpack.io/#CeraCharlesCC/Flight)

Release target: **5.1.10**. See [build and release instructions](docs/new-feature-and-migration.md#building-and-release-versions).

Flight is a Kotlin command framework for JDA 6. It supports slash commands, context menus, optional message commands, typed argument parsers, and explicit command registration and synchronization.

Requires Java 17+, Kotlin/JVM targeting Java 17, JDA 6, and JDA's `AnnotatedEventManager`. The examples describe the current source tree; use a release containing the APIs you need. See the [feature and migration guide](docs/new-feature-and-migration.md) for the full API walkthrough.

## Installation

These examples target **5.1.10**. The release tag and the package on your chosen registry must be published before these coordinates resolve. Add JDA separately because Flight declares it as a compile-only dependency.

### JitPack (public, no token required)

```kotlin
// build.gradle.kts
repositories {
  mavenCentral()
  maven("https://m2.dv8tion.net/releases")
  maven("https://jitpack.io")
}

dependencies {
  implementation("com.github.CeraCharlesCC:Flight:5.1.10")
  implementation("net.dv8tion:JDA:6.1.2")
}
```

Use the exact owner/repository casing shown above. JitPack maps this single-module project to `com.github.CeraCharlesCC:Flight`, while GitHub Packages uses `io.github.ceracharlescc:flight-jda6`. See JitPack's [single-module coordinate rules](https://docs.jitpack.io/building/#multi-module-projects).

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
  implementation("io.github.ceracharlescc:flight-jda6:5.1.10")
  implementation("net.dv8tion:JDA:6.1.2")
}
```

Set `GITHUB_USER` to your GitHub username and `GITHUB_TOKEN` to a personal access token (classic) with `read:packages`. GitHub's Maven/Gradle registry [requires authentication even for public packages](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry#authenticating-to-github-packages). Choose a version available in GitHub Packages; a Git tag alone does not create that publication.

## Quick start

Commands live in cogs. Use `Context.respond(...)` for handlers shared by slash and message commands so interaction invocations are acknowledged correctly.

```kotlin
import me.devoxin.flight.api.CommandClient
import me.devoxin.flight.api.annotations.Command
import me.devoxin.flight.api.command.Cog
import me.devoxin.flight.api.context.Context
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.hooks.AnnotatedEventManager
import net.dv8tion.jda.api.requests.GatewayIntent

class UtilityCog : Cog {
  @Command(description = "Ping, pong!")
  fun ping(ctx: Context) {
    ctx.respond("Pong!")
  }
}

val commandClient = CommandClient.builder()
  .setPrefixes("!", "?")
  .registerDefaultParsers()
  .register(UtilityCog())
  .useDefaultErrorHandler()
  .build()

val jda = JDABuilder.createDefault(yourBotToken)
  .enableIntents(GatewayIntent.MESSAGE_CONTENT) // for the prefix commands below
  .setEventManager(AnnotatedEventManager())
  .addEventListeners(commandClient)
  .build()
  .awaitReady()

// Explicitly publish the registered slash commands and context menus to Discord.
val syncResult = commandClient.syncCommands(jda).join()
```

Register all cogs before syncing. Sync replaces the complete application-command set in each targeted scope, including when the payload is empty. Inspect `commandClient.planCommandSync()` first when you need to review the desired state. A guild-only sync must set `CommandSyncOptions(includeGlobal = false, guildIds = setOf(guildId))`; guild IDs filter guild scopes and do not move global commands into a guild. See [command sync](docs/new-feature-and-migration.md#command-sync).

Flight requires `AnnotatedEventManager`; `InterfacedEventManager` is unsupported. The prefix-command example enables `MESSAGE_CONTENT`; enable the Message Content Intent in the Discord Developer Portal for your bot too.

### Registering cogs

Register the exact instances your application constructs, including those supplied by dependency injection:

```kotlin
val cogs: List<Cog> = container.resolveAll()

val commandClient = CommandClient.builder()
  .setPrefixes("!")
  .registerDefaultParsers()
  .registerAll(cogs)
  .build()
```

Package scanning remains an optional convenience when Flight should construct the cogs itself:

```kotlin
commandClient.commands.register("my.bot.commands")
```

Register scanned cogs before calling `syncCommands(...)` too.

### Subcommands and groups

Attach subcommands to a named top-level command with `parent`. A cog can contain multiple top-level commands; grouped subcommands also specify `group` and require a matching `@SubCommandGroup` on the parent. Permission requirements on the parent apply to its subcommands.

```kotlin
import me.devoxin.flight.api.annotations.SubCommand
import me.devoxin.flight.api.annotations.SubCommandGroup
import net.dv8tion.jda.api.Permission

class SettingsCog : Cog {
  @Command(description = "Server settings", userPermissions = [Permission.MANAGE_SERVER])
  @SubCommandGroup(name = "display", description = "Display settings")
  fun settings(ctx: Context) {
    ctx.respond("Choose a settings subcommand.")
  }

  @SubCommand(parent = "settings", description = "Show the server prefix")
  fun prefix(ctx: Context) {
    ctx.respond("The default prefix is !")
  }

  @SubCommand(parent = "settings", group = "display", description = "Show the language")
  fun locale(ctx: Context) {
    ctx.respond("The default language is English.")
  }
}
```

These commands are invoked as `/settings prefix` or `/settings display locale`, or with the configured message prefix. Slash-command parents with subcommands act as containers; Discord invokes the selected child. A top-level slash command can have root options or subcommands/groups, but cannot mix both.

For autocomplete, centralized error handlers, parsers, and migration details, see the [feature and migration guide](docs/new-feature-and-migration.md).
