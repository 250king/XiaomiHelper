/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Core behavior adapted from Fxxk-MiBrowser:
 * https://github.com/DuhMatt/Fxxk-MiBrowser
 * Original project is distributed under the MIT License.
 */

package dev.lackluster.mihelper.hook.rules.shared

import android.app.PendingIntent
import android.app.role.RoleManager
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
                    interceptStart(context, intent, options) ?: result(proceed())
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
                    interceptStart(context, intent, null) ?: result(proceed())
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
                    val newArgs = args.copyOf()
                    newArgs[intentIndex] = replacement
                    result(proceed(newArgs))
                }
            }
    }

    private fun interceptStart(context: Context, intent: Intent, options: Bundle?): dev.lackluster.mihelper.hook.base.HookResult? {
        rememberWebUrl(intent)
        if (redirectGuard.get()) return null
        if (!shouldIntercept(context.packageName, intent)) return null

        val targetUrl = recoverWebUri(intent) ?: recentUrl()
        if (targetUrl == null) {
            // Do not fall through to Xiaomi Market's "install browser" page when that is the only target.
            return if (isBrowserDownloadIntent(intent)) result(null) else null
        }

        val replacement = buildReplacementIntent(context, intent, targetUrl)
        redirectGuard.set(true)
        return try {
            if (options != null) context.startActivity(replacement, options) else context.startActivity(replacement)
            result(null)
        } catch (_: Throwable) {
            null
        } finally {
            redirectGuard.remove()
        }
    }

    private fun shouldIntercept(callerPackage: String, intent: Intent): Boolean {
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
        val base = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addCategory(Intent.CATEGORY_DEFAULT)
            flags = source.flags
            putExtras(source)
            if (defaultBrowser != null) setPackage(defaultBrowser)
        }
        return if (defaultBrowser != null) base else Intent.createChooser(base, "Open with")
    }

    private fun resolveDefaultBrowser(context: Context): String? {
        runCatching {
            val roleManager = context.getSystemService(RoleManager::class.java)
            roleManager?.getRoleHolders(RoleManager.ROLE_BROWSER)
                ?.firstOrNull { !isXiaomiBrowser(it) }
        }.getOrNull()?.let { return it }

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
        val uri = extractWebUri(intent) ?: return
        if (uri.scheme != "http" && uri.scheme != "https") return
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
        if (data?.scheme == "http" || data?.scheme == "https") return data
        return extractWebUri(intent) ?: recoverWrappedUri(intent)
    }

    private fun recoverWrappedUri(intent: Intent): Uri? {
        val data = intent.data
        val keys = arrayOf("url", "web_url", "referrer", "link", "target_url", "query", "q", "text")
        if (data != null) {
            for (key in keys) {
                val value = runCatching { data.getQueryParameter(key) }.getOrNull()
                parseWebUri(value)?.let { return it }
            }
        }
        val extras = intent.extras
        if (extras != null) {
            for (key in extras.keySet()) {
                parseWebUri(runCatching { extras.get(key) as? String }.getOrNull())?.let { return it }
            }
        }
        return null
    }

    private fun extractWebUri(intent: Intent): Uri? {
        val data = intent.data
        if (data?.scheme == "http" || data?.scheme == "https") return data

        val extras = intent.extras
        if (extras != null) {
            for (key in extras.keySet()) {
                parseWebUri(runCatching { extras.get(key) as? String }.getOrNull())?.let { return it }
            }
        }

        val clip: ClipData? = intent.clipData
        if (clip != null) {
            for (index in 0 until clip.itemCount) {
                parseWebUri(clip.getItemAt(index).uri?.toString())?.let { return it }
                parseWebUri(clip.getItemAt(index).text?.toString())?.let { return it }
            }
        }
        return null
    }

    private fun parseWebUri(raw: String?): Uri? {
        val value = raw?.trim().orEmpty()
        if (!value.startsWith("http://") && !value.startsWith("https://")) return null
        return runCatching { Uri.parse(value) }.getOrNull()
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
