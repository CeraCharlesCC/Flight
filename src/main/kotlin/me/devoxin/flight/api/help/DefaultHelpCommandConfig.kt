package me.devoxin.flight.api.help

data class DefaultHelpCommandConfig(
    var enabled: Boolean = true,
    var showParameterTypes: Boolean = false,
    var messages: HelpMessages = HelpMessages()
)
