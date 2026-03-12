package me.devoxin.flight.api.util

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder

class DSLMessageCreateBuilder : MessageCreateBuilder() {
    fun embed(builder: EmbedBuilder.() -> Unit) {
        addEmbeds(EmbedBuilder().apply(builder).build())
    }
}
