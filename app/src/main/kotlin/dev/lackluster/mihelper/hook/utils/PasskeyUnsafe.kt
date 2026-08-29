/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Adapted from Howard20181/HyperPasskey UnsafeUtils.kt.
 */

package dev.lackluster.mihelper.hook.utils

import android.graphics.Point
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Small ART-aware field writer used by the HyperPasskey port.
 *
 * Unsafe is resolved reflectively so XiaomiHelper does not need HyperPasskey's hidden-API stub module
 * merely to compile this compatibility path.
 */
object PasskeyUnsafe {
    private val unsafeClass: Class<*> by lazy { Class.forName("sun.misc.Unsafe") }
    private val unsafe: Any by lazy {
        unsafeClass.getDeclaredField("theUnsafe").let { field ->
            field.isAccessible = true
            field.get(null)
        }
    }

    private val objectFieldOffsetMethod: Method by lazy {
        unsafeClass.getMethod("objectFieldOffset", Field::class.java)
    }
    private val getIntMethod: Method by lazy {
        unsafeClass.getMethod("getInt", Any::class.java, Long::class.javaPrimitiveType)
    }
    private val putIntMethod: Method by lazy {
        unsafeClass.getMethod(
            "putInt",
            Any::class.java,
            Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
    }
    private val putBooleanMethod: Method by lazy {
        unsafeClass.getMethod(
            "putBoolean",
            Any::class.java,
            Long::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
    }
    private val putObjectMethod: Method by lazy {
        unsafeClass.getMethod(
            "putObject",
            Any::class.java,
            Long::class.javaPrimitiveType,
            Any::class.java,
        )
    }
    private val putObjectVolatileMethod: Method by lazy {
        unsafeClass.getMethod(
            "putObjectVolatile",
            Any::class.java,
            Long::class.javaPrimitiveType,
            Any::class.java,
        )
    }

    private val fieldOffsetOffset: Long by lazy { findFieldOffsetOffset() }

    fun setStaticBoolean(field: Field, value: Boolean) {
        runCatching {
            field.isAccessible = true
            field.setBoolean(null, value)
        }.onSuccess { return }

        resolveField(field, null)
        val offset = getInt(field, fieldOffsetOffset).toLong()
        putBoolean(field.declaringClass, offset, value)
    }

    fun setObject(field: Field, target: Any, value: Any?) {
        runCatching {
            field.isAccessible = true
            field.set(target, value)
        }.onSuccess { return }

        resolveField(field, target)
        val offset = getInt(field, fieldOffsetOffset).toLong()
        if (Modifier.isStatic(field.modifiers)) {
            putObject(field.declaringClass, offset, value)
        } else if (Modifier.isVolatile(field.modifiers)) {
            putObjectVolatile(target, offset, value)
        } else {
            putObject(target, offset, value)
        }
    }

    private fun resolveField(field: Field, target: Any?) {
        runCatching {
            field.isAccessible = true
            if (Modifier.isStatic(field.modifiers)) field.get(null) else field.get(target)
        }
    }

    private fun objectFieldOffset(field: Field): Long =
        (objectFieldOffsetMethod.invoke(unsafe, field) as Number).toLong()

    private fun getInt(target: Any, offset: Long): Int =
        (getIntMethod.invoke(unsafe, target, offset) as Number).toInt()

    private fun putInt(target: Any, offset: Long, value: Int) {
        putIntMethod.invoke(unsafe, target, offset, value)
    }

    private fun putBoolean(target: Any, offset: Long, value: Boolean) {
        putBooleanMethod.invoke(unsafe, target, offset, value)
    }

    private fun putObject(target: Any, offset: Long, value: Any?) {
        putObjectMethod.invoke(unsafe, target, offset, value)
    }

    private fun putObjectVolatile(target: Any, offset: Long, value: Any?) {
        putObjectVolatileMethod.invoke(unsafe, target, offset, value)
    }

    private fun findFieldOffsetOffset(): Long {
        runCatching {
            val offsetField = Field::class.java.getDeclaredField("offset")
            offsetField.isAccessible = true
            offsetField.getInt(offsetField)
            return objectFieldOffset(offsetField)
        }

        val probeField = Point::class.java.getDeclaredField("x")
        probeField.getInt(Point())
        val expected = objectFieldOffset(probeField).toInt()
        for (candidate in 8 until 256 step 4) {
            val candidateLong = candidate.toLong()
            if (getInt(probeField, candidateLong) != expected) continue
            val modified = expected.inv()
            putInt(probeField, candidateLong, modified)
            val current = objectFieldOffset(probeField).toInt()
            putInt(probeField, candidateLong, expected)
            if (current == modified) return candidateLong
        }
        throw NoSuchFieldException("Unable to resolve java.lang.reflect.Field ART offset")
    }
}
