package dev.lackluster.mihelper.hook.rules.systemui.wifi

import dev.lackluster.mihelper.hook.rules.systemui.compat.FlowCompat
import dev.lackluster.mihelper.hook.rules.systemui.compat.FlowCompat.collectFlow
import dev.lackluster.mihelper.hook.rules.systemui.compat.MutableStateFlowCompat
import dev.lackluster.mihelper.hook.rules.systemui.compat.ReadonlyStateFlowCompat

sealed interface WifiSignalState {
    data object Hidden : WifiSignalState
    data class Visible(val level: Int) : WifiSignalState
}

object WifiIconInteractor {
    private var isStarted = false

    val proxyWifiSignal = MutableStateFlowCompat<WifiSignalState>(WifiSignalState.Hidden)

    var coroutineScope: Any? = null
        private set

    private var sourceJob: Any? = null

    fun start(scope: Any, wifiNetwork: Any, mapper: (Any?) -> WifiSignalState) {
        if (isStarted) return
        isStarted = true
        coroutineScope = scope
        FlowCompat.cancelJob(sourceJob)
        sourceJob = ReadonlyStateFlowCompat<Any>().of(wifiNetwork).collectFlow(scope) { network ->
            proxyWifiSignal.setValue(mapper(network))
        }
    }
}
