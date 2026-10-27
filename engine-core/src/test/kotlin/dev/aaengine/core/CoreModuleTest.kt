package dev.aaengine.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CoreModuleTest {
    @Test
    fun `module id is engine-core`() {
        assertEquals("engine-core", CoreModule.ID)
    }
}
