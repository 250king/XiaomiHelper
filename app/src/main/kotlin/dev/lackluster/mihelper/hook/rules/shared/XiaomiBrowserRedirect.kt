/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Core behavior adapted from Fxxk-MiBrowser:
 * https://github.com/DuhMatt/Fxxk-MiBrowser
 * Original project is distributed under the MIT License.
 */

package dev.lackluster.mihelper.hook.rules.shared

import android.app.Activity
import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import dev.lackluster.mihelper.data.Scope
import dev.lackluster.mihelper.data.preference.ParityPreferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Prevent Xiaomi components from forcing web links into Xiaomi Browser or its Market download page.
 *
 * The target browser is never hard-coded: if Android has a non-Xiaomi default browser, the cleaned
 * URL is sent there; otherwise the normal Android chooser is shown.
 */
object XiaomiBrowserRedirect : StaticHooker() {
    private val xiaomiBrowserPackages = setOf(
        "com.android.browser",
        "com.miui.browser",
        "com.mi.globalbrowser",
        "com.xiaomi.browser",
    )
    private val xiaomiMarketPackages = setOf(
        "com.xiaomi.market",
        "com.xiaomi.mi.global.appstore",
        "com.mi.india.appstore",
    )
    private val implicitWebCallers = setOf(
        Scope.MI_SHARE,
        Scope.AI_ENGINE,
        Scope.MI_AI,
        Scope.CONTENT_CATCHER,
        Scope.AI_ASSIST_VISION,
        Scope.MI_MIRROR,
    )
    private val fakeBrowserCallers = setOf(
        Scope.MI_SHARE,
        Scope.AI_ENGINE,
        Scope.MI_AI,
        Scope.CONTENT_CATCHER,
        Scope.AI_ASSIST_VISION,
    )
    private val earlyUrlSourcePackages = setOf(
        Scope.MI_SHARE,
        Scope.AI_ENGINE,
        Scope.MI_AI,
        Scope.AI_ASSIST_VISION,
    )

    private val urlSourceClassCandidates = mapOf(
        Scope.MI_SHARE to listOf(
            "com.miui.mishare.connectivity.refactor.lyra.LyraShareListenerService",
        ),
        Scope.AI_ENGINE to listOf(
            "com.xiaomi.aicr.copydirect.util.SmartPasswordUtils",
            "com.xiaomi.aicr.copydirect.CopyDirectActivity",
            "com.xiaomi.aicr.copydirect.CopyDirectService",
            "com.xiaomi.aicr.screen.ScreenRecognitionActivity",
            "com.xiaomi.aicr.screen.ScreenRecognitionService",
            "com.xiaomi.aicr.smartaction.SmartActionActivity",
            "com.xiaomi.aicr.smartaction.SmartActionService",
            "i26",
        ),
        Scope.MI_AI to listOf(
            "com.miui.voiceassist.ui.ScreenRecognitionActivity",
            "com.miui.voiceassist.service.ScreenRecognitionService",
            "com.xiaomi.voiceassistant.screenrecognition.ScreenRecognitionActivity",
            "com.xiaomi.voiceassistant.screenrecognition.ScreenRecognitionPresenter",
            "com.xiaomi.voiceassistant.screenrecognition.ScreenRecognitionService",
            "com.xiaomi.voiceassistant.utils.b2",
            "com.xiaomi.voiceassistant.utils.f2",
            "com.xiaomi.voiceassistant.utils.s2",
            "com.xiaomi.voiceassistant.utils.t2",
            "com.xiaomi.voiceassistant.smartaction.SmartActionHandler",
            "com.xiaomi.voiceassistant.screen.ScreenRecognitionManager",
        ),
        Scope.AI_ASSIST_VISION to listOf(
            "com.xiaomi.aiasst.vision.ScreenRecognitionActivity",
            "com.xiaomi.aiasst.vision.ScreenRecognitionService",
            "com.xiaomi.aiasst.vision.SmartActionHandler",
            "com.xiaomi.aiasst.vision.VisionRecognitionManager",
        ),
    )

    private val redirectGuard = ThreadLocal.withInitial { false }

    @Volatile
    private var recentSourceUrl: Uri? = null

    @Volatile
    private var recentSourceUrlAt: Long = 0L

    private const val SOURCE_CACHE_MS = 2 * 60 * 1000L

    override fun onInit() {
        updateSelfState(ParityPreferences.REDIRECT_XIAOMI_BROWSER.get())
    }

    override fun onHook() {
        hookContextImpl()
        hookInstrumentation()
        hookPendingIntentCreation()
        if (hookParam.packageName in fakeBrowserCallers) {
            hookPackageManagerChecks()
        }
        if (hookParam.packageName in earlyUrlSourcePackages) {
            hookEarlyUrlSources(hookParam.packageName)
        }
        if (hookParam.packageName == Scope.AI_ENGINE) hookAiEngineInstallCheck()
        if (hookParam.packageName == Scope.MI_AI) hookVoiceAssistAvailability()
    }

    private fun hookContextImpl() {
        val clazz = "android.app.ContextImpl".toClassOrNull() ?: return
        clazz.declaredMethods
            .filter { method ->
                method.name == "startActivity" &&
                    method.parameterTypes.isNotEmpty() &&
                    method.parameterTypes[0] == Intent::class.java
            }
            .forEach { method ->
                method.isAccessible = true
                method.hook {
                    val context = thisObject as? Context ?: return@hook result(proceed())
                    val intent = getArg(0) as? Intent ?: return@hook result(proceed())
                    val options = args.firstOrNull { it is Bundle } as? Bundle
                    if (interceptStart(context, intent, options)) result(null) else result(proceed())
                }
            }
    }

    private fun hookInstrumentation() {
        val clazz = "android.app.Instrumentation".toClassOrNull() ?: return
        clazz.declaredMethods
            .filter { method ->
                method.name == "execStartActivity" &&
                    method.parameterTypes.any { it == Intent::class.java }
            }
            .forEach { method ->
                method.isAccessible = true
                val intentIndex = method.parameterTypes.indexOfFirst { it == Intent::class.java }
                method.hook {
                    val context = args.firstOrNull { it is Context } as? Context
                        ?: return@hook result(proceed())
                    val intent = getArg(intentIndex) as? Intent ?: return@hook result(proceed())
                    if (interceptStart(context, intent, null)) result(null) else result(proceed())
                }
            }
    }

    private fun hookPendingIntentCreation() {
        PendingIntent::class.java.declaredMethods
            .filter { method ->
                method.name == "getActivity" &&
                    method.parameterTypes.size >= 4 &&
                    method.parameterTypes[0] == Context::class.java &&
                    method.parameterTypes.any { it == Intent::class.java }
            }
            .forEach { method ->
                method.isAccessible = true
                val intentIndex = method.parameterTypes.indexOfFirst { it == Intent::class.java }
                method.hook {
                    val context = getArg(0) as? Context ?: return@hook result(proceed())
                    val intent = getArg(intentIndex) as? Intent ?: return@hook result(proceed())
                    rememberWebUrl(intent)

                    if (!isBrowserDownloadIntent(intent)) return@hook result(proceed())
                    val recovered = recoverWebUri(intent) ?: recentUrl() ?: return@hook result(proceed())
                    val replacement = buildReplacementIntent(context, intent, recovered)
                    val newArgs = args.toTypedArray()
                    newArgs[intentIndex] = replacement
                    result(proceed(newArgs))
                }
            }
    }

    private fun hookEarlyUrlSources(packageName: String) {
        var hooked = 0
        val maxHooks = 80
        for (className in urlSourceClassCandidates[packageName].orEmpty()) {
            if (hooked >= maxHooks) break
            val clazz = className.toClassOrNull() ?: continue
            for (method in clazz.declaredMethods) {
                if (hooked >= maxHooks) break
                val interesting = method.parameterTypes.any { type ->
                    type == Intent::class.java ||
                        type == String::class.java ||
                        type == Uri::class.java ||
                        type == Bundle::class.java ||
                        CharSequence::class.java.isAssignableFrom(type)
                }
                if (!interesting) continue
                method.isAccessible = true
                method.hook {
                    args.forEach { rememberWebUrlFromValue(it) }
                    result(proceed())
                }
                hooked++
            }
        }
    }

    /** Returns true when the original launch should be consumed. */
    private fun interceptStart(context: Context, intent: Intent, options: Bundle?): Boolean {
        rememberWebUrl(intent)
        if (redirectGuard.get() == true) return false
        if (!shouldIntercept(context.packageName, intent)) return false

        val targetUrl = recoverWebUri(intent) ?: recentUrl()
        if (targetUrl == null) {
            // Never swallow a click merely because the original URL was lost. Keeping the
            // Market fallback is preferable to turning the UI action into a no-op.
            return false
        }

        val replacement = buildReplacementIntent(context, intent, targetUrl)
        redirectGuard.set(true)
        return try {
            if (options != null) context.startActivity(replacement, options) else context.startActivity(replacement)
            true
        } catch (_: Throwable) {
            false
        } finally {
            redirectGuard.remove()
        }
    }

    private fun shouldIntercept(callerPackage: String, intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_VIEW && intent.action != Intent.ACTION_MAIN) return false
        val data = intent.data ?: extractWebUri(intent) ?: return false
        val scheme = data.scheme?.lowercase() ?: return false
        val targetPackage = intent.`package`
        val targetComponentPackage = intent.component?.packageName
        val targetsBrowser = isXiaomiBrowser(targetPackage) || isXiaomiBrowser(targetComponentPackage)
        val targetsMarket = isXiaomiMarket(targetPackage) || isXiaomiMarket(targetComponentPackage)

        if ((scheme == "http" || scheme == "https") && (targetsBrowser || targetsMarket)) return true
        if (scheme == "market" && isBrowserDownloadIntent(intent)) return true
        if (scheme == "intent" && targetsBrowser) return true
        if (scheme.startsWith("mi") && (isBrowserDownloadIntent(intent) || recoverWrappedUri(intent) != null)) return true

        // Xiaomi Market has internal http/https deep links. Do not rewrite those unless the
        // intent explicitly targets Xiaomi Browser/Market (handled above).
        if (isXiaomiMarket(callerPackage)) return false

        if ((scheme == "http" || scheme == "https") &&
            targetPackage == null && intent.component == null &&
            callerPackage in implicitWebCallers
        ) return true

        if ((scheme == "http" || scheme == "https") &&
            callerPackage == Scope.SETTINGS && isRouterAdminUri(data)
        ) return true

        return false
    }

    private fun buildReplacementIntent(context: Context, source: Intent, uri: Uri): Intent {
        val defaultBrowser = resolveDefaultBrowser(context)
        val webIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addCategory(Intent.CATEGORY_DEFAULT)
            putExtras(source)
            if (defaultBrowser != null) setPackage(defaultBrowser)
        }
        return (if (defaultBrowser != null) webIntent else Intent.createChooser(webIntent, "Open with")).apply {
            flags = source.flags
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun resolveDefaultBrowser(context: Context): String? {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        return runCatching {
            context.packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
                ?.takeIf { it != "android" && !isXiaomiBrowser(it) }
        }.getOrNull()
    }

    private fun rememberWebUrl(intent: Intent) {
        extractWebUri(intent)?.let { rememberWebUri(it) }
    }

    private fun rememberWebUrlFromValue(value: Any?) {
        extractWebUriFromValue(value)?.let(::rememberWebUri)
    }

    private fun rememberWebUri(uri: Uri) {
        if (!isLikelyUserWebUri(uri)) return
        recentSourceUrl = uri
        recentSourceUrlAt = System.currentTimeMillis()
    }

    private fun recentUrl(): Uri? {
        val uri = recentSourceUrl ?: return null
        if (System.currentTimeMillis() - recentSourceUrlAt > SOURCE_CACHE_MS) {
            recentSourceUrl = null
            return null
        }
        return uri
    }

    private fun recoverWebUri(intent: Intent): Uri? {
        val data = intent.data
        if ((data?.scheme == "http" || data?.scheme == "https") && isLikelyUserWebUri(data)) return data
        return extractWebUri(intent) ?: recoverWrappedUri(intent)
    }

    private fun recoverWrappedUri(intent: Intent): Uri? {
        val data = intent.data
        val keys = arrayOf("url", "web_url", "referrer", "link", "target_url", "query", "q", "text")
        if (data != null) {
            for (key in keys) {
                val value = runCatching { data.getQueryParameter(key) }.getOrNull()
                parseWebUri(value)?.takeIf { isLikelyUserWebUri(it) }?.let { return it }
            }
        }
        val extras = intent.extras
        if (extras != null) {
            for (key in extras.keySet()) {
                extractWebUriFromValue(bundleValue(extras, key))?.let { return it }
            }
        }
        return null
    }

    private fun extractWebUri(intent: Intent): Uri? {
        val data = intent.data
        if ((data?.scheme == "http" || data?.scheme == "https") && isLikelyUserWebUri(data)) return data

        val extras = intent.extras
        if (extras != null) {
            for (key in extras.keySet()) {
                extractWebUriFromValue(bundleValue(extras, key))?.let { return it }
            }
        }

        val clip: ClipData? = intent.clipData
        if (clip != null) {
            for (index in 0 until clip.itemCount) {
                parseWebUri(clip.getItemAt(index).uri?.toString())
                    ?.takeIf { isLikelyUserWebUri(it) }
                    ?.let { return it }
                parseWebUri(clip.getItemAt(index).text?.toString())
                    ?.takeIf { isLikelyUserWebUri(it) }
                    ?.let { return it }
            }
        }
        return null
    }

    private fun parseWebUri(raw: String?): Uri? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val decoded = runCatching { Uri.decode(value) }.getOrDefault(value)
        val candidate = when {
            decoded.startsWith("http://", true) || decoded.startsWith("https://", true) -> decoded
            else -> Regex("""(?i)https?://[^\s\"'<>]+""")
                .find(decoded)
                ?.value
                ?.trimEnd(')', ']', '}', ',', '.', ';')
        } ?: return null
        return runCatching { Uri.parse(candidate) }.getOrNull()
    }

    private fun extractWebUriFromValue(
        value: Any?,
        depth: Int = 0,
        visited: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap()),
    ): Uri? {
        if (value == null || depth > 4) return null
        val direct = when (value) {
            is Uri -> value.takeIf(::isLikelyUserWebUri)
            is Intent -> {
                if (!visited.add(value)) return null
                value.data?.takeIf(::isLikelyUserWebUri)
                    ?: value.extras?.keySet()?.asSequence()
                        ?.mapNotNull {
                            extractWebUriFromValue(bundleValue(value.extras!!, it), depth + 1, visited)
                        }
                        ?.firstOrNull()
                    ?: value.clipData?.let { clip ->
                        (0 until clip.itemCount).asSequence()
                            .mapNotNull { index ->
                                val item = clip.getItemAt(index)
                                item.uri?.takeIf(::isLikelyUserWebUri)
                                    ?: parseWebUri(item.text?.toString())?.takeIf(::isLikelyUserWebUri)
                            }
                            .firstOrNull()
                    }
            }
            is CharSequence -> parseWebUri(value.toString())?.takeIf(::isLikelyUserWebUri)
            is Bundle -> value.keySet().asSequence()
                .mapNotNull { extractWebUriFromValue(bundleValue(value, it), depth + 1, visited) }
                .firstOrNull()
            is Collection<*> -> value.asSequence().take(24)
                .mapNotNull { extractWebUriFromValue(it, depth + 1, visited) }
                .firstOrNull()
            is Array<*> -> value.asSequence().take(24)
                .mapNotNull { extractWebUriFromValue(it, depth + 1, visited) }
                .firstOrNull()
            else -> null
        }
        if (direct != null || value is Uri || value is Intent || value is CharSequence ||
            value is Bundle || value is Collection<*> || value is Array<*>) {
            return direct
        }
        if (!visited.add(value)) return null
        val className = value.javaClass.name
        if (className.startsWith("java.") || className.startsWith("kotlin.") ||
            className.startsWith("android.") || className.startsWith("androidx.") ||
            className.startsWith("dalvik.")) {
            return null
        }
        var current: Class<*>? = value.javaClass
        while (current != null && current != Any::class.java) {
            current.declaredFields
                .asSequence()
                .filterNot { Modifier.isStatic(it.modifiers) }
                .forEach { field ->
                    val nested = runCatching {
                        field.isAccessible = true
                        field.get(value)
                    }.getOrNull()
                    extractWebUriFromValue(nested, depth + 1, visited)?.let { return it }
                }
            current = current.superclass
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun bundleValue(bundle: Bundle, key: String): Any? = runCatching { bundle.get(key) }.getOrNull()

    private fun isLikelyUserWebUri(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val path = uri.path?.lowercase().orEmpty()
        if (listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".ico")
                .any { path.endsWith(it) }) return false
        val host = uri.host?.lowercase().orEmpty()
        if (host.endsWith("mi-fds.com")) return false
        if (host.endsWith("xiaomi.com") &&
            listOf("icon", "resource", "cdn", "asset").any { host.contains(it) }
        ) return false
        return true
    }

    private fun isBrowserDownloadIntent(intent: Intent): Boolean {
        val data = intent.data ?: return false
        val scheme = data.scheme?.lowercase().orEmpty()
        if (scheme != "market" && !scheme.startsWith("mi")) return false
        val id = runCatching { data.getQueryParameter("id") }.getOrNull()
        return isXiaomiBrowser(id) || data.toString().contains("com.android.browser")
    }

    private fun isRouterAdminUri(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        if (host == "router.miwifi.com" || host == "miwifi.com") return true
        if (host.startsWith("192.168.")) return true
        if (host.startsWith("10.")) return true
        val parts = host.split('.')
        if (parts.size == 4 && parts[0] == "172") {
            val second = parts[1].toIntOrNull() ?: return false
            if (second in 16..31) return true
        }
        return false
    }

    private fun hookPackageManagerChecks() {
        val clazz = "android.app.ApplicationPackageManager".toClassOrNull() ?: return
        clazz.declaredMethods.forEach { method ->
            if (method.parameterTypes.firstOrNull() != String::class.java) return@forEach
            when (method.name) {
                "getPackageInfo" -> {
                    method.isAccessible = true
                    method.hook {
                        val pkg = getArg(0) as? String
                        if (isXiaomiBrowser(pkg) && hookParam.packageName != pkg) {
                            result(fakePackageInfo(pkg!!))
                        } else result(proceed())
                    }
                }
                "getApplicationInfo" -> {
                    method.isAccessible = true
                    method.hook {
                        val pkg = getArg(0) as? String
                        if (isXiaomiBrowser(pkg) && hookParam.packageName != pkg) {
                            result(fakeApplicationInfo(pkg!!))
                        } else result(proceed())
                    }
                }
                "getLaunchIntentForPackage" -> {
                    method.isAccessible = true
                    method.hook {
                        val pkg = getArg(0) as? String
                        if (isXiaomiBrowser(pkg) && hookParam.packageName != pkg) {
                            result(Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_LAUNCHER)
                                setPackage(pkg)
                            })
                        } else result(proceed())
                    }
                }
            }
        }
    }

    private fun hookAiEngineInstallCheck() {
        listOf("com.xiaomi.aicr.copydirect.util.SmartPasswordUtils", "i26")
            .mapNotNull { it.toClassOrNull() }
            .forEach { clazz ->
                clazz.declaredMethods
                    .filter { method ->
                        method.returnType == Boolean::class.javaPrimitiveType &&
                            method.parameterTypes.contentEquals(
                                arrayOf(Context::class.java, String::class.java),
                            )
                    }
                    .forEach { method ->
                        method.isAccessible = true
                        method.hook {
                            val packageName = getArg(1) as? String
                            if (isXiaomiBrowser(packageName)) result(true) else result(proceed())
                        }
                    }
            }
    }

    private fun hookVoiceAssistAvailability() {
        listOf(
            "com.xiaomi.voiceassistant.utils.b2",
            "com.xiaomi.voiceassistant.utils.f2",
            "com.xiaomi.voiceassistant.utils.s2",
            "com.xiaomi.voiceassistant.utils.t2",
        ).mapNotNull { it.toClassOrNull() }
            .forEach { clazz ->
                clazz.declaredMethods
                    .filter { method ->
                        method.name == "isIntentAvailable" &&
                            method.returnType == Boolean::class.javaPrimitiveType &&
                            method.parameterTypes.contentEquals(
                                arrayOf(Intent::class.java, Context::class.java),
                            )
                    }
                    .forEach { method ->
                        method.isAccessible = true
                        method.hook {
                            val intent = getArg(0) as? Intent ?: return@hook result(proceed())
                            val context = getArg(1) as? Context ?: return@hook result(proceed())
                            if (rewriteVoiceAssistBrowserIntent(context, intent)) result(true)
                            else result(proceed())
                        }
                    }
            }
    }

    private fun rewriteVoiceAssistBrowserIntent(context: Context, intent: Intent): Boolean {
        val targetsBrowser = isXiaomiBrowser(intent.`package`) ||
            isXiaomiBrowser(intent.component?.packageName)
        if (!targetsBrowser) return false
        val url = recoverWebUri(intent) ?: recentUrl() ?: return false
        intent.action = Intent.ACTION_VIEW
        intent.data = url
        intent.component = null
        intent.`package` = resolveDefaultBrowser(context)
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.addCategory(Intent.CATEGORY_DEFAULT)
        return true
    }

    private fun fakePackageInfo(pkg: String): PackageInfo = PackageInfo().apply {
        packageName = pkg
        versionName = "1.0"
        @Suppress("DEPRECATION")
        versionCode = 1
        applicationInfo = fakeApplicationInfo(pkg)
    }

    private fun fakeApplicationInfo(pkg: String): ApplicationInfo = ApplicationInfo().apply {
        packageName = pkg
        enabled = true
        flags = ApplicationInfo.FLAG_SYSTEM
        val stubPath = "/system/app/$pkg/$pkg.apk"
        sourceDir = stubPath
        publicSourceDir = stubPath
    }

    private fun isXiaomiBrowser(pkg: String?): Boolean = pkg in xiaomiBrowserPackages
    private fun isXiaomiMarket(pkg: String?): Boolean = pkg in xiaomiMarketPackages
}
