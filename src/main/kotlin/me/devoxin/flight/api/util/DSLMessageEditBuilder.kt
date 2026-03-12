package me.devoxin.flight.api.util

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder

class DSLMessageEditBuilder : MessageEditBuilder() {
    fun embed(builder: EmbedBuilder.() -> Unit) {
        setEmbeds(EmbedBuilder().apply(builder).build())
    }
}