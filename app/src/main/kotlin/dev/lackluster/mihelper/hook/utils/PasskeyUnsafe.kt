/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Adapted from Howard20181/HyperPasskey UnsafeUtils.kt.
 */

package dev.lackluster.mihelper.hook.utils

import android.graphics.Point
import sun.misc.Unsafe
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/** Small ART-aware field writer used by the HyperPasskey port. */
object PasskeyUnsafe {
    private val unsafe: Unsafe by lazy {
        Unsafe::class.java.getDeclaredField("theUnsafe").let { field ->
            field.isAccessible = true
            field.get(null) as Unsafe
        }
    }

    private val fieldOffsetOffset: Long by lazy { findFieldOffsetOffset() }

    fun setStaticBoolean(field: Field, value: Boolean) {
        runCatching {
            field.isAccessible = true
            field.setBoolean(null, value)
        }.onSuccess { return }

        resolveField(field, null)
        val offset = unsafe.getInt(field, fieldOffsetOffset).toLong()
        unsafe.putBoolean(field.declaringClass, offset, value)
    }

    fun setObject(field: Field, target: Any, value: Any?) {
        runCatching {
            field.isAccessible = true
            field.set(target, value)
        }.onSuccess { return }

        resolveField(field, target)
        val offset = unsafe.getInt(field, fieldOffsetOffset).toLong()
        if (Modifier.isStatic(field.modifiers)) {
            unsafe.putObject(field.declaringClass, offset, value)
        } else if (Modifier.isVolatile(field.modifiers)) {
            unsafe.putObjectVolatile(target, offset, value)
        } else {
            unsafe.putObject(target, offset, value)
        }
    }

    private fun resolveField(field: Field, target: Any?) {
        runCatching {
            field.isAccessible = true
            if (Modifier.isStatic(field.modifiers)) field.get(null) else field.get(target)
        }
    }

    private fun findFieldOffsetOffset(): Long {
        runCatching {
            val offsetField = Field::class.java.getDeclaredField("offset")
            offsetField.isAccessible = true
            offsetField.getInt(offsetField)
            return unsafe.objectFieldOffset(offsetField)
        }

        val probeField = Point::class.java.getDeclaredField("x")
        probeField.getInt(Point())
        val expected = unsafe.objectFieldOffset(probeField).toInt()
        for (candidate in 8 until 256 step 4) {
            val candidateLong = candidate.toLong()
            if (unsafe.getInt(probeField, candidateLong) != expected) continue
            val modified = expected.inv()
            unsafe.putInt(probeField, candidateLong, modified)
            val current = unsafe.objectFieldOffset(probeField).toInt()
            unsafe.putInt(probeField, candidateLong, expected)
            if (current == modified) return candidateLong
        }
        throw NoSuchFieldException("Unable to resolve java.lang.reflect.Field ART offset")
    }
}
