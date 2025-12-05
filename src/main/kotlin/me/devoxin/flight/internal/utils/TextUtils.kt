package me.devoxin.flight.internal.utils


object TextUtils {
    fun split(content: String, limit: Int = 2000): List<String> {
        if (content.isEmpty()) {
            return emptyList()
        }
        if (content.length <= limit) {
            return listOf(content)
        }

        val pages = mutableListOf<String>()
        val chunk = StringBuilder(limit)

        fun flushChunk() {
            if (chunk.isNotEmpty()) {
                pages += chunk.toString()
                chunk.setLength(0)
            }
        }

        val lines = content.split('\n')

        lines.forEachIndexed { index, rawLine ->
            val line = if (index == lines.lastIndex) rawLine else rawLine + "\n"
            var cursor = 0

            while (cursor < line.length) {
                val remainingInLine = line.length - cursor
                val remainingSpace = limit - chunk.length

                if (remainingSpace == 0) {
                    flushChunk()
                    continue
                }

                val toTake = minOf(remainingInLine, remainingSpace)
                chunk.append(line, cursor, cursor + toTake)
                cursor += toTake

                if (chunk.length == limit) {
                    flushChunk()
                }
            }
        }

        flushChunk()
        return pages
    }

    fun capitalise(s: String): String =
        s.lowercase().replaceFirstChar { it.uppercase() }

    fun plural(num: Number): String = if (num == 1) "" else "s"

    fun truncate(s: String, maxLength: Int) =
        s.takeIf { it.length <= maxLength } ?: (s.take(maxLength - 3) + "...")

    fun toTitleCase(s: String) =
        s.split(" +".toRegex()).joinToString(" ", transform = ::capitalise)
}
