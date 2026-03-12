package me.devoxin.flight.internal.parsers

import me.devoxin.flight.api.context.MessageContext
import net.dv8tion.jda.api.entities.emoji.CustomEmoji
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.entities.emoji.EmojiUnion
import net.dv8tion.jda.api.entities.emoji.UnicodeEmoji

class EmojiParser<T : Any>(
    private val transformer: (EmojiUnion) -> T?
) : Parser<T> {
    override fun parse(ctx: MessageContext, param: String): T? {
        if (!looksLikeEmojiToken(param)) {
            return null
        }

        val emoji = try {
            Emoji.fromFormatted(param)
        } catch (_: IllegalArgumentException) {
            return null
        }

        return transformer(emoji)
    }

    companion object {
        private const val ZERO_WIDTH_JOINER = 0x200D
        private const val VARIATION_SELECTOR_16 = 0xFE0F
        private const val COMBINING_ENCLOSING_KEYCAP = 0x20E3

        private val customEmojiPattern = Regex("^<(a)?:\\w+:\\d{17,21}>$")
        private val codepointPattern = Regex("^(?i:U\\+[0-9A-F]{4,6})(?:\\s+(?i:U\\+[0-9A-F]{4,6}))*$")

        fun forEmoji(): EmojiParser<Emoji> = EmojiParser { it }

        fun forEmojiUnion(): EmojiParser<EmojiUnion> = EmojiParser { it }

        fun forUnicodeEmoji(): EmojiParser<UnicodeEmoji> = EmojiParser { emoji ->
            if (emoji.type == Emoji.Type.UNICODE) emoji.asUnicode() else null
        }

        fun forCustomEmoji(): EmojiParser<CustomEmoji> = EmojiParser { emoji ->
            if (emoji.type == Emoji.Type.CUSTOM) emoji.asCustom() else null
        }

        internal fun looksLikeEmojiToken(param: String): Boolean {
            if (param.isBlank()) {
                return false
            }

            if (customEmojiPattern.matches(param) || codepointPattern.matches(param)) {
                return true
            }

            if (param.any(Char::isWhitespace)) {
                return false
            }

            return param.codePoints().anyMatch { codePoint ->
                codePoint == ZERO_WIDTH_JOINER ||
                    codePoint == VARIATION_SELECTOR_16 ||
                    codePoint == COMBINING_ENCLOSING_KEYCAP ||
                    codePoint in 0x1F000..0x1FAFF ||
                    codePoint in 0x2600..0x27BF ||
                    codePoint in 0x1F1E6..0x1F1FF ||
                    Character.getType(codePoint) == Character.OTHER_SYMBOL.toInt()
            }
        }
    }
}
