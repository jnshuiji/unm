package pochita.common

import io.github.libxposed.api.XposedModule
import java.io.PrintStream

/**
 * Unified logging facade for the NetEase UNM module.
 *
 * Provides a pluggable logging architecture that adapts dynamically to:
 * - Xposed host environment via [XposedAdapter] (forwarding to [XposedModule.log]).
 * - JVM unit test environment via [ConsoleAdapter] (forwarding to stdout/stderr).
 *
 * Guarantee: Under NO circumstances will any logging call throw an exception.
 * All adapter exceptions are silently swallowed to protect host application stability.
 */
object UnmLogger {

    const val LEVEL_VERBOSE: Int = 2
    const val LEVEL_DEBUG: Int = 3
    const val LEVEL_INFO: Int = 4
    const val LEVEL_WARN: Int = 5
    const val LEVEL_ERROR: Int = 6

    /**
     * Adapter interface for receiving formatted log events.
     */
    interface Adapter {
        fun log(level: Int, tag: String, message: String, throwable: Throwable? = null)
    }

    /**
     * Default console adapter for JVM unit tests and standalone environments.
     *
     * Routes DEBUG and INFO logs to [outStream] (stdout), and WARN and ERROR
     * logs to [errStream] (stderr). Prints stack traces when [throwable] is non-null.
     */
    class ConsoleAdapter(
        private val outStream: PrintStream = System.out,
        private val errStream: PrintStream = System.err,
        var minLevel: Int = LEVEL_DEBUG
    ) : Adapter {
        override fun log(level: Int, tag: String, message: String, throwable: Throwable?) {
            try {
                if (level < minLevel) return
                val levelChar = when (level) {
                    LEVEL_VERBOSE -> "V"
                    LEVEL_DEBUG -> "D"
                    LEVEL_INFO -> "I"
                    LEVEL_WARN -> "W"
                    LEVEL_ERROR -> "E"
                    else -> "L$level"
                }
                val target = if (level >= LEVEL_WARN) errStream else outStream
                val threadName = Thread.currentThread().name
                target.println("[$levelChar][$tag][$threadName] $message")
                throwable?.printStackTrace(target)
                target.flush()
            } catch (_: Throwable) {
                // Silently swallow any console I/O or format exceptions
            }
        }
    }

    /**
     * Xposed adapter delegating logs to the LSPosed framework runtime.
     */
    class XposedAdapter(private val module: XposedModule) : Adapter {
        override fun log(level: Int, tag: String, message: String, throwable: Throwable?) {
            try {
                if (throwable != null) {
                    module.log(level, tag, message, throwable)
                } else {
                    module.log(level, tag, message)
                }
            } catch (_: Throwable) {
                // Silently swallow any framework IPC or binder exceptions
            }
        }
    }

    @Volatile
    private var currentAdapter: Adapter = ConsoleAdapter()

    /**
     * Replaces the active logging adapter.
     */
    @JvmStatic
    fun setAdapter(adapter: Adapter) {
        currentAdapter = adapter
    }

    /**
     * Convenience overload allowing a lambda to be passed as an adapter.
     */
    @JvmStatic
    fun setAdapter(adapter: (level: Int, tag: String, message: String, throwable: Throwable?) -> Unit) {
        setAdapter(object : Adapter {
            override fun log(level: Int, tag: String, message: String, throwable: Throwable?) {
                adapter(level, tag, message, throwable)
            }
        })
    }

    /**
     * Returns the currently active adapter.
     */
    @JvmStatic
    fun getAdapter(): Adapter = currentAdapter

    /**
     * Resets adapter to the default [ConsoleAdapter].
     */
    @JvmStatic
    fun resetAdapter() {
        currentAdapter = ConsoleAdapter()
    }

    @JvmStatic
    fun v(tag: String, msg: String) {
        dispatchLog(LEVEL_VERBOSE, tag, msg, null)
    }

    @JvmStatic
    @JvmOverloads
    fun d(tag: String, msg: String, tr: Throwable? = null) {
        dispatchLog(LEVEL_DEBUG, tag, msg, tr)
    }

    @JvmStatic
    @JvmOverloads
    fun i(tag: String, msg: String, tr: Throwable? = null) {
        dispatchLog(LEVEL_INFO, tag, msg, tr)
    }

    @JvmStatic
    @JvmOverloads
    fun w(tag: String, msg: String, tr: Throwable? = null) {
        dispatchLog(LEVEL_WARN, tag, msg, tr)
    }

    @JvmStatic
    @JvmOverloads
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        dispatchLog(LEVEL_ERROR, tag, msg, tr)
    }

    private fun dispatchLog(level: Int, tag: String, message: String, throwable: Throwable?) {
        try {
            currentAdapter.log(level, tag, message, throwable)
        } catch (_: Throwable) {
            // Silently suppress under all conditions
        }
    }
}
