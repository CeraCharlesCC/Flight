package me.devoxin.flight.internal.utils

/** Utility functions for enum introspection and choice generation. */
object EnumUtils {

    /**
     * Generates a list of [StringChoiceData] from an enum class. The display name is determined by:
     * 1. If the enum has a single String property, use its value as the display name
     * 2. Otherwise, use the enum constant name
     *
     * The value is always the enum constant name.
     */
    fun getEnumChoices(enumClass: Class<*>): List<StringChoiceData> {
        require(enumClass.isEnum) { "${enumClass.simpleName} is not an enum class" }

        return enumClass.enumConstants.map { constant ->
            val displayName = getEnumDisplayName(constant)
            val value = (constant as Enum<*>).name
            StringChoiceData(displayName, value)
        }
    }

    /**
     * Extracts the display name from an enum constant. If the enum has a single String property,
     * returns that property's value. Otherwise, returns the enum constant name.
     */
    fun getEnumDisplayName(enumConstant: Any): String {
        val enumClass = enumConstant::class.java

        // Find non-synthetic, non-enum fields (i.e., declared properties)
        val propertyFields =
            enumClass.declaredFields.filter { field ->
                !field.isEnumConstant && !field.isSynthetic && field.name != "\$VALUES"
            }

        // If there's exactly one String property, use its value as the display name
        val singleStringField = propertyFields.singleOrNull { it.type == String::class.java }

        if (singleStringField != null) {
            singleStringField.isAccessible = true
            val value = singleStringField.get(enumConstant) as? String
            if (value != null) {
                return value
            }
        }

        // Fallback to enum constant name
        return (enumConstant as Enum<*>).name
    }

    /**
     * Resolves an enum constant from a string input. Matches against:
     * 1. The enum constant name (case-insensitive)
     * 2. The display name (if the enum has a single String property)
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Enum<T>> resolveEnum(enumClass: Class<T>, input: String): T? {
        val constants = enumClass.enumConstants ?: return null

        // Try to match by enum name (case-insensitive)
        val byName = constants.find { it.name.equals(input, ignoreCase = true) }
        if (byName != null) return byName

        // Try to match by display name
        val byDisplayName =
            constants.find { getEnumDisplayName(it).equals(input, ignoreCase = true) }
        if (byDisplayName != null) return byDisplayName

        return null
    }

    /**
     * A data holder for StringChoice since annotation classes can't be instantiated directly. This
     * mirrors [StringChoice] for internal use.
     */
    data class StringChoiceData(val key: String, val value: String)
}
