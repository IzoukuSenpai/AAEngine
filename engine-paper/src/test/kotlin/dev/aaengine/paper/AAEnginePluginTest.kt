package dev.aaengine.paper

import dev.aaengine.api.AAEngineApiVersion
import dev.aaengine.api.AAEngineProvider
import dev.aaengine.api.event.AAEngineContentLoadedEvent
import net.kyori.adventure.key.Key
import org.bukkit.plugin.PluginDescriptionFile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

/** Activation complète du plugin sur serveur simulé : fichiers par défaut, chargement initial, API. */
class AAEnginePluginTest {
    private lateinit var server: ServerMock

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    private fun loadedEvents() =
        server.pluginManager.firedEvents
            .toList()
            .filterIsInstance<AAEngineContentLoadedEvent>()

    @Test
    fun `activation charge l'exemple et installe l'API`() {
        val plugin =
            MockBukkit.loadWith(
                AAEnginePlugin::class.java,
                PluginDescriptionFile("AAEngine", "0.2.0-test", AAEnginePlugin::class.java.name),
            )
        try {
            val api = AAEngineProvider.get()
            assertEquals(AAEngineApiVersion.CURRENT, api.apiVersion)
            // Parsing sur le thread dédié AAEngine-Reload puis construction/publication au tick suivant.
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!api.isContentLoaded && System.nanoTime() < deadline) {
                Thread.sleep(10)
                server.scheduler.performOneTick()
            }
            assertTrue(api.isContentLoaded)
            assertTrue(api.items.exists(Key.key("aaengine", "forest_axe")))
            assertTrue(api.lastReload!!.success)
            assertTrue(plugin.dataFolder.resolve("config.yml").isFile)
            assertTrue(loadedEvents().any { it.initial })
        } finally {
            server.pluginManager.disablePlugin(plugin)
        }
        assertFalse(AAEngineProvider.find().isPresent, "API retirée à la désactivation")
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
