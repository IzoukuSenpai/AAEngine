package dev.aaengine.paper.pack

import dev.aaengine.core.config.PackDistribution
import dev.aaengine.core.config.PackSettings
import dev.aaengine.core.log.EngineLog
import dev.aaengine.core.log.LogCategory
import dev.aaengine.core.net.PublicHttpsUrl
import dev.aaengine.pack.BuiltPack
import net.kyori.adventure.resource.ResourcePackInfo
import net.kyori.adventure.resource.ResourcePackRequest
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Distribution `external` (§17, WP2d minimal) : au join, envoie l'URL HTTPS configurée avec le SHA-1 du
 * dernier pack construit, via `ResourcePackRequest` (API Paper/Adventure). Aucun serveur HTTP intégré :
 * l'administrateur publie lui-même `cache/pack/aaengine-pack.zip` à cette URL.
 *
 * L'URL est revérifiée ([PublicHttpsUrl]) après substitution de `{sha1}` ; en cas de doute, rien n'est envoyé.
 * L'évènement de join s'exécute sur l'ordonnanceur du joueur (Folia) : l'envoi y est fait directement.
 */
class PackDistributor(
    private val settings: PackSettings,
    private val packs: () -> BuiltPack?,
    private val log: EngineLog,
) : Listener {
    private val warnedMissing = AtomicBoolean(false)

    val enabled: Boolean
        get() = settings.distribution == PackDistribution.EXTERNAL && settings.url != null

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        send(event.player)
    }

    /** Demande construite pour le pack courant, ou `null` si la distribution est inactive ou impossible. */
    fun request(): ResourcePackRequest? {
        if (!enabled) return null
        val pack = packs()
        if (pack == null) {
            if (warnedMissing.compareAndSet(false, true)) {
                log.warn(LogCategory.PACK, "Distribution external : aucun pack construit, rien n'est envoyé (lancez /aaengine pack build).")
            }
            return null
        }
        val url = PackSettings.expandUrl(settings.url ?: return null, pack.sha1)
        val check = PublicHttpsUrl.check(url, settings.allowPrivateHosts)
        val uri = check.uri
        if (uri == null) {
            log.error(LogCategory.SECURITY, "URL du pack refusée après substitution (${check.reason}) : rien n'est envoyé.")
            return null
        }
        val info = ResourcePackInfo.resourcePackInfo(PACK_ID, uri, pack.sha1)
        val builder =
            ResourcePackRequest
                .resourcePackRequest()
                .packs(info)
                .required(settings.required)
                .replace(false)
        settings.prompt?.let { builder.prompt(Component.text(it)) }
        return builder.build()
    }

    fun send(player: Player) {
        val request = request() ?: return
        player.sendResourcePacks(request)
    }

    companion object {
        /** Identifiant stable du pack AAEngine côté client : un nouvel envoi remplace le précédent. */
        @JvmField
        val PACK_ID: UUID = UUID.nameUUIDFromBytes("dev.aaengine:resource-pack".toByteArray(Charsets.UTF_8))
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
