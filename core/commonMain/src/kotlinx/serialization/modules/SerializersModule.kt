/*
 * Copyright 2017-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.serialization.modules

import kotlinx.serialization.*
import kotlinx.serialization.internal.*
import kotlin.internal.*
import kotlin.jvm.*
import kotlin.native.concurrent.*
import kotlin.reflect.*

/**
 * [SerializersModule] is a collection of serializers used by [ContextualSerializer] and [PolymorphicSerializer]
 * to override or provide serializers at the runtime, whereas at the compile-time they provided by the serialization plugin.
 * It can be considered as a map where serializers can be found using their statically known KClasses.
 *
 * To enable runtime serializers resolution, one of the special annotations must be used on target types
 * ([Polymorphic] or [Contextual]), and a serial module with serializers should be used during construction of [SerialFormat].
 *
 * @see Contextual
 * @see Polymorphic
 */
public sealed class SerializersModule {

    /**
     * Returns a contextual serializer associated with a given [kclass].
     * This method is used in context-sensitive operations on a property marked with [Contextual] by a [ContextualSerializer]
     */
    @ExperimentalSerializationApi
    @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
    @Deprecated(
        "Deprecated in favor of overload with default parameter",
        ReplaceWith("getContextual(kclass)"),
        DeprecationLevel.ERROR
    )
    @LowPriorityInOverloadResolution
    public fun <T : Any> getContextual(kclass: KClass<T>): KSerializer<T>? =
        getContextual(kclass, EMPTY_SERIALIZER_ARRAY)

    @ExperimentalSerializationApi
    public abstract fun <T : Any> getContextual(
        kClass: KClass<T>,
        typeArgumentsSerializers: Array<KSerializer<*>> = EMPTY_SERIALIZER_ARRAY
    ): KSerializer<T>?

    /**
     * Returns a polymorphic serializer registered for a class of the given [value] in the scope of [baseClass].
     */
    @ExperimentalSerializationApi
    public abstract fun <T : Any> getPolymorphic(baseClass: KClass<in T>, value: T): SerializationStrategy<T>?

    /**
     * Returns a polymorphic deserializer registered for a [serializedClassName] in the scope of [baseClass]
     * or default value constructed from [serializedClassName] if a default serializer provider was registered.
     */
    @ExperimentalSerializationApi
    public abstract fun <T : Any> getPolymorphic(baseClass: KClass<in T>, serializedClassName: String?): DeserializationStrategy<out T>?

    /**
     * Copies contents of this module to the given [collector].
     */
    @ExperimentalSerializationApi
    public abstract fun dumpTo(collector: SerializersModuleCollector)
}

/**
 * A [SerializersModule] which is empty and always returns `null`.
 */
@SharedImmutable
@ExperimentalSerializationApi
public val EmptySerializersModule: SerializersModule = SerialModuleImpl(emptyMap(), emptyMap(), emptyMap(), emptyMap())

/**
 * Returns a combination of two serial modules
 *
 * If serializer for some class presents in both modules, a [SerializerAlreadyRegisteredException] is thrown.
 * To overwrite serializers, use [SerializersModule.overwriteWith] function.
 */
public operator fun SerializersModule.plus(other: SerializersModule): SerializersModule = SerializersModule {
    include(this@plus)
    include(other)
}

/**
 * Returns a combination of two serial modules
 *
 * If serializer for some class presents in both modules, result module
 * will contain serializer from [other] module.
 */
@OptIn(ExperimentalSerializationApi::class)
public infix fun SerializersModule.overwriteWith(other: SerializersModule): SerializersModule = SerializersModule {
    include(this@overwriteWith)
    other.dumpTo(object : SerializersModuleCollector {
        override fun <T : Any> contextual(kClass: KClass<T>, serializer: KSerializer<T>) {
            registerSerializer(kClass, ContextualProvider.Argless(serializer), allowOverwrite = true)
        }

        override fun <T : Any> contextual(
            kClass: KClass<T>,
            provider: (serializers: Array<KSerializer<*>>) -> KSerializer<*>
        ) {
            registerSerializer(kClass, ContextualProvider.WithTypeArguments(provider), allowOverwrite = true)
        }

        override fun <Base : Any, Sub : Base> polymorphic(
            baseClass: KClass<Base>,
            actualClass: KClass<Sub>,
            actualSerializer: KSerializer<Sub>
        ) {
            registerPolymorphicSerializer(baseClass, actualClass, actualSerializer, allowOverwrite = true)
        }

        override fun <Base : Any> polymorphicDefault(
            baseClass: KClass<Base>,
            defaultSerializerProvider: (className: String?) -> DeserializationStrategy<out Base>?
        ) {
            registerDefaultPolymorphicSerializer(baseClass, defaultSerializerProvider, allowOverwrite = true)
        }
    })
}

// Implementation details below

/**
 * A default implementation of [SerializersModule]
 * which uses hash maps to store serializers associated with KClasses.
 */
@Suppress("UNCHECKED_CAST")
@OptIn(ExperimentalSerializationApi::class)
internal class SerialModuleImpl(
    private val class2ContextualFactory: Map<KClass<*>, ContextualProvider>,
    @JvmField val polyBase2Serializers: Map<KClass<*>, Map<KClass<*>, KSerializer<*>>>,
    private val polyBase2NamedSerializers: Map<KClass<*>, Map<String, KSerializer<*>>>,
    private val polyBase2DefaultProvider: Map<KClass<*>, PolymorphicProvider<*>>
) : SerializersModule() {

    override fun <T : Any> getPolymorphic(baseClass: KClass<in T>, value: T): SerializationStrategy<T>? {
        if (!value.isInstanceOf(baseClass)) return null
        return polyBase2Serializers[baseClass]?.get(value::class) as? SerializationStrategy<T>
    }

    override fun <T : Any> getPolymorphic(baseClass: KClass<in T>, serializedClassName: String?): DeserializationStrategy<out T>? {
        // Registered
        val registered = polyBase2NamedSerializers[baseClass]?.get(serializedClassName) as? KSerializer<out T>
        if (registered != null) return registered
        // Default
        return (polyBase2DefaultProvider[baseClass] as? PolymorphicProvider<T>)?.invoke(serializedClassName)
    }

    override fun <T : Any> getContextual(kClass: KClass<T>, typeArgumentsSerializers: Array<KSerializer<*>>): KSerializer<T>? {
        return (class2ContextualFactory[kClass]?.invoke(typeArgumentsSerializers)) as? KSerializer<T>?
    }

    override fun dumpTo(collector: SerializersModuleCollector) {
        class2ContextualFactory.forEach { (kclass, serial) ->
            when (serial) {
                is ContextualProvider.Argless -> collector.contextual(
                    kclass as KClass<Any>,
                    serial.serializer as KSerializer<Any>
                )
                is ContextualProvider.WithTypeArguments -> collector.contextual(kclass, serial.provider)
            }
        }

        polyBase2Serializers.forEach { (baseClass, classMap) ->
            classMap.forEach { (actualClass, serializer) ->
                collector.polymorphic(
                    baseClass as KClass<Any>,
                    actualClass as KClass<Any>,
                    serializer.cast()
                )
            }
        }

        polyBase2DefaultProvider.forEach { (baseClass, provider) ->
            collector.polymorphicDefault(baseClass as KClass<Any>, provider as (PolymorphicProvider<out Any>))
        }
    }
}

internal typealias PolymorphicProvider<Base> = (className: String?) -> DeserializationStrategy<out Base>?

/** This class is needed to support re-registering the same static (argless) serializers:
 *
 * ```
 * val m1 = serializersModuleOf(A::class, A.serializer())
 * val m2 = serializersModuleOf(A::class, A.serializer())
 * val aggregate = m1 + m2 // should not throw
 * ```
 */
internal sealed class ContextualProvider {
    abstract operator fun invoke(typeArgumentsSerializers: Array<KSerializer<*>>): KSerializer<*>

    class Argless(val serializer: KSerializer<*>) : ContextualProvider() {
        override fun invoke(typeArgumentsSerializers: Array<KSerializer<*>>): KSerializer<*> = serializer

        override fun equals(other: Any?): Boolean = other is Argless && other.serializer == this.serializer

        override fun hashCode(): Int = serializer.hashCode()
    }

    class WithTypeArguments(val provider: (typeArgumentsSerializers: Array<KSerializer<*>>) -> KSerializer<*>) :
        ContextualProvider() {
        override fun invoke(typeArgumentsSerializers: Array<KSerializer<*>>): KSerializer<*> =
            provider(typeArgumentsSerializers)
    }

}
