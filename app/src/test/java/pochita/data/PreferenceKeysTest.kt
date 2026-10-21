package pochita.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PreferenceKeysTest {

    @Test
    fun testSerializeSourcesPreservesExactOrdering() {
        val sources = listOf("kugou", "migu", "kuwo", "bodian")
        val serialized = PreferenceKeys.serializeSources(sources)
        assertEquals("kugou,migu,kuwo,bodian", serialized)
    }

    @Test
    fun testSerializeSourcesFiltersOutUnknownAndNormalizesJs() {
        val sources = listOf("spotify", "kuwo", "custom_js", "apple_music")
        val serialized = PreferenceKeys.serializeSources(sources)
        assertEquals("kuwo,customjs", serialized)
    }

    @Test
    fun testDeserializeCsvSources() {
        val csv = "kuwo,bodian,migu"
        val deserialized = PreferenceKeys.deserializeSources(csv)
        assertEquals(listOf("kuwo", "bodian", "migu"), deserialized)
    }

    @Test
    fun testDeserializeJsonArraySources() {
        val json = "[\"migu\", \"kugou\", \"customjs\"]"
        val deserialized = PreferenceKeys.deserializeSources(json)
        assertEquals(listOf("migu", "kugou", "customjs"), deserialized)
    }

    @Test
    fun testDeserializeNullOrBlankReturnsDefault() {
        assertEquals(PreferenceKeys.DEFAULT_ENABLED_SOURCES, PreferenceKeys.deserializeSources(null))
        assertEquals(PreferenceKeys.DEFAULT_ENABLED_SOURCES, PreferenceKeys.deserializeSources(""))
        assertEquals(PreferenceKeys.DEFAULT_ENABLED_SOURCES, PreferenceKeys.deserializeSources("   "))
    }

    @Test
    fun testDeserializeCorruptedStringReturnsDefault() {
        assertEquals(PreferenceKeys.DEFAULT_ENABLED_SOURCES, PreferenceKeys.deserializeSources("[bad json {"))
        assertEquals(PreferenceKeys.DEFAULT_ENABLED_SOURCES, PreferenceKeys.deserializeSources("unknown1,unknown2"))
    }
}
