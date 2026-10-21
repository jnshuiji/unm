package pochita.config

import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pochita.data.PreferenceKeys

class ConfigManagerTest {

    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    fun setUp() {
        ConfigManager.resetForTesting()
        fakePrefs = FakeSharedPreferences()
    }

    @After
    fun tearDown() {
        ConfigManager.resetForTesting()
    }

    // =========================================================================
    // Tier 1: Defaults & Baseline Verification
    // =========================================================================

    @Test
    fun testDefaultConfigValues() {
        val config = UnmConfig()
        assertEquals(listOf("bodian", "kuwo", "migu", "kugou"), config.enabledSources)
        assertEquals("320k", config.preferredQuality)
        assertTrue(config.enableToast)
        assertTrue(config.enablePlayerBadge)
        assertTrue(config.enableAntiRoll)
        assertEquals(320000, config.targetBitrate())
        assertEquals("exhigh", config.targetLevel())
        assertEquals("mp3", config.targetFormat())
    }

    @Test
    fun testLoadFromNullPreferencesReturnsDefaults() {
        val config = ConfigManager.loadFromPreferences(null)
        assertEquals(UnmConfig(), config)
    }

    @Test
    fun testLoadFromEmptyPreferencesReturnsDefaults() {
        val config = ConfigManager.loadFromPreferences(fakePrefs)
        assertEquals(UnmConfig(), config)
    }

    @Test
    fun testQualityBitrateAndLevelCalculations() {
        val flac = UnmConfig(preferredQuality = "flac")
        assertEquals(999000, flac.targetBitrate())
        assertEquals("lossless", flac.targetLevel())
        assertEquals("flac", flac.targetFormat())

        val q320 = UnmConfig(preferredQuality = "320k")
        assertEquals(320000, q320.targetBitrate())
        assertEquals("exhigh", q320.targetLevel())
        assertEquals("mp3", q320.targetFormat())

        val q192 = UnmConfig(preferredQuality = "192k")
        assertEquals(192000, q192.targetBitrate())
        assertEquals("higher", q192.targetLevel())
        assertEquals("mp3", q192.targetFormat())

        val q128 = UnmConfig(preferredQuality = "128k")
        assertEquals(128000, q128.targetBitrate())
        assertEquals("standard", q128.targetLevel())
        assertEquals("mp3", q128.targetFormat())
    }

    // =========================================================================
    // Tier 2: Serialization & Deserialization (Round-Trip)
    // =========================================================================

    @Test
    fun testSaveAndLoadFullConfigRoundTrip() {
        val custom = UnmConfig(
            enabledSources = listOf("kuwo", "bodian"),
            preferredQuality = "flac",
            enableToast = false,
            enablePlayerBadge = true,
            enableAntiRoll = false,
        )

        ConfigManager.saveToPreferences(fakePrefs, custom)
        val loaded = ConfigManager.loadFromPreferences(fakePrefs)

        assertEquals(custom, loaded)
    }

    @Test
    fun testSourceOrderingStrictlyPreserved() {
        val reverseOrder = listOf("kugou", "migu", "kuwo", "bodian")
        val custom = UnmConfig(enabledSources = reverseOrder)

        ConfigManager.saveToPreferences(fakePrefs, custom)
        val loaded = ConfigManager.loadFromPreferences(fakePrefs)

        assertEquals(reverseOrder, loaded.enabledSources)
    }

    @Test
    fun testAllSupportedQualitiesRoundTrip() {
        for (q in UnmConfig.SUPPORTED_QUALITIES) {
            val cfg = UnmConfig(preferredQuality = q)
            ConfigManager.saveToPreferences(fakePrefs, cfg)
            val loaded = ConfigManager.loadFromPreferences(fakePrefs)
            assertEquals("Quality $q must preserve round trip", q, loaded.preferredQuality)
        }
    }

    @Test
    fun testJsonArraySourcesDeserialization() {
        fakePrefs.edit()
            .putString(PreferenceKeys.KEY_ENABLED_SOURCES, "[\"migu\", \"kuwo\", \"customjs\"]")
            .apply()

        val loaded = ConfigManager.loadFromPreferences(fakePrefs)
        assertEquals(listOf("migu", "kuwo", "customjs"), loaded.enabledSources)
    }

    @Test
    fun testCommaSeparatedSourcesDeserialization() {
        fakePrefs.edit()
            .putString(PreferenceKeys.KEY_ENABLED_SOURCES, "migu,kuwo,bodian")
            .apply()

        val loaded = ConfigManager.loadFromPreferences(fakePrefs)
        assertEquals(listOf("migu", "kuwo", "bodian"), loaded.enabledSources)
    }

    // =========================================================================
    // Tier 3: Fault-Tolerant Fallback & Defensive Parsing
    // =========================================================================

    @Test
    fun testCorruptedJsonInSourcesFallsBackSafely() {
        fakePrefs.edit()
            .putString(PreferenceKeys.KEY_ENABLED_SOURCES, "[invalid json string {[[")
            .apply()

        val loaded = ConfigManager.loadFromPreferences(fakePrefs)
        assertEquals(UnmConfig.DEFAULT_ENABLED_SOURCES, loaded.enabledSources)
    }

    @Test
    fun testEmptyOrBlankSourcesStringFallsBackToDefault() {
        fakePrefs.edit()
            .putString(PreferenceKeys.KEY_ENABLED_SOURCES, "   ")
            .apply()

        val loaded = ConfigManager.loadFromPreferences(fakePrefs)
        assertEquals(UnmConfig.DEFAULT_ENABLED_SOURCES, loaded.enabledSources)
    }

    @Test
    fun testUnknownSourcesFilteredOut() {
        fakePrefs.edit()
            .putString(PreferenceKeys.KEY_ENABLED_SOURCES, "spotify,kuwo,applemusic,bodian,youtube")
            .apply()

        val loaded = ConfigManager.loadFromPreferences(fakePrefs)
        assertEquals(listOf("kuwo", "bodian"), loaded.enabledSources)
    }

    @Test
    fun testInvalidQualityFallsBackToDefault() {
        fakePrefs.edit()
            .putString(PreferenceKeys.KEY_PREFERRED_QUALITY, "ultra_999k_master")
            .apply()

        val loaded = ConfigManager.loadFromPreferences(fakePrefs)
        assertEquals(UnmConfig.DEFAULT_PREFERRED_QUALITY, loaded.preferredQuality)
    }

    @Test
    fun testClassCastExceptionInBooleansHandledGracefully() {
        // Intentionally storing a String or Integer in boolean preference keys
        fakePrefs.putRaw(PreferenceKeys.KEY_ENABLE_TOAST, "false")
        fakePrefs.putRaw(PreferenceKeys.KEY_ENABLE_PLAYER_BADGE, 12345)

        val loaded = ConfigManager.loadFromPreferences(fakePrefs)
        assertFalse("String 'false' should be safely parsed", loaded.enableToast)
        assertTrue("Incompatible object should fall back to default true", loaded.enablePlayerBadge)
    }

    @Test
    fun testPartialConfigurationLoadsStoredAndDefaultsRemaining() {
        fakePrefs.edit()
            .putString(PreferenceKeys.KEY_PREFERRED_QUALITY, "128k")
            .apply()

        val loaded = ConfigManager.loadFromPreferences(fakePrefs)
        assertEquals("128k", loaded.preferredQuality)
        assertEquals(UnmConfig.DEFAULT_ENABLED_SOURCES, loaded.enabledSources)
        assertTrue(loaded.enableToast)
        assertTrue(loaded.enablePlayerBadge)
        assertTrue(loaded.enableAntiRoll)
    }

    // =========================================================================
    // Tier 4: Dynamic Hot Reload, Listeners & Lifecycle
    // =========================================================================

    @Test
    fun testDynamicReloadOnPreferenceChange() {
        ConfigManager.init(fakePrefs)
        assertEquals("320k", ConfigManager.getConfig().preferredQuality)

        fakePrefs.edit()
            .putString(PreferenceKeys.KEY_PREFERRED_QUALITY, "flac")
            .apply()

        assertEquals("flac", ConfigManager.getConfig().preferredQuality)
    }

    @Test
    fun testConfigChangeListenerTriggered() {
        ConfigManager.init(fakePrefs)
        var receivedConfig: UnmConfig? = null

        val unregister = ConfigManager.addOnConfigChangeListener {
            receivedConfig = it
        }

        fakePrefs.edit()
            .putString(PreferenceKeys.KEY_PREFERRED_QUALITY, "192k")
            .apply()

        assertNotNull(receivedConfig)
        assertEquals("192k", receivedConfig?.preferredQuality)

        unregister()
    }

    @Test
    fun testConfigChangeListenerUnregister() {
        ConfigManager.init(fakePrefs)
        var triggerCount = 0

        val unregister = ConfigManager.addOnConfigChangeListener {
            triggerCount++
        }

        fakePrefs.edit()
            .putString(PreferenceKeys.KEY_PREFERRED_QUALITY, "flac")
            .apply()
        assertEquals(1, triggerCount)

        unregister()

        fakePrefs.edit()
            .putString(PreferenceKeys.KEY_PREFERRED_QUALITY, "128k")
            .apply()
        assertEquals("Unregistered listener must not receive further events", 1, triggerCount)
    }

    @Test
    fun testTestingSeamOverride() {
        val override = UnmConfig(preferredQuality = "128k")
        ConfigManager.setConfigForTesting(override)

        assertEquals("128k", ConfigManager.getConfig().preferredQuality)

        ConfigManager.resetForTesting()
        assertEquals("320k", ConfigManager.getConfig().preferredQuality)
    }

    @Test
    fun testThreadSafeConcurrentReads() {
        ConfigManager.init(fakePrefs)
        val executor = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(1)
        val doneLatch = CountDownLatch(8)
        val readCount = AtomicInteger(0)

        for (i in 0 until 8) {
            executor.execute {
                latch.await()
                for (j in 0 until 1000) {
                    val cfg = ConfigManager.getConfig()
                    assertNotNull(cfg)
                    readCount.incrementAndGet()
                }
                doneLatch.countDown()
            }
        }

        latch.countDown()
        // Concurrent writes while threads are reading
        for (k in 0 until 50) {
            fakePrefs.edit()
                .putString(PreferenceKeys.KEY_PREFERRED_QUALITY, if (k % 2 == 0) "flac" else "320k")
                .apply()
        }

        assertTrue("Concurrent reads should finish within 5s", doneLatch.await(5, TimeUnit.SECONDS))
        assertEquals(8000, readCount.get())
        executor.shutdown()
    }
}

/**
 * Pure Kotlin in-memory [SharedPreferences] implementation with live change listener support.
 */
class FakeSharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any?>()
    private val listeners = CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    fun putRaw(key: String, value: Any?) {
        data[key] = value
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
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener)
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
                data[k] = v
                changedKeys.add(k)
            }
            for (key in changedKeys) {
                for (listener in listeners) {
                    listener.onSharedPreferenceChanged(this@FakeSharedPreferences, key)
                }
            }
        }
    }
}
