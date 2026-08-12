package io.github.noodles_studio.revisiongraph.ui

import com.intellij.openapi.project.DumbAware
import kotlin.test.Test
import kotlin.test.assertTrue

class RevisionGraphToolWindowFactoryTest {
    @Test
    fun `tool window can be created while indexes are unavailable`() {
        assertTrue(
            DumbAware::class.java.isAssignableFrom(RevisionGraphToolWindowFactory::class.java),
            "RevisionGraph must not wait for smart mode before creating its tool window",
        )
    }
}
