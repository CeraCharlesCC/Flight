package me.devoxin.flight.internal.parsers

import me.devoxin.flight.api.context.MessageContext
import me.devoxin.flight.internal.utils.EnumUtils

/**
 * A parser for enum types. Resolves input by matching against enum constant names
 * (case-insensitive) or display names (if the enum has a single String property).
 */
class EnumParser<T : Enum<T>>(private val enumClass: Class<T>) : Parser<T> {

    override fun parse(ctx: MessageContext, param: String): T? {
        return EnumUtils.resolveEnum(enumClass, param)
    }
}
