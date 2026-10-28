package dev.aaengine.paper.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.tree.LiteralCommandNode
import dev.aaengine.core.diagnostic.Severity
import dev.aaengine.core.engine.DefaultAAEngine
import dev.aaengine.core.reload.ReloadResult
import dev.aaengine.core.reload.ReloadService
import dev.aaengine.items.ItemIds
import dev.aaengine.pack.PackStatus
import dev.aaengine.paper.pack.PackReport
import dev.aaengine.paper.pack.PackService
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture

/**
 * Commandes `/aaengine` (alias `/aae`) via Brigadier (§32) : `reload`, `give`, `items`, `info`,
 * `pack build [force]`, `pack publish` et `validate`.
 * Tous les textes affichés sont des composants littéraux : aucune saisie n'est interprétée en MiniMessage.
 */
class AAEngineCommands(
    private val plugin: Plugin,
    private val engine: DefaultAAEngine,
    private val reloads: ReloadService<*>,
    private val packs: PackService? = null,
) {
    /** Enregistre l'arbre de commandes auprès de Paper. */
    fun register(registrar: Commands) {
        registrar.register(node(), "Administration d'AAEngine", listOf(ALIAS))
    }

    fun node(): LiteralCommandNode<CommandSourceStack> =
        Commands
            .literal(ROOT)
            .requires { src -> Permissions.ALL_COMMANDS.any { src.sender.hasPermission(it) } }
            .then(
                Commands
                    .literal("reload")
                    .requires { it.sender.hasPermission(Permissions.RELOAD) }
                    .executes { ctx -> reload(ctx.source.sender) },
            ).then(
                Commands
                    .literal("give")
                    .requires { it.sender.hasPermission(Permissions.GIVE) }
                    .then(
                        Commands
                            .argument("player", ArgumentTypes.player())
                            .then(
                                Commands
                                    .argument("id", ArgumentTypes.key())
                                    .suggests(::suggestIds)
                                    .executes { ctx -> give(ctx, 1) }
                                    .then(
                                        Commands
                                            .argument("amount", IntegerArgumentType.integer(1, MAX_GIVE))
                                            .executes { ctx -> give(ctx, IntegerArgumentType.getInteger(ctx, "amount")) },
                                    ),
                            ),
                    ),
            ).then(
                Commands
                    .literal("items")
                    .requires { it.sender.hasPermission(Permissions.ITEMS) }
                    .executes { ctx -> listItems(ctx.source.sender, 1) }
                    .then(
                        Commands
                            .argument("page", IntegerArgumentType.integer(1))
                            .executes { ctx -> listItems(ctx.source.sender, IntegerArgumentType.getInteger(ctx, "page")) },
                    ),
            ).then(
                Commands
                    .literal("info")
                    .requires { it.sender.hasPermission(Permissions.INFO) }
                    .executes { ctx -> info(ctx.source.sender) },
            ).then(
                Commands
                    .literal("pack")
                    .requires { it.sender.hasPermission(Permissions.PACK) }
                    .then(
                        Commands
                            .literal("build")
                            .executes { ctx -> packBuild(ctx.source.sender, force = false) }
                            .then(Commands.literal("force").executes { ctx -> packBuild(ctx.source.sender, force = true) }),
                    ).then(Commands.literal("publish").executes { ctx -> packPublish(ctx.source.sender) }),
            ).then(
                Commands
                    .literal("validate")
                    .requires { it.sender.hasPermission(Permissions.VALIDATE) }
                    .executes { ctx -> packValidate(ctx.source.sender) },
            ).build()

    private fun packBuild(
        sender: CommandSender,
        force: Boolean,
    ): Int {
        val service = packs ?: return unavailable(sender)
        val future = service.build(force)
        if (future == null) {
            sender.sendMessage(err("Une opération sur le pack est déjà en cours."))
            return 0
        }
        sender.sendMessage(note(if (force) "Reconstruction complète du pack…" else "Construction du pack…"))
        future.whenComplete { r, error -> runFor(sender) { if (error != null) interrupted(sender, error) else packReport(sender, r) } }
        return Command.SINGLE_SUCCESS
    }

    private fun packPublish(sender: CommandSender): Int {
        val service = packs ?: return unavailable(sender)
        val future = service.publish()
        if (future == null) {
            sender.sendMessage(err("Une opération sur le pack est déjà en cours."))
            return 0
        }
        future.whenComplete { published, error ->
            runFor(sender) {
                when {
                    error != null -> interrupted(sender, error)
                    published == null -> sender.sendMessage(err("Aucun pack construit intact à annoncer : lancez /$ROOT pack build."))
                    else -> {
                        sender.sendMessage(ok("Pack annoncé aux prochaines connexions : SHA-1 ${published.sha1}."))
                        sender.sendMessage(
                            Component.text(
                                " Le fichier servi à l'URL doit être exactement ce zip (sinon le client le refuse).",
                                NamedTextColor.GRAY,
                            ),
                        )
                    }
                }
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun packValidate(sender: CommandSender): Int {
        val service = packs ?: return unavailable(sender)
        val future = service.validate()
        if (future == null) {
            sender.sendMessage(err("Une opération sur le pack est déjà en cours."))
            return 0
        }
        sender.sendMessage(note("Validation du pack…"))
        future.whenComplete { r, error -> runFor(sender) { if (error != null) interrupted(sender, error) else packReport(sender, r) } }
        return Command.SINGLE_SUCCESS
    }

    private fun unavailable(sender: CommandSender): Int {
        sender.sendMessage(err("Compilateur de pack indisponible."))
        return 0
    }

    private fun interrupted(
        sender: CommandSender,
        error: Throwable,
    ) = sender.sendMessage(err("Opération interrompue (${error.javaClass.simpleName})."))

    private fun packReport(
        sender: CommandSender,
        r: PackReport,
    ) {
        val pack = r.outcome.pack
        val counts = "${r.errorCount} erreur(s), ${r.warningCount} avertissement(s), ${r.durationMillis} ms"
        when (r.outcome.status) {
            PackStatus.BUILT, PackStatus.UP_TO_DATE -> {
                val head =
                    if (r.outcome.status ==
                        PackStatus.BUILT
                    ) {
                        "Pack construit : ${pack?.fileCount} fichier(s), ${pack?.size} octets"
                    } else {
                        "Pack à jour, rien à reconstruire"
                    }
                sender.sendMessage(ok("$head ; $counts."))
                val announced = packs?.published
                if (pack != null && announced?.sha1 != pack.sha1) {
                    sender.sendMessage(
                        note(
                            "Non annoncé aux joueurs : téléversez cache/pack/aaengine-pack.zip à l'URL du pack, puis /$ROOT pack publish.",
                        ),
                    )
                }
            }
            PackStatus.VALIDATED -> sender.sendMessage(ok("Pack valide : ${r.outcome.fileCount} fichier(s) ; $counts."))
            PackStatus.FAILED -> sender.sendMessage(err("Pack invalide : rien n'a été écrit, le pack précédent reste en place ; $counts."))
        }
        if (pack != null) {
            sender.sendMessage(Component.text(" SHA-1 ${pack.sha1}", NamedTextColor.GRAY))
            sender.sendMessage(Component.text(" SHA-256 ${pack.sha256}", NamedTextColor.GRAY))
        }
        val shown = r.diagnostics.take(MAX_DIAGNOSTICS_SHOWN)
        for (d in shown) {
            val color =
                when (d.severity) {
                    Severity.ERROR -> NamedTextColor.RED
                    Severity.WARN -> NamedTextColor.YELLOW
                    Severity.INFO -> NamedTextColor.GRAY
                }
            sender.sendMessage(Component.text(" - " + d.format(), color))
        }
        val hidden = r.errorCount + r.warningCount + r.infoCount - shown.size
        if (hidden > 0) sender.sendMessage(Component.text(" … $hidden autre(s), voir la console.", NamedTextColor.GRAY))
    }

    private fun reload(sender: CommandSender): Int {
        if (reloads.isReloading) {
            sender.sendMessage(note("Un rechargement est déjà en cours : la demande est fusionnée avec le suivant."))
        } else {
            sender.sendMessage(note("Rechargement du contenu…"))
        }
        reloads.reload().whenComplete { result, error ->
            // Le futur peut se terminer hors du thread serveur (échec, désactivation) : réponse replanifiée.
            runFor(sender) {
                if (error != null) {
                    sender.sendMessage(err("Rechargement interrompu (${error.javaClass.simpleName})."))
                } else {
                    report(sender, result)
                }
            }
        }
        return Command.SINGLE_SUCCESS
    }

    /** Exécute [action] sur l'ordonnanceur de [sender] (joueur : son entité ; sinon région globale). */
    private fun runFor(
        sender: CommandSender,
        action: () -> Unit,
    ) {
        if (!plugin.isEnabled) return
        try {
            if (sender is Player) {
                sender.scheduler.run(plugin, { _ -> action() }, null)
            } else {
                plugin.server.globalRegionScheduler.execute(plugin) { action() }
            }
        } catch (_: RuntimeException) {
            // Plugin désactivé entre-temps : plus personne à prévenir.
        }
    }

    private fun report(
        sender: CommandSender,
        result: ReloadResult,
    ) {
        val r = result.report
        if (r.success) {
            sender.sendMessage(
                ok(
                    "Contenu rechargé : révision ${r.revision}, ${engine.snapshot().items.size} item(s), " +
                        "${r.durationMillis} ms, ${r.warningCount} avertissement(s).",
                ),
            )
        } else {
            sender.sendMessage(
                err("Échec du rechargement : ${r.errorCount} erreur(s). Le contenu précédent (révision ${r.revision}) reste actif."),
            )
        }
        val shown = result.diagnostics.take(MAX_DIAGNOSTICS_SHOWN)
        for (d in shown) {
            val color = if (d.severity == Severity.ERROR) NamedTextColor.RED else NamedTextColor.YELLOW
            sender.sendMessage(Component.text(" - " + d.format(), color))
        }
        val hidden = r.errorCount + r.warningCount - shown.size
        if (hidden > 0) sender.sendMessage(Component.text(" … $hidden autre(s), voir la console.", NamedTextColor.GRAY))
    }

    private fun give(
        ctx: CommandContext<CommandSourceStack>,
        amount: Int,
    ): Int {
        val sender = ctx.source.sender
        val target: Player? =
            ctx
                .getArgument("player", PlayerSelectorArgumentResolver::class.java)
                .resolve(ctx.source)
                .firstOrNull()
        if (target == null) {
            sender.sendMessage(err("Joueur introuvable."))
            return 0
        }
        val id = resolveId(ctx)
        val entry = engine.snapshot().items[id]
        if (entry == null) {
            sender.sendMessage(err("Item inconnu : ${id.asString()}"))
            return 0
        }
        // Borne réelle : un inventaire plein (36 piles) de cet item, quelle que soit sa taille de pile.
        val maxAmount = INVENTORY_STACKS * entry.maxStackSize
        if (amount > maxAmount) {
            sender.sendMessage(
                err("Quantité trop grande pour ${id.asString()} : $maxAmount max ($INVENTORY_STACKS piles de ${entry.maxStackSize})."),
            )
            return 0
        }
        // L'inventaire du joueur ne se touche que depuis son ordonnanceur (Folia) ; tick suivant sous Paper.
        val scheduled = target.scheduler.run(plugin, { _ -> deliver(sender, target, id, amount) }, null)
        if (scheduled == null) {
            sender.sendMessage(err("Joueur indisponible."))
            return 0
        }
        return Command.SINGLE_SUCCESS
    }

    private fun deliver(
        sender: CommandSender,
        target: Player,
        id: Key,
        amount: Int,
    ) {
        var remaining = amount
        var given = 0
        var dropped = 0
        var droppedStacks = 0
        var lost = 0
        var stacks = 0
        // Au plus 36 piles créées (la taille de pile a pu changer depuis la commande, après un reload).
        while (remaining > 0 && stacks < INVENTORY_STACKS) {
            val stack = engine.items.create(id, remaining) ?: break
            stacks++
            remaining -= stack.amount
            given += stack.amount
            for (left in target.inventory.addItem(stack).values) {
                // Inventaire plein : piles au sol plafonnées, le reste n'est pas donné.
                if (droppedStacks >= MAX_DROPPED_STACKS) {
                    lost += left.amount
                    continue
                }
                droppedStacks++
                dropped += left.amount
                target.world.dropItem(target.location, left) { it.owner = target.uniqueId }
            }
        }
        if (stacks == 0) {
            sender.sendMessage(err("Item retiré entre-temps : ${id.asString()}"))
            return
        }
        given -= lost
        val notGiven = lost + remaining
        val suffix =
            buildString {
                if (dropped > 0) append(" ($dropped au sol, inventaire plein)")
                if (notGiven > 0) append(" ; $notGiven non donné(s)")
            }
        sender.sendMessage(ok("Donné $given × ${id.asString()} à ${target.name}$suffix."))
    }

    /**
     * Identifiant saisi. L'argument `key` de Brigadier complète un id sans namespace en `minecraft:` ; une
     * saisie sans `:` inconnue sous `minecraft` est donc relue dans le namespace par défaut des fichiers.
     */
    private fun resolveId(ctx: CommandContext<CommandSourceStack>): Key {
        val id = ctx.getArgument("id", Key::class.java)
        if (id.namespace() != Key.MINECRAFT_NAMESPACE || engine.items.exists(id)) return id
        val raw =
            ctx.nodes
                .firstOrNull { it.node.name == "id" }
                ?.range
                ?.get(ctx.input) ?: return id
        if (raw.contains(':')) return id
        return ItemIds.parseKey(id.value(), ItemIds.DEFAULT_NAMESPACE) ?: id
    }

    private fun listItems(
        sender: CommandSender,
        page: Int,
    ): Int {
        // Liste triée calculée une fois par instantané (pas de tri à chaque commande).
        val entries = engine.snapshot().sortedEntries
        if (entries.isEmpty()) {
            sender.sendMessage(note("Aucun item chargé."))
            return Command.SINGLE_SUCCESS
        }
        val pages = (entries.size + PAGE_SIZE - 1) / PAGE_SIZE
        val current = page.coerceIn(1, pages)
        sender.sendMessage(note("Items ${entries.size} — page $current/$pages"))
        val from = (current - 1) * PAGE_SIZE
        for (entry in entries.subList(from, minOf(from + PAGE_SIZE, entries.size))) {
            sender.sendMessage(
                Component
                    .text(" - ", NamedTextColor.GRAY)
                    .append(Component.text(entry.id.asString(), NamedTextColor.WHITE))
                    .append(Component.text(" (${entry.material.name})", NamedTextColor.GRAY)),
            )
        }
        if (current < pages) sender.sendMessage(Component.text(" /$ROOT items ${current + 1}", NamedTextColor.GRAY))
        return Command.SINGLE_SUCCESS
    }

    private fun info(sender: CommandSender): Int {
        sender.sendMessage(note("AAEngine ${engine.version} — API ${engine.apiVersion}"))
        sender.sendMessage(
            Component.text(
                " Révision ${engine.contentRevision}, ${engine.snapshot().items.size} item(s)" +
                    if (reloads.isReloading) ", rechargement en cours" else "",
                NamedTextColor.GRAY,
            ),
        )
        val last = engine.lastReload
        val line =
            if (last == null) {
                " Aucun chargement terminé."
            } else {
                " Dernier chargement : ${if (last.success) "réussi" else "échoué"} en ${last.durationMillis} ms, " +
                    "${last.errorCount} erreur(s), ${last.warningCount} avertissement(s)."
            }
        sender.sendMessage(Component.text(line, NamedTextColor.GRAY))
        return Command.SINGLE_SUCCESS
    }

    /**
     * Suggestions d'identifiants depuis l'index de l'instantané actif (immuable : sûr hors thread principal) :
     * recherche dichotomique du préfixe, sans tri ni conversion à chaque frappe.
     */
    private fun suggestIds(
        @Suppress("UNUSED_PARAMETER") ctx: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        engine
            .snapshot()
            .idIndex
            .suggest(builder.remainingLowerCase, MAX_SUGGESTIONS)
            .forEach(builder::suggest)
        return builder.buildFuture()
    }

    private fun ok(text: String) = prefix().append(Component.text(text, NamedTextColor.GREEN))

    private fun note(text: String) = prefix().append(Component.text(text, NamedTextColor.WHITE))

    private fun err(text: String) = prefix().append(Component.text(text, NamedTextColor.RED))

    private fun prefix() = Component.text("[AAEngine] ", NamedTextColor.DARK_GREEN)

    companion object {
        const val ROOT: String = "aaengine"
        const val ALIAS: String = "aae"

        /** Piles d'un inventaire de joueur (hors armure et main secondaire). */
        const val INVENTORY_STACKS: Int = 36

        /** Borne de saisie : 36 piles de 64 ; la borne réelle dépend de la taille de pile de l'item. */
        const val MAX_GIVE: Int = 64 * INVENTORY_STACKS

        /** Piles posées au sol au plus par commande quand l'inventaire est plein. */
        const val MAX_DROPPED_STACKS: Int = INVENTORY_STACKS

        private const val PAGE_SIZE = 10
        private const val MAX_SUGGESTIONS = 100
        private const val MAX_DIAGNOSTICS_SHOWN = 15
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
