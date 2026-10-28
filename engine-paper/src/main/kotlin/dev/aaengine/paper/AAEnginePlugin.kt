package dev.aaengine.paper

import dev.aaengine.api.AAEngineProvider
import dev.aaengine.api.event.AAEngineContentUnloadingEvent
import dev.aaengine.core.config.EngineConfig
import dev.aaengine.core.diagnostic.DiagnosticCollector
import dev.aaengine.core.diagnostic.Severity
import dev.aaengine.core.engine.DefaultAAEngine
import dev.aaengine.core.log.EngineLog
import dev.aaengine.core.log.LogCategory
import dev.aaengine.core.reload.ReloadService
import dev.aaengine.items.ItemCatalog
import dev.aaengine.items.ItemCatalogLoader
import dev.aaengine.items.ItemFileParser
import dev.aaengine.pack.PackLayout
import dev.aaengine.paper.command.AAEngineCommands
import dev.aaengine.paper.items.ItemsContentCompiler
import dev.aaengine.paper.items.PaperMaterials
import dev.aaengine.paper.pack.PackDistributor
import dev.aaengine.paper.pack.PackService
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.handler.LifecycleEventHandler
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.plugin.java.JavaPlugin
import java.io.IOException

/**
 * Entrée Paper. À l'activation : installe l'API, prépare `plugins/AAEngine/` (fichiers par défaut), lit
 * `config.yml`, enregistre les commandes puis lance le chargement initial du contenu (transactionnel,
 * asynchrone). Les consommateurs attendent `AAEngineContentLoadedEvent` ou `isContentLoaded`.
 *
 * Classe `open` : MockBukkit charge le plugin via une sous-classe générée (tests d'activation).
 */
open class AAEnginePlugin : JavaPlugin() {
    private var engine: DefaultAAEngine? = null
    private var reloads: ReloadService<ItemCatalog>? = null
    private var executors: PaperExecutors? = null

    override fun onEnable() {
        val log = EngineLog(logger)
        val e = DefaultAAEngine(pluginMeta.version)
        AAEngineProvider.install(e)
        engine = e
        try {
            val folder = DataFolder(dataFolder.toPath())
            prepareDataFolder(folder, log)
            val config = loadConfig(folder, log)
            log.debugCategories = config.debugCategories

            val loader = ItemCatalogLoader(ItemFileParser(PaperMaterials.catalog()))
            val exec = PaperExecutors(this).also { executors = it }
            val service =
                ReloadService(
                    engine = e,
                    compiler = ItemsContentCompiler({ folder.itemsDir }, loader),
                    asyncExecutor = exec.async,
                    mainExecutor = exec.main,
                    listener = ContentReloadListener(e, server.pluginManager, log),
                    logger = logger,
                )
            reloads = service

            val packs = PackService(PackLayout(folder.root), { folder.itemsDir }, loader, exec.pack, logger)
            val distributor = PackDistributor(config.pack, { packs.published }, log)
            if (distributor.enabled) {
                server.pluginManager.registerEvents(distributor, this)
                distributor.checkDns(exec.pack)
            }
            // Au démarrage : rien n'est recompilé ; le SHA-1 annoncé est celui du dernier /aaengine pack publish.
            packs.loadCurrent().whenComplete { pack, error ->
                when {
                    error != null -> log.error(LogCategory.PACK, "Lecture du pack en cache impossible (${error.javaClass.simpleName})")
                    pack != null -> log.info(LogCategory.PACK, "Dernier build : ${pack.fileCount} fichier(s), SHA-1 ${pack.sha1}")
                }
                val announced = packs.published
                if (announced != null) {
                    log.info(LogCategory.PACK, "Pack annoncé aux joueurs : SHA-1 ${announced.sha1}")
                } else if (distributor.enabled) {
                    log.warn(LogCategory.PACK, "Aucun pack publié : /aaengine pack build, téléversez le zip, puis /aaengine pack publish.")
                }
            }

            val commands = AAEngineCommands(this, e, service, packs)
            lifecycleManager.registerEventHandler(
                LifecycleEvents.COMMANDS,
                LifecycleEventHandler { event: ReloadableRegistrarEvent<Commands> -> commands.register(event.registrar()) },
            )

            log.info(LogCategory.BOOT, "AAEngine ${pluginMeta.version} enabled (API ${e.apiVersion}); loading content…")
            service.reload()
        } catch (ex: RuntimeException) {
            // Échec de câblage : l'API ne doit pas rester installée dans un état partiel.
            shutdown()
            throw ex
        }
    }

    override fun onDisable() {
        if (engine == null) return
        shutdown()
        logger.info("AAEngine disabled.")
    }

    private fun shutdown() {
        reloads?.close()
        reloads = null
        executors?.close()
        executors = null
        val e = engine ?: return
        engine = null
        server.pluginManager.callEvent(AAEngineContentUnloadingEvent())
        AAEngineProvider.uninstall(e)
    }

    private fun prepareDataFolder(
        folder: DataFolder,
        log: EngineLog,
    ) {
        try {
            val defaults = folder.ensureDefaults(::getResource)
            val firstStart = "${DataFolder.ITEMS}/${DataFolder.EXAMPLE}" in defaults
            val created = defaults + folder.ensurePackDirs(::getResource, withExample = firstStart)
            if (created.isNotEmpty()) log.info(LogCategory.BOOT, "Fichiers par défaut créés : ${created.joinToString(", ")}")
            if ("${DataFolder.ITEMS}/" in created && "${DataFolder.ITEMS}/${DataFolder.EXAMPLE}" !in created) {
                log.warn(
                    LogCategory.BOOT,
                    "Dossier ${DataFolder.ITEMS}/ absent : recréé vide (l'exemple n'est fourni qu'au premier démarrage).",
                )
            }
        } catch (ex: IOException) {
            log.error(LogCategory.BOOT, "Préparation de ${folder.root} impossible (${ex.javaClass.simpleName})", ex)
        } catch (ex: SecurityException) {
            log.error(LogCategory.SECURITY, "Préparation du dossier refusée : ${ex.message}")
        }
    }

    private fun loadConfig(
        folder: DataFolder,
        log: EngineLog,
    ): EngineConfig {
        val diagnostics = DiagnosticCollector()
        val config =
            try {
                folder.readConfig()?.let { EngineConfig.parse(it, DataFolder.CONFIG, diagnostics) } ?: EngineConfig.DEFAULT
            } catch (ex: IOException) {
                log.error(
                    LogCategory.CONFIG,
                    "Lecture de ${DataFolder.CONFIG} impossible (${ex.javaClass.simpleName}) : valeurs par défaut.",
                )
                EngineConfig.DEFAULT
            } catch (ex: SecurityException) {
                log.error(LogCategory.SECURITY, "Lecture de ${DataFolder.CONFIG} refusée : ${ex.message}")
                EngineConfig.DEFAULT
            }
        for (d in diagnostics.toList()) {
            if (d.severity == Severity.ERROR) log.error(LogCategory.CONFIG, d.format()) else log.warn(LogCategory.CONFIG, d.format())
        }
        return config
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
