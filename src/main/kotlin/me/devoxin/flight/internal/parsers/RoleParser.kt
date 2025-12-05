package me.devoxin.flight.internal.parsers

import me.devoxin.flight.api.context.MessageContext
import net.dv8tion.jda.api.entities.Role

class RoleParser : Parser<Role> {
    override fun parse(ctx: MessageContext, param: String): Role? {
        val snowflake = SnowflakeParser.INSTANCE.parse(ctx, param)?.resolved

        return when {
            snowflake != null -> ctx.guild?.getRoleById(snowflake)
            else -> if (param == "everyone") ctx.guild?.publicRole else ctx.guild?.roleCache?.firstOrNull { it.name == param }
        }
    }
}
