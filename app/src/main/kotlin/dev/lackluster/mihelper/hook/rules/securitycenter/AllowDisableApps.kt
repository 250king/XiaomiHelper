package dev.lackluster.mihelper.hook.rules.securitycenter

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import dev.lackluster.mihelper.data.Scope
import dev.lackluster.mihelper.data.preference.ParityPreferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Adds a real enable/disable action to Security Center's application details page. */
object AllowDisableApps : StaticHooker() {
    private const val CUSTOM_DISABLE_ITEM_ID = 666
    private val protectedPackages = setOf(
        Scope.LBE,
        Scope.PACKAGE_INSTALLER,
    )

    override fun onInit() {
        updateSelfState(ParityPreferences.ALLOW_DISABLE_APPS.get())
    }

    override fun onHook() {
        val activityClass = "com.miui.appmanager.ApplicationsDetailsActivity".toClassOrNull() ?: return
        val fragmentClass = "com.miui.appmanager.fragment.ApplicationsDetailsFragment".toClassOrNull()

        hookMenuCreation(activityClass)
        hookMenuPreparation(activityClass)
        hookMenuSelection(activityClass, fragmentClass)
    }

    private fun hookMenuCreation(activityClass: Class<*>) {
        activityClass.declaredMethods
            .filter { method ->
                method.name == "onCreateOptionsMenu" &&
                    method.parameterTypes.any { Menu::class.java.isAssignableFrom(it) }
            }
            .forEach { method ->
                method.isAccessible = true
                method.hook {
                    val original = proceed()
                    val activity = thisObject as? Activity
                    val menu = args.firstOrNull { it is Menu } as? Menu
                    if (activity != null && menu != null) configureMenu(activity, thisObject, menu)
                    result(original)
                }
            }
    }

    private fun hookMenuPreparation(activityClass: Class<*>) {
        activityClass.declaredMethods
            .filter { method ->
                method.name == "onPrepareOptionsMenu" &&
                    method.parameterTypes.any { Menu::class.java.isAssignableFrom(it) }
            }
            .forEach { method ->
                method.isAccessible = true
                method.hook {
                    val original = proceed()
                    val activity = thisObject as? Activity
                    val menu = args.firstOrNull { it is Menu } as? Menu
                    if (activity != null && menu != null) configureMenu(activity, thisObject, menu)
                    result(original)
                }
            }
    }

    private fun hookMenuSelection(activityClass: Class<*>, fragmentClass: Class<*>?) {
        val candidates = buildList {
            addAll(activityClass.methods.asList())
            addAll(activityClass.declaredMethods.asList())
            if (fragmentClass != null) addAll(fragmentClass.declaredMethods.asList())
        }.filter { method -> method.parameterTypes.any { MenuItem::class.java.isAssignableFrom(it) } }
            .distinctBy(Method::toGenericString)

        candidates.forEach { method ->
            method.isAccessible = true
            method.hook {
                val item = args.firstOrNull { it is MenuItem } as? MenuItem
                if (item?.itemId != CUSTOM_DISABLE_ITEM_ID) {
                    return@hook result(proceed())
                }
                val activity = when (val owner = thisObject) {
                    is Activity -> owner
                    null -> null
                    else -> invokeFirst(owner, "getActivity") as? Activity
                } ?: return@hook result(proceed())
                val handled = handleToggle(activity, thisObject, item)
                if (!handled) return@hook result(proceed())

                val returnType = (executable as Method).returnType
                if (returnType == Boolean::class.javaPrimitiveType ||
                    returnType == Boolean::class.javaObjectType
                ) result(true) else result(null)
            }
        }
    }

    private fun configureMenu(activity: Activity, owner: Any?, menu: Menu) {
        menu.findItem(6)?.isVisible = false
        val packageInfo = resolvePackageInfo(activity, owner) ?: return
        val appInfo = runCatching {
            activity.packageManager.getApplicationInfo(packageInfo.packageName, 0)
        }.getOrNull() ?: return

        val item = menu.findItem(CUSTOM_DISABLE_ITEM_ID) ?: menu.add(
            0,
            CUSTOM_DISABLE_ITEM_ID,
            1,
            stateTitle(activity, appInfo.enabled),
        ).apply {
            val iconId = activity.resources.getIdentifier(
                "action_button_stop_svg",
                "drawable",
                Scope.SECURITY_CENTER,
            )
            if (iconId != 0) setIcon(iconId)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        item.title = stateTitle(activity, isAppEnabled(activity.packageManager, packageInfo.packageName))
        item.isEnabled = packageInfo.packageName !in protectedPackages

        val isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val isUpdatedSystem = appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        if (!isAppEnabled(activity.packageManager, packageInfo.packageName) || (isSystem && !isUpdatedSystem)) {
            menu.findItem(2)?.isVisible = false
        }
    }

    private fun handleToggle(activity: Activity, owner: Any?, item: MenuItem): Boolean {
        val packageInfo = resolvePackageInfo(activity, owner) ?: return false
        val packageName = packageInfo.packageName
        if (packageName in protectedPackages) {
            Toast.makeText(activity, "This core system component cannot be disabled.", Toast.LENGTH_SHORT).show()
            return true
        }

        val pm = activity.packageManager
        val appInfo = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull() ?: return false
        val enabled = isAppEnabled(pm, packageName)
        val isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0

        if (!enabled) {
            setAppState(activity, packageName, item, true)
            return true
        }
        if (!isSystem) {
            setAppState(activity, packageName, item, false)
            return true
        }

        AlertDialog.Builder(activity)
            .setTitle(stateTitle(activity, true))
            .setMessage("Disabling a system app may affect system stability. Continue?")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                setAppState(activity, packageName, item, false)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        return true
    }

    private fun setAppState(
        activity: Activity,
        packageName: String,
        item: MenuItem,
        enable: Boolean,
    ) {
        val pm = activity.packageManager
        val state = if (enable) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val changed = runCatching {
            pm.setApplicationEnabledSetting(packageName, state, 0)
            isAppEnabled(pm, packageName) == enable
        }.getOrDefault(false)

        if (changed) {
            item.title = stateTitle(activity, enable)
            val messageName = if (enable) "app_manager_enabled" else "app_manager_disabled"
            val messageId = activity.resources.getIdentifier(messageName, "string", Scope.SECURITY_CENTER)
            Toast.makeText(
                activity,
                if (messageId != 0) activity.getString(messageId)
                else if (enable) "App enabled" else "App disabled",
                Toast.LENGTH_SHORT,
            ).show()
            activity.invalidateOptionsMenu()
        } else {
            Toast.makeText(activity, "Failed to change app state.", Toast.LENGTH_LONG).show()
        }
    }

    private fun stateTitle(activity: Activity, enabled: Boolean): CharSequence {
        val name = if (enabled) "app_manager_disable_text" else "app_manager_enable_text"
        val id = activity.resources.getIdentifier(name, "string", Scope.SECURITY_CENTER)
        return if (id != 0) activity.getText(id) else if (enabled) "Disable" else "Enable"
    }

    private fun isAppEnabled(pm: PackageManager, packageName: String): Boolean = runCatching {
        when (pm.getApplicationEnabledSetting(packageName)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
            else -> true
        }
    }.getOrDefault(true)

    private fun resolvePackageInfo(activity: Activity, owner: Any?): PackageInfo? {
        owner?.let { readFirstFieldByType(it, PackageInfo::class.java) as? PackageInfo }?.let { return it }
        (readFirstFieldByType(activity, PackageInfo::class.java) as? PackageInfo)?.let { return it }

        val fragmentClass = "com.miui.appmanager.fragment.ApplicationsDetailsFragment".toClassOrNull()
            ?: return null
        val fragment = readFirstFieldAssignable(activity, fragmentClass) ?: return null
        return readFirstFieldByType(fragment, PackageInfo::class.java) as? PackageInfo
    }

    private fun readFirstFieldByType(target: Any, type: Class<*>): Any? {
        var current: Class<*>? = target.javaClass
        while (true) {
            val currentType = current ?: break
            currentType.declaredFields.firstOrNull { it.type == type }?.let { field ->
                return readFieldValue(target, field)
            }
            current = currentType.superclass
        }
        return null
    }

    private fun readFirstFieldAssignable(target: Any, type: Class<*>): Any? {
        var current: Class<*>? = target.javaClass
        while (true) {
            val currentType = current ?: break
            currentType.declaredFields.firstOrNull { type.isAssignableFrom(it.type) }?.let { field ->
                return readFieldValue(target, field)
            }
            current = currentType.superclass
        }
        return null
    }

    private fun readFieldValue(target: Any, field: Field): Any? = runCatching {
        field.isAccessible = true
        field.get(target)
    }.getOrNull()

    private fun invokeFirst(target: Any, name: String, vararg values: Any?): Any? {
        var current: Class<*>? = target.javaClass
        while (true) {
            val currentType = current ?: break
            currentType.declaredMethods
                .asSequence()
                .filter { it.name == name && it.parameterCount == values.size }
                .forEach { method ->
                    val invocation = runCatching {
                        method.isAccessible = true
                        method.invoke(target, *values)
                    }
                    if (invocation.isSuccess) return invocation.getOrNull()
                }
            current = currentType.superclass
        }
        return null
    }
}
