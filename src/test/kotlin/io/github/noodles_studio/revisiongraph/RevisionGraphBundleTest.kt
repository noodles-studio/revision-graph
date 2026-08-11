package io.github.noodles_studio.revisiongraph

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

class RevisionGraphBundleTest {
    @Test
    fun `english and chinese bundles contain the same keys`() {
        val english = loadProperties("/messages/RevisionGraphBundle.properties")
        val chinese = loadProperties("/messages/RevisionGraphBundle_zh_CN.properties")

        assertEquals(english.stringPropertyNames(), chinese.stringPropertyNames())
        assertEquals("Revision Graph", english.getProperty("toolwindow.title"))
        assertEquals("修订图", chinese.getProperty("toolwindow.title"))
    }

    private fun loadProperties(path: String): Properties = Properties().apply {
        RevisionGraphBundleTest::class.java.getResourceAsStream(path)!!.reader(Charsets.UTF_8).use(::load)
    }
}
