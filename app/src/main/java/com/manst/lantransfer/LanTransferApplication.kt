package com.manst.lantransfer

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

class LanTransferApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val resumedActivities = AtomicInteger(0)

    /**
     * 是否有已 Resumed 的 Activity。
     *
     * 传输前台服务只在界面可见时启动：无界面场景（仪器测试、后台进程恢复等）
     * 直接 startForegroundService 可能被系统限制并触发 ForegroundServiceDidNotStartInTimeException。
     * 服务一旦在界面前台时启动，后续离开界面仍会继续运行，不影响真实后台传输。
     */
    val hasForegroundActivity: Boolean
        get() = resumedActivities.get() > 0

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivities.incrementAndGet()
            }

            override fun onActivityPaused(activity: Activity) {
                resumedActivities.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
