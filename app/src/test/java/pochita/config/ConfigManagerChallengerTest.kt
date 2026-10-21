package pochita.config

import android.content.SharedPreferences
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pochita.common.UnmLruCache
import pochita.data.PreferenceKeys
import pochita.model.MatchedAudio
import pochita.model.ReplaceEvent
import pochita.model.SongInfo
import pochita.resolver.PlayerUrlResolver

/**
 * Empirical Adversarial Challenge Suite for [ConfigManager] and [RemotePreferences] synchronization.
 *
 * Stress vectors tested:
 * 1. Listener Garbage Collection Retention (WeakHashMap simulation & memory pressure).
 * 2. High-concurrency race condition testing (concurrent readers, writers, listener churn, init swaps).
 * 3. Adversarial / Corrupted input fuzzing (malformed JSON, invalid qualities, type poisoning).
 * 4. Listener fault isolation (uncaught exceptions in callbacks).
 * 5. Concurrent PlayerUrlResolver audio resolution with live preference mutation.
 */
class ConfigManagerChallengerTest {

    private lateinit var weakPrefs: WeakHashMapSharedPreferences
    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    fun setUp() {
        ConfigManager.resetForTesting()
        weakPrefs = WeakHashMapSharedPreferences()
        fakePrefs = FakeSharedPreferences()
    }

    @After
    fun tearDown() {
        ConfigManager.resetForTesting()
    }

    private fun forceGarbageCollection() {
        for (i in 0 until 5) {
            System.gc()
            System.runFinalization()
            try {
                val garbage = ArrayList<ByteArray>()
                for (j in 0 until 30) {
                    garbage.add(ByteArray(1024 * 1024)) // 30MB allocation to provoke GC
                }
            } catch (_: OutOfMemoryError) {}
            Thread.sleep(10)
        }
    }

    // =========================================================================
    // Category 1: Listener Retention under Garbage Collection (WeakHashMap)
    // =========================================================================

    @Test
    fun testWeakHashMapReclaimsAnonymousListenerUnderGCPressure() {
        // Must capture local state so Kotlin compiler doesn't optimize it into a static singleton
        val capturedState = Any()
        var anonymousListener: SharedPreferences.OnSharedPreferenceChangeListener? =
            SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                capturedState.hashCode()
            }
        val weakRef = WeakReference(anonymousListener)

        weakPrefs.registerOnSharedPreferenceChangeListener(anonymousListener)
        assertEquals("Anonymous listener registered", 1, weakPrefs.listenerCount)

        // Nullify local strong reference and induce GC
        anonymousListener = null
        forceGarbageCollection()

        // WeakHashMap should have reclaimed the key
        assertTrue("Anonymous listener must be garbage collected", weakPrefs.listenerCount == 0 || weakRef.get() == null)
    }

    @Test
    fun testConfigManagerStrongReferencePreventsReclamationUnderAggressiveGC() {
        ConfigManager.init(weakPrefs)
        assertEquals("ConfigManager listener must be registered in WeakHashMap", 1, weakPrefs.listenerCount)

        // Induce intense GC sweeps and memory pressure
        forceGarbageCollection()

        assertEquals("ConfigManager strong reference must survive GC in WeakHashMap", 1, weakPrefs.listenerCount)

        // Verify live notification still succeeds after GC sweeps
        weakPrefs.edit()
            .putString(PreferenceKeys.KEY_PREFERRED_QUALITY, "flac")
            .apply()

        assertEquals("Config snapshot must update to 'flac' after GC", "flac", ConfigManager.getConfig().preferredQuality)
    }

    @Test
    fun testConfigManagerExternalListenersSurviveGCAndReceiveUpdates() {
        ConfigManager.init(weakPrefs)
        val receivedUpdates = AtomicInteger(0)
        val lastReceivedQuality = AtomicReference<String>()

        // User listener held strongly
        val userListener: (UnmConfig) -> Unit = { cfg ->
            receivedUpdates.incrementAndGet()
            lastReceivedQuality.set(cfg.preferredQuality)
        }
        val unregister = ConfigManager.addOnConfigChangeListener(userListener)

        forceGarbageCollection()

        weakPrefs.edit()
            .putString(PreferenceKeys.KEY_PREFERRED_QUALITY, "128k")
            .apply()

        assertEquals(1, receivedUpdates.get())
        assertEquals("128k", lastReceivedQuality.get())

        unregister()

        weakPrefs.edit()
            .putString(PreferenceKeys.KEY_PREFERRED_QUALITY, "192k")
            .apply()

        assertEquals("Unregistered listener must not receive further events", 1, receivedUpdates.get())
    }

    @Test
    fun testConfigManagerUnregistersOldPrefsWhenReinitialized() {
        val firstPrefs = WeakHashMapSharedPreferences()
        val secondPrefs = WeakHashMapSharedPreferences()

        ConfigManager.init(firstPrefs)
        assertEquals(1, firstPrefs.listenerCount)
        assertEquals(0, secondPrefs.listenerCount)

        ConfigManager.init(secondPrefs)
        assertEquals("Old prefs must be unregistered", 0, firstPrefs.listenerCount)
        assertEquals("New prefs must be registered", 1, secondPrefs.listenerCount)

        ConfigManager.resetForTesting()
        assertEquals("Reset must unregister from active prefs", 0, secondPrefs.listenerCount)
    }

    // =========================================================================
    // Category 2: Concurrency Stress Testing
    // =========================================================================

    @Test
    fun testExtremeConcurrencyReadersWritersAndListenerChurn() {
        ConfigManager.init(fakePrefs)

        val readerCount = 16
        val writerCount = 4
        val totalThreads = readerCount + writerCount + 2
        val executor = Executors.newFixedThreadPool(totalThreads)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(totalThreads)

        val readErrors = AtomicInteger(0)
        val totalReads = AtomicInteger(0)
        val qualities = listOf("flac", "320k", "192k", "128k")
        val sourceLists = listOf(
            "bodian,kuwo,migu,kugou",
            "kugou,migu",
            "kuwo,customjs",
            "bodian",
            "migu,kugou,kuwo"
        )

        // 1. Reader Threads
        for (i in 0 until readerCount) {
            executor.execute {
                startLatch.await()
                try {
                    for (j in 0 until 5000) {
                        val config = ConfigManager.getConfig()
                        assertNotNull("Config must not be null", config)
                        assertTrue("Sources must not be empty", config.enabledSources.isNotEmpty())
                        assertTrue("Quality must be supported", config.preferredQuality in UnmConfig.SUPPORTED_QUALITIES)
                        assertTrue("Bitrate must be positive", config.targetBitrate() > 0)
                        assertTrue("Level must not be blank", config.targetLevel().isNotBlank())
                        assertTrue("Format must be mp3 or flac", config.targetFormat() in listOf("mp3", "flac"))
                        totalReads.incrementAndGet()
                    }
                } catch (t: Throwable) {
                    readErrors.incrementAndGet()
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        // 2. Writer Threads
        for (w in 0 until writerCount) {
            executor.execute {
                startLatch.await()
                try {
                    for (k in 0 until 300) {
                        val q = qualities[k % qualities.size]
                        val s = sourceLists[k % sourceLists.size]
                        fakePrefs.edit()
                            .putString(PreferenceKeys.KEY_PREFERRED_QUALITY, q)
                            .putString(PreferenceKeys.KEY_ENABLED_SOURCES, s)
                            .putBoolean(PreferenceKeys.KEY_ENABLE_TOAST, k % 2 == 0)
                            .putBoolean(PreferenceKeys.KEY_ENABLE_PLAYER_BADGE, k % 3 == 0)
                            .putBoolean(PreferenceKeys.KEY_ENABLE_ANTI_ROLL, k % 2 != 0)
                            .apply()
                        Thread.yield()
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        // 3. Listener Churn Threads
        for (l in 0 until 2) {
            executor.execute {
                startLatch.await()
                try {
                    for (m in 0 until 100) {
                        val unreg = ConfigManager.addOnConfigChangeListener { cfg ->
                            assertNotNull(cfg)
                        }
                        Thread.sleep(1)
                        unreg()
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        val finishedInTime = doneLatch.await(15, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue("All concurrent threads must complete within timeout", finishedInTime)
        assertEquals("Zero read errors under high concurrency", 0, readErrors.get())
        assertEquals(readerCount * 5000, totalReads.get())
    }

    @Test
    fun testConcurrentReInitWhileReadingDoesNotDeadlockOrThrow() {
        val prefsA = FakeSharedPreferences()
        val prefsB = FakeSharedPreferences()
        prefsA.edit().putString(PreferenceKeys.KEY_PREFERRED_QUALITY, "flac").apply()
        prefsB.edit().putString(PreferenceKeys.KEY_PREFERRED_QUALITY, "128k").apply()

        ConfigManager.init(prefsA)

        val executor = Executors.newFixedThreadPool(8)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(8)
        val errorCount = AtomicInteger(0)

        // 4 reader threads
        for (i in 0 until 4) {
            executor.execute {
                startLatch.await()
                try {
                    for (j in 0 until 3000) {
                        val cfg = ConfigManager.getConfig()
                        assertTrue(cfg.preferredQuality in listOf("flac", "128k", "320k"))
                    }
                } catch (t: Throwable) {
                    errorCount.incrementAndGet()
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        // 4 switcher threads
        for (i in 0 until 4) {
            executor.execute {
                startLatch.await()
                try {
                    for (j in 0 until 100) {
                        ConfigManager.init(if (j % 2 == 0) prefsA else prefsB)
                        Thread.yield()
                    }
                } catch (t: Throwable) {
                    errorCount.incrementAndGet()
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        assertTrue("Re-init concurrency test must complete within 10s", doneLatch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals(0, errorCount.get())
    }

    // =========================================================================
    // Category 3: Corrupted / Malformed Input Handling & Type Poisoning
    // =========================================================================

    @Test
    fun testCorruptedSourcesInputHandlingMatrix() {
        val testCases = listOf(
            // Broken / truncated JSON
            "[\"bodian\", \"ku" to PreferenceKeys.DEFAULT_ENABLED_SOURCES,
            "[[[{\"bad\": 123}]]]" to PreferenceKeys.DEFAULT_ENABLED_SOURCES,
            "{\"enabled\": [\"kuwo\"]}" to PreferenceKeys.DEFAULT_ENABLED_SOURCES,

            // Mixed JSON array types
            "[123, true, null, {}, []]" to PreferenceKeys.DEFAULT_ENABLED_SOURCES,
            "[\"\", \"   \"]" to PreferenceKeys.DEFAULT_ENABLED_SOURCES,

            // JSON array with unknown sources
            "[\"spotify\", \"apple_music\", \"soundcloud\"]" to PreferenceKeys.DEFAULT_ENABLED_SOURCES,

            // JSON array with partial valid sources
            "[\"spotify\", \"bodian\", \"tidal\", \"kuwo\"]" to listOf("bodian", "kuwo"),

            // JSON array with duplicate sources
            "[\"kuwo\", \"kuwo\", \"bodian\", \"kuwo\"]" to listOf("kuwo", "bodian"),

            // Comma-separated with erratic delimiters & whitespaces
            ",,,bodian,,,  kuwo  ,,  migu,," to listOf("bodian", "kuwo", "migu"),
            "   ,   ,   " to PreferenceKeys.DEFAULT_ENABLED_SOURCES,
            "" to PreferenceKeys.DEFAULT_ENABLED_SOURCES,
            "   " to PreferenceKeys.DEFAULT_ENABLED_SOURCES,

            // Normalized aliases: js, custom_js, customjs
            "js,custom_js,customjs" to listOf("customjs"),
            "[\"js\", \"custom_js\"]" to listOf("customjs"),

            // Case insensitivity
            "BODIAN,KUWO,MIGU" to listOf("bodian", "kuwo", "migu"),

            // Special control characters / emoji
            "\u0000bodian\u0000,kuwo\n,🎵" to listOf("kuwo")
        )

        for ((input, expected) in testCases) {
            fakePrefs.edit()
                .putString(PreferenceKeys.KEY_ENABLED_SOURCES, input)
                .apply()

            val loaded = ConfigManager.loadFromPreferences(fakePrefs)
            assertEquals("Failed for input: '$input'", expected, loaded.enabledSources)
        }
    }

    @Test
    fun testMassiveCorruptedStringDoesNotCrashOrOOM() {
        val massiveCorrupted = StringBuilder().apply {
            repeat(10000) { append("unknown$it,") }
            append("kuwo,")
            repeat(10000) { append(",,,") }
        }.toString()

        fakePrefs.edit().putString(PreferenceKeys.KEY_ENABLED_SOURCES, massiveCorrupted).apply()

        val loaded = ConfigManager.loadFromPreferences(fakePrefs)
        assertEquals(listOf("kuwo"), loaded.enabledSources)
    }

    @Test
    fun testInvalidQualityStringsFallbackMatrix() {
        val invalidQualities = listOf(
            "lossless", "hires", "999k", "256k", "64k", "auto", "best",
            "", "   ", "MP3", "FLAC_MASTER", "1000000", "-1", "null"
        )

        for (q in invalidQualities) {
            fakePrefs.edit().putString(PreferenceKeys.KEY_PREFERRED_QUALITY, q).apply()
            val loaded = ConfigManager.loadFromPreferences(fakePrefs)
            assertEquals("Quality '$q' must fall back to default '320k'", "320k", loaded.preferredQuality)
        }

        // Padded / mixed-case valid qualities must be accepted
        val validPadded = mapOf(
            "  flac  " to "flac",
            "FLAC" to "flac",
            " 320k " to "320k",
            "192K" to "192k",
            "\t128k\n" to "128k"
        )

        for ((raw, expected) in validPadded) {
            fakePrefs.edit().putString(PreferenceKeys.KEY_PREFERRED_QUALITY, raw).apply()
            val loaded = ConfigManager.loadFromPreferences(fakePrefs)
            assertEquals("Padded quality '$raw' should parse as '$expected'", expected, loaded.preferredQuality)
        }
    }

    @Test
    fun testTypePoisoningResistanceAcrossAllPreferenceTypes() {
        // Intentionally storing completely incompatible types in every preference key
        fakePrefs.putRaw(PreferenceKeys.KEY_ENABLED_SOURCES, 99999) // Int in String key
        fakePrefs.putRaw(PreferenceKeys.KEY_PREFERRED_QUALITY, false) // Boolean in String key
        fakePrefs.putRaw(PreferenceKeys.KEY_ENABLE_TOAST, 123456L) // Long in Boolean key
        fakePrefs.putRaw(PreferenceKeys.KEY_ENABLE_PLAYER_BADGE, 3.14159f) // Float in Boolean key
        fakePrefs.putRaw(PreferenceKeys.KEY_ENABLE_ANTI_ROLL, setOf("invalid", "set")) // Set in Boolean key

        val loaded = ConfigManager.loadFromPreferences(fakePrefs)

        // Must safely degrade to defaults without throwing ClassCastException
        assertEquals(PreferenceKeys.DEFAULT_ENABLED_SOURCES, loaded.enabledSources)
        assertEquals(PreferenceKeys.DEFAULT_PREFERRED_QUALITY, loaded.preferredQuality)
        assertEquals(PreferenceKeys.DEFAULT_ENABLE_TOAST, loaded.enableToast)
        assertEquals(PreferenceKeys.DEFAULT_ENABLE_PLAYER_BADGE, loaded.enablePlayerBadge)
        assertEquals(PreferenceKeys.DEFAULT_ENABLE_ANTI_ROLL, loaded.enableAntiRoll)
    }

    @Test
    fun testBooleanStringsCorrectlyParsedInDefensiveReader() {
        // Lowercase string representations of booleans
        fakePrefs.putRaw(PreferenceKeys.KEY_ENABLE_TOAST, "false")
        fakePrefs.putRaw(PreferenceKeys.KEY_ENABLE_PLAYER_BADGE, "true")
        fakePrefs.putRaw(PreferenceKeys.KEY_ENABLE_ANTI_ROLL, "false")

        val loaded = ConfigManager.loadFromPreferences(fakePrefs)
        assertFalse(loaded.enableToast)
        assertTrue(loaded.enablePlayerBadge)
        assertFalse(loaded.enableAntiRoll)
    }

    @Test
    fun testUppercaseOrInvalidBooleanStringsFallbackToDefault() {
        // Note: toBooleanStrictOrNull is case-sensitive, so uppercase "FALSE" / "TRUE"
        // or arbitrary words "yes" / "no" fall back safely to defaults.
        fakePrefs.putRaw(PreferenceKeys.KEY_ENABLE_TOAST, "FALSE")
        fakePrefs.putRaw(PreferenceKeys.KEY_ENABLE_PLAYER_BADGE, "maybe")
        fakePrefs.putRaw(PreferenceKeys.KEY_ENABLE_ANTI_ROLL, "TRUE")

        val loaded = ConfigManager.loadFromPreferences(fakePrefs)
        assertEquals(PreferenceKeys.DEFAULT_ENABLE_TOAST, loaded.enableToast)
        assertEquals(PreferenceKeys.DEFAULT_ENABLE_PLAYER_BADGE, loaded.enablePlayerBadge)
        assertEquals(PreferenceKeys.DEFAULT_ENABLE_ANTI_ROLL, loaded.enableAntiRoll)
    }

    @Test
    fun testUnexpectedAndGarbageKeysIgnoredSafely() {
        fakePrefs.edit().apply {
            for (i in 0 until 500) {
                putString("unrelated_garbage_key_$i", "value_$i")
                putInt("unrelated_int_$i", i)
            }
            putString(PreferenceKeys.KEY_PREFERRED_QUALITY, "flac")
        }.apply()

        val loaded = ConfigManager.loadFromPreferences(fakePrefs)
        assertEquals("flac", loaded.preferredQuality)
        assertEquals(PreferenceKeys.DEFAULT_ENABLED_SOURCES, loaded.enabledSources)
    }

    // =========================================================================
    // Category 4: Listener Fault Isolation
    // =========================================================================

    @Test
    fun testMaliciousListenerExceptionDoesNotAffectOtherListenersOrWriter() {
        ConfigManager.init(fakePrefs)

        val firstExecuted = AtomicBoolean(false)
        val thirdExecuted = AtomicBoolean(false)

        ConfigManager.addOnConfigChangeListener {
            firstExecuted.set(true)
        }

        // Malicious listener that throws an arbitrary exception
        ConfigManager.addOnConfigChangeListener {
            throw IllegalStateException("Malicious listener exploded!")
        }

        ConfigManager.addOnConfigChangeListener {
            thirdExecuted.set(true)
        }

        // Writer applies change; must not throw
        fakePrefs.edit()
            .putString(PreferenceKeys.KEY_PREFERRED_QUALITY, "128k")
            .apply()

        assertTrue("First listener must execute", firstExecuted.get())
        assertTrue("Third listener must execute despite second listener failing", thirdExecuted.get())
        assertEquals("128k", ConfigManager.getConfig().preferredQuality)
    }

    // =========================================================================
    // Category 5: SharedPreferences Clear Notification
    // =========================================================================

    @Test
    fun testPreferencesClearNotificationResetsConfigToDefaults() {
        ConfigManager.init(fakePrefs)

        fakePrefs.edit()
            .putString(PreferenceKeys.KEY_PREFERRED_QUALITY, "flac")
            .putString(PreferenceKeys.KEY_ENABLED_SOURCES, "migu,kugou")
            .putBoolean(PreferenceKeys.KEY_ENABLE_TOAST, false)
            .apply()

        assertEquals("flac", ConfigManager.getConfig().preferredQuality)
        assertEquals(listOf("migu", "kugou"), ConfigManager.getConfig().enabledSources)
        assertFalse(ConfigManager.getConfig().enableToast)

        // Clear all preferences
        fakePrefs.edit().clear().apply()

        // ConfigManager must have received (prefs, null) notification and reloaded defaults
        val resetConfig = ConfigManager.getConfig()
        assertEquals(PreferenceKeys.DEFAULT_PREFERRED_QUALITY, resetConfig.preferredQuality)
        assertEquals(PreferenceKeys.DEFAULT_ENABLED_SOURCES, resetConfig.enabledSources)
        assertTrue(resetConfig.enableToast)
        assertTrue(resetConfig.enablePlayerBadge)
        assertTrue(resetConfig.enableAntiRoll)
    }

    // =========================================================================
    // Category 6: Integration Stress - PlayerUrlResolver with Live Preference Mutations
    // =========================================================================

    @Test
    fun testPlayerUrlResolverConcurrentlyWithConfigManagerLiveMutations() {
        ConfigManager.init(fakePrefs)

        val songCache = UnmLruCache<Long, SongInfo>(1000)
        for (i in 1L..100L) {
            songCache.put(i, SongInfo(i, "Song$i", "Artist$i", 200000L))
        }

        val eventCount = AtomicInteger(0)
        val resolver = PlayerUrlResolver(
            songCache = songCache,
            audioMatcher = { song, config ->
                MatchedAudio(
                    url = "http://audio.${config.enabledSources.firstOrNull() ?: "kuwo"}.cn/${song.id}.mp3",
                    br = 0,
                    source = config.enabledSources.firstOrNull() ?: "kuwo",
                    matchedTitle = song.name,
                    matchedArtist = song.artist
                )
            },
            songDetailFetcher = null,
            configProvider = { ConfigManager.getConfig() },
            onTrackReplaced = { eventCount.incrementAndGet() }
        )

        val executor = Executors.newFixedThreadPool(8)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(8)
        val resolutionErrors = AtomicInteger(0)

        // 6 threads resolving player URLs
        for (t in 0 until 6) {
            executor.execute {
                startLatch.await()
                try {
                    for (k in 1..200) {
                        val songId = (k % 100 + 1).toLong()
                        val payload = """
                            {
                                "code": 200,
                                "data": [
                                    {
                                        "id": $songId,
                                        "url": null,
                                        "br": 0,
                                        "trialMode": 1
                                    }
                                ]
                            }
                        """.trimIndent()
                        val result = resolver.processPlayerUrl(
                            "https://interface.music.163.com/eapi/song/enhance/player/url",
                            payload
                        )
                        assertNotNull("Result must not be null", result)
                    }
                } catch (t: Throwable) {
                    resolutionErrors.incrementAndGet()
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        // 2 threads rapidly changing configurations
        for (t in 0 until 2) {
            executor.execute {
                startLatch.await()
                try {
                    val qualities = listOf("flac", "320k", "192k", "128k")
                    for (k in 0 until 50) {
                        val q = qualities[k % qualities.size]
                        fakePrefs.edit()
                            .putString(PreferenceKeys.KEY_PREFERRED_QUALITY, q)
                            .putBoolean(PreferenceKeys.KEY_ENABLE_TOAST, k % 2 == 0)
                            .apply()
                        Thread.sleep(2)
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        assertTrue("Resolver stress test must finish in 10s", doneLatch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("Zero resolution errors during concurrent mutations", 0, resolutionErrors.get())
        assertTrue("Replace events must have been dispatched", eventCount.get() > 0)
    }
}

/**
 * SharedPreferences implementation with WeakHashMap listener storage strictly matching
 * `io.github.libxposed.service.RemotePreferences` and Android `SharedPreferencesImpl`.
 */
class WeakHashMapSharedPreferences : SharedPreferences {
    private val data = ConcurrentHashMap<String, Any>()
    private val listeners = Collections.synchronizedMap(WeakHashMap<SharedPreferences.OnSharedPreferenceChangeListener, Any>())

    val listenerCount: Int
        get() {
            // Force stale weak reference expulsion
            return synchronized(listeners) { listeners.size }
        }

    override fun getAll(): MutableMap<String, *> = HashMap(data)

    override fun getString(key: String?, defValue: String?): String? {
        val v = data[key] ?: return defValue
        if (v is String) return v
        throw ClassCastException("Value $v cannot be cast to String")
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        data[key] as? MutableSet<String> ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        val v = data[key] ?: return defValue
        if (v is Boolean) return v
        throw ClassCastException("Value $v cannot be cast to Boolean")
    }

    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null) {
            listeners[listener] = Any()
        }
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null) {
            listeners.remove(listener)
        }
    }

    inner class Editor : SharedPreferences.Editor {
        private val temp = mutableMapOf<String, Any?>()
        private val removeKeys = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            key?.let { temp[it] = value }
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            key?.let { temp[it] = values }
            return this
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            key?.let { temp[it] = value }
            return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            key?.let { temp[it] = value }
            return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            key?.let { temp[it] = value }
            return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            key?.let { temp[it] = value }
            return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            key?.let { removeKeys.add(it) }
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            val changedKeys = mutableSetOf<String>()
            if (clearAll) {
                changedKeys.addAll(data.keys)
                data.clear()
            }
            removeKeys.forEach {
                data.remove(it)
                changedKeys.add(it)
            }
            temp.forEach { (k, v) ->
                if (v != null) {
                    data[k] = v
                } else {
                    data.remove(k)
                }
                changedKeys.add(k)
            }

            val registeredListeners = synchronized(listeners) {
                ArrayList(listeners.keys)
            }

            if (clearAll) {
                for (listener in registeredListeners) {
                    listener?.onSharedPreferenceChanged(this@WeakHashMapSharedPreferences, null)
                }
            } else {
                for (key in changedKeys) {
                    for (listener in registeredListeners) {
                        listener?.onSharedPreferenceChanged(this@WeakHashMapSharedPreferences, key)
                    }
                }
            }
        }
    }
}
