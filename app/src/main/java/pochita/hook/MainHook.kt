package pochita.hook

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

import java.util.concurrent.atomic.AtomicBoolean

/**
 * LSPosed (API 102) 模块核心入口类。
 * 必须在 META-INF/xposed/java_init.list 中声明此完整类名。
 */
class MainHook : XposedModule() {

    companion object {
        private const val TAG = "NeteaseUNMHook"
        private const val TARGET_PACKAGE = "com.netease.cloudmusic"
        private val initialized = AtomicBoolean(false)
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        log(Log.INFO, TAG, "UNM Module loaded in process: ${param.processName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        if (param.packageName != TARGET_PACKAGE) return

        log(Log.INFO, TAG, "NetEase Cloud Music ready: ${param.packageName}")


        // Hook Instrumentation.callApplicationOnCreate 获取解壳后的真实 Application 与 ClassLoader
        try {
            val instrumentationClass = param.classLoader.loadClass("android.app.Instrumentation")
            val callAppOnCreateMethod = instrumentationClass.getDeclaredMethod(
                "callApplicationOnCreate",
                Application::class.java
            )

            hook(callAppOnCreateMethod)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val app = chain.args.getOrNull(0) as? Application
                    if (app != null && app.packageName == TARGET_PACKAGE) {
                        if (initialized.compareAndSet(false, true)) {
                            log(Log.INFO, TAG, "Application onCreate: ${app.javaClass.name}, initializing NetEaseHook...")
                            try {
                                NetEaseHook.init(this@MainHook, app.classLoader, app)
                                log(Log.INFO, TAG, "NetEaseHook initialized successfully!")
                            } catch (t: Throwable) {
                                log(Log.ERROR, TAG, "NetEaseHook.init failed", t)
                            }
                        }
                    }
                    chain.proceed()
                }
            log(Log.INFO, TAG, "Hooked Instrumentation.callApplicationOnCreate successfully")

            // Hook Activity lifecycle directly to ensure 100% intercept reliability on all wrappers/flavors
            try {
                val callActivityOnCreateMethod = instrumentationClass.getDeclaredMethod(
                    "callActivityOnCreate",
                    android.app.Activity::class.java,
                    android.os.Bundle::class.java
                )
                hook(callActivityOnCreateMethod)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        chain.proceed()
                        val activity = chain.args.getOrNull(0) as? android.app.Activity
                        if (activity != null && activity.packageName == TARGET_PACKAGE) {
                            PlayerUiHook.onActivityCreated(activity)
                        }
                    }

                val callActivityOnResumeMethod = instrumentationClass.getDeclaredMethod(
                    "callActivityOnResume",
                    android.app.Activity::class.java
                )
                hook(callActivityOnResumeMethod)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        chain.proceed()
                        val activity = chain.args.getOrNull(0) as? android.app.Activity
                        if (activity != null && activity.packageName == TARGET_PACKAGE) {
                            PlayerUiHook.onActivityResumed(activity)
                        }
                    }

                val callActivityOnDestroyMethod = instrumentationClass.getDeclaredMethod(
                    "callActivityOnDestroy",
                    android.app.Activity::class.java
                )
                hook(callActivityOnDestroyMethod)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val activity = chain.args.getOrNull(0) as? android.app.Activity
                        if (activity != null && activity.packageName == TARGET_PACKAGE) {
                            PlayerUiHook.onActivityDestroyed(activity)
                        }
                        chain.proceed()
                    }

                log(Log.INFO, TAG, "Hooked Instrumentation activity lifecycle methods successfully")
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "Failed to hook callActivity methods: ${t.message}", t)
            }
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook callApplicationOnCreate", t)
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        log(Log.INFO, TAG, "Module is hot reloading...")
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        super.onHotReloaded(param)
        log(Log.INFO, TAG, "Module hot reloaded successfully!")
    }
}
