package me.devoxin.flight.api.help

data class HelpMessages(
    var noCommandOrCogFound: String = "No commands or cogs found with that name.",
    var commandsInCog: String = "Commands in %s",
    var pageWrapper: String = "```\n%s```"
) {
    fun formatCommandsInCog(cogName: String): String = commandsInCog.format(cogName)

    fun formatPage(page: String): String = pageWrapper.format(page)
}
