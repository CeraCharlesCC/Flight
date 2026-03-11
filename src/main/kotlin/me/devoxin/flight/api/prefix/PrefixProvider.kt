package me.devoxin.flight.api.prefix

import net.dv8tion.jda.api.entities.Message

interface PrefixProvider {

    fun provide(message: Message): List<String>
}
