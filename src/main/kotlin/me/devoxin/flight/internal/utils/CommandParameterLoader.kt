package me.devoxin.flight.internal.utils

import me.devoxin.flight.api.annotations.Autocomplete
import me.devoxin.flight.api.annotations.Choices
import me.devoxin.flight.api.annotations.Describe
import me.devoxin.flight.api.annotations.Greedy
import me.devoxin.flight.api.annotations.Name
import me.devoxin.flight.api.annotations.Range
import me.devoxin.flight.api.annotations.Tentative
import me.devoxin.flight.api.autocomplete.AutocompleteHandler
import me.devoxin.flight.api.autocomplete.expectedCogTypeOf
import me.devoxin.flight.api.command.Cog
import me.devoxin.flight.internal.arguments.Argument
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.jvm.jvmErasure

internal object CommandParameterLoader {
    fun loadParameters(cog: Cog, parameters: List<KParameter>): List<Argument> {
        val arguments = mutableListOf<Argument>()

        for (parameter in parameters) {
            val name = parameter.findAnnotation<Name>()?.value ?: parameter.name ?: parameter.index.toString()
            val description = parameter.findAnnotation<Describe>()?.value ?: "No description available."
            val range = parameter.findAnnotation<Range>()
            val choices = parameter.findAnnotation<Choices>()
            val type = parameter.type.jvmErasure.javaObjectType
            val isGreedy = parameter.hasAnnotation<Greedy>()
            val isOptional = parameter.isOptional
            val isNullable = parameter.type.isMarkedNullable
            val isTentative = parameter.hasAnnotation<Tentative>()
            val autocompleteHandler = parameter.findAnnotation<Autocomplete>()?.value?.let {
                resolveAutocompleteHandler(cog, parameter, it)
            }

            if (isTentative && !(isNullable || isOptional)) {
                throw IllegalStateException(
                    "${parameter.name} is marked as tentative, but does not have a default value and is not marked nullable!"
                )
            }

            arguments.add(
                Argument(
                    name,
                    description,
                    range,
                    choices,
                    type,
                    isGreedy,
                    isOptional,
                    isNullable,
                    isTentative,
                    autocompleteHandler,
                    cog,
                    parameter
                )
            )
        }

        return arguments
    }

    private fun resolveAutocompleteHandler(
        cog: Cog,
        parameter: KParameter,
        handlerType: KClass<out AutocompleteHandler<*>>
    ): AutocompleteHandler<*> {
        val expectedCogType = expectedCogTypeOf(handlerType)

        if (expectedCogType != null && !expectedCogType.isInstance(cog)) {
            throw IllegalStateException(
                "Autocomplete handler ${handlerType.qualifiedName} for parameter ${parameter.name} expects cog type " +
                    "${expectedCogType.qualifiedName}, but was registered on ${cog::class.qualifiedName}."
            )
        }

        return try {
            handlerType.objectInstance ?: handlerType.createInstance()
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Failed to instantiate autocomplete handler ${handlerType.qualifiedName} for parameter ${parameter.name}.",
                throwable
            )
        }
    }
}
