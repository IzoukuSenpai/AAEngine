package dev.aaengine.core.net

import dev.aaengine.core.config.EngineConfig
import dev.aaengine.core.config.PackDistribution
import dev.aaengine.core.diagnostic.DiagnosticCollector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** URL publique du pack (§18.1) et section `pack` de `config.yml`. */
class PublicHttpsUrlTest {
    @Test
    fun `https public accepte`() {
        assertTrue(PublicHttpsUrl.check("https://cdn.example.com/packs/aaengine.zip").ok)
        assertTrue(PublicHttpsUrl.check("https://93.184.216.34:8443/p.zip").ok)
        assertTrue(PublicHttpsUrl.check("https://[2606:4700::1111]/p.zip").ok)
    }

    @Test
    fun `http, identifiants et fragment refuses`() {
        for (url in listOf(
            "http://cdn.example.com/p.zip",
            "ftp://cdn.example.com/p.zip",
            "https://user:pass@cdn.example.com/p.zip",
            "https://cdn.example.com/p.zip#x",
            "https://cdn.example.com/a b.zip",
            "https:///p.zip",
            "",
        )) {
            assertFalse(PublicHttpsUrl.check(url).ok, url)
        }
    }

    @Test
    fun `hotes locaux et prives refuses`() {
        for (url in listOf(
            "https://127.0.0.1/p.zip",
            "https://10.1.2.3/p.zip",
            "https://172.16.0.1/p.zip",
            "https://192.168.1.10/p.zip",
            "https://169.254.169.254/latest",
            "https://100.64.0.1/p.zip",
            "https://0.0.0.0/p.zip",
            "https://[::1]/p.zip",
            "https://[fc00::1]/p.zip",
            "https://[fe80::1]/p.zip",
            "https://[::ffff:127.0.0.1]/p.zip",
            "https://localhost/p.zip",
            "https://pack.localhost/p.zip",
            "https://nas.local/p.zip",
            "https://127.1/p.zip",
            "https://2130706433/p.zip",
            "https://0x7f.0.0.1/p.zip",
            "https://010.0.0.1/p.zip",
            "https://intranet/p.zip",
        )) {
            assertFalse(PublicHttpsUrl.check(url).ok, url)
        }
    }

    @Test
    fun `opt-in reseau prive`() {
        assertTrue(PublicHttpsUrl.check("https://192.168.1.10/p.zip", allowPrivate = true).ok)
        assertFalse(PublicHttpsUrl.check("http://192.168.1.10/p.zip", allowPrivate = true).ok)
    }

    @Test
    fun `section pack de la configuration`() {
        val d = DiagnosticCollector()
        val cfg =
            EngineConfig.parse(
                "pack:\n  distribution: external\n  url: https://cdn.example.com/aaengine-{sha1}.zip\n  required: true\n  prompt: Pack requis\n",
                "config.yml",
                d,
            )
        assertEquals(PackDistribution.EXTERNAL, cfg.pack.distribution)
        assertTrue(cfg.pack.required)
        assertEquals("Pack requis", cfg.pack.prompt)
        assertEquals(0, d.warningCount)
    }

    @Test
    fun `url http desactive la distribution`() {
        val d = DiagnosticCollector()
        val cfg = EngineConfig.parse("pack:\n  distribution: external\n  url: http://cdn.example.com/p.zip\n", "config.yml", d)
        assertEquals(PackDistribution.NONE, cfg.pack.distribution)
        assertEquals("pack.url", d.toList().single().path)
        assertFalse(d.hasErrors)
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
