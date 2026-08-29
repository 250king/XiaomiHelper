package dev.lackluster.mihelper.hook.rules.android

import android.hardware.display.DisplayManager
import android.os.Build
import dev.lackluster.mihelper.data.preference.ParityPreferences
import dev.lackluster.mihelper.hook.base.HookResult
import dev.lackluster.mihelper.hook.base.HookScope
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Full FLAG_SECURE bypass for screenshots, recording, mirroring and virtual displays. */
object AllowScreenshot : StaticHooker() {
    override fun onInit() {
        updateSelfState(ParityPreferences.ALLOW_SCREENSHOT.get())
    }

    override fun onHook() {
        deoptimizeCaptureCallers()
        hookCaptureDetection()
        hookHyperOsCaptureGate()
        hookScreenCaptureArguments()
        hookDisplayCreation()
        hookWindowSecureState()
    }

    private fun deoptimizeCaptureCallers() {
        deoptimizeMethods("com.android.server.wm.WindowStateAnimator", "createSurfaceLocked")
        deoptimizeMethods("com.android.server.wm.WindowManagerService", "relayoutWindow")
        for (index in 0 until 20) {
            deoptimizeMethods(
                "com.android.server.wm.RootWindowContainer\$\$ExternalSyntheticLambda$index",
                "accept",
            )
            deoptimizeMethods("com.android.server.wm.DisplayContent\$$index", "test")
        }
    }

    private fun deoptimizeMethods(className: String, vararg names: String) {
        val clazz = className.toClassOrNull() ?: return
        clazz.declaredMethods
            .filter { it.name in names }
            .forEach { method -> runCatching { module.deoptimize(method) } }
    }

    private fun hookCaptureDetection() {
        hookMethods("com.android.server.wm.ActivityTaskManagerService", "registerScreenCaptureObserver") {
            result(null)
        }
        hookMethods("com.android.server.wm.WindowManagerService", "registerScreenRecordingCallback") {
            if ((executable as Method).returnType == Boolean::class.javaPrimitiveType) result(false)
            else result(null)
        }
    }

    private fun hookHyperOsCaptureGate() {
        hookMethods("com.android.server.wm.WindowManagerServiceImpl", "notAllowCaptureDisplay") {
            result(false)
        }
    }

    private fun hookScreenCaptureArguments() {
        val classNames = if (Build.VERSION.SDK_INT >= 36) {
            listOf("android.window.ScreenCaptureInternal", "android.window.ScreenCapture")
        } else {
            listOf("android.window.ScreenCapture")
        }
        classNames.forEach { className ->
            val clazz = className.toClassOrNull() ?: return@forEach
            clazz.declaredMethods
                .filter { it.name == "nativeCaptureDisplay" || it.name == "nativeCaptureLayers" }
                .forEach { method ->
                    method.isAccessible = true
                    method.hook {
                        args.firstOrNull { argument ->
                            argument?.javaClass?.name?.endsWith("\$CaptureArgs") == true
                        }?.let(::enableSecureLayerCapture)
                        result(proceed())
                    }
                }
        }
    }

    private fun enableSecureLayerCapture(captureArgs: Any) {
        val field = sequenceOf("mSecureContentPolicy", "mCaptureSecureLayers")
            .mapNotNull { name ->
                runCatching {
                    captureArgs.javaClass.getDeclaredField(name).apply { isAccessible = true }
                }.getOrNull()
            }
            .firstOrNull() ?: return
        runCatching {
            when (field.type) {
                Boolean::class.javaPrimitiveType -> field.setBoolean(captureArgs, true)
                Int::class.javaPrimitiveType -> field.setInt(captureArgs, 1)
            }
        }
    }

    private fun hookDisplayCreation() {
        "com.android.server.display.DisplayControl".toClassOrNull()?.declaredMethods
            ?.filter { method ->
                method.name == "createVirtualDisplay" &&
                    method.parameterTypes.size >= 2 &&
                    method.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }
            ?.forEach { method ->
                method.isAccessible = true
                method.hook {
                    val newArgs = args.toTypedArray()
                    newArgs[1] = true
                    result(proceed(newArgs))
                }
            }

        "com.android.server.display.VirtualDisplayAdapter".toClassOrNull()?.declaredMethods
            ?.filter { it.name == "createVirtualDisplayLocked" }
            ?.forEach { method ->
                method.isAccessible = true
                method.hook {
                    val callerUid = args.getOrNull(2) as? Int ?: -1
                    if (callerUid >= 10_000 && args.getOrNull(1) == null) {
                        return@hook result(proceed())
                    }
                    val newArgs = args.toTypedArray()
                    val flagsIndex = (3 until newArgs.size).firstOrNull { newArgs[it] is Int }
                        ?: return@hook result(proceed())
                    newArgs[flagsIndex] = (newArgs[flagsIndex] as Int) or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE
                    result(proceed(newArgs))
                }
            }
    }

    private fun hookWindowSecureState() {
        "com.android.server.wm.WindowState".toClassOrNull()?.declaredMethods
            ?.filter { it.name == "isSecureLocked" && it.parameterCount == 0 }
            ?.forEach { method ->
                method.isAccessible = true
                method.hook {
                    val creatingSurface = Throwable().stackTrace
                        .asSequence()
                        .drop(4)
                        .take(12)
                        .any { frame ->
                            frame.methodName == "setInitialSurfaceControlProperties" ||
                                frame.methodName == "createSurfaceLocked"
                        }
                    if (creatingSurface) result(proceed()) else result(false)
                }
            }
    }

    private fun hookMethods(className: String, methodName: String, callback: HookScope.() -> HookResult) {
        val clazz = className.toClassOrNull() ?: return
        clazz.declaredMethods
            .filter { it.name == methodName && !Modifier.isAbstract(it.modifiers) }
            .forEach { method ->
                method.isAccessible = true
                method.hook(callback = callback)
            }
    }
}
