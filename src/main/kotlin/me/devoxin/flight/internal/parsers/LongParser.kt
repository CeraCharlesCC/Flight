package me.devoxin.flight.internal.parsers

import me.devoxin.flight.api.context.MessageContext

class LongParser : Parser<Long> {
    override fun parse(ctx: MessageContext, param: String): Long? {
        return param.toLongOrNull()
    }
}
