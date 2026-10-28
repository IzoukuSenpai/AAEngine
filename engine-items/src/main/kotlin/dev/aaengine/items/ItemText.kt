package dev.aaengine.items

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags

/**
 * Textes d'items en MiniMessage. Uniquement pour la configuration écrite par l'administrateur, jamais pour
 * une saisie joueur. Jeu de balises restreint à la mise en forme : pas de clic, survol, insertion, sélecteur,
 * score ni NBT (inutiles sur un item et résolus côté serveur).
 */
object ItemText {
    private val MINI: MiniMessage =
        MiniMessage
            .builder()
            .tags(
                TagResolver.resolver(
                    StandardTags.color(),
                    StandardTags.decorations(),
                    StandardTags.gradient(),
                    StandardTags.rainbow(),
                    StandardTags.transition(),
                    StandardTags.reset(),
                    StandardTags.font(),
                    StandardTags.shadowColor(),
                    StandardTags.pride(),
                    StandardTags.translatable(),
                    StandardTags.translatableFallback(),
                    StandardTags.keybind(),
                ),
            ).build()

    /** Nom d'item (composant `item_name`, non italique par défaut côté client). */
    @JvmStatic
    fun name(raw: String): Component = MINI.deserialize(raw)

    /** Ligne de lore : italique désactivé sauf si la ligne le demande explicitement. */
    @JvmStatic
    fun loreLine(raw: String): Component = MINI.deserialize(raw).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
