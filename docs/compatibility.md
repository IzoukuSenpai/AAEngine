# Compatibilité avec les plugins tiers ciblant Nexo

Beaucoup de plugins (MythicMobs, MMOItems, ModelEngine, TAB, PlaceholderAPI expansions, Lands, etc.)
embarquent un hook « Nexo » : ils détectent le plugin par son nom et appellent une poignée
de méthodes statiques publiques (`NexoItems.itemFromId`, `NexoItems.idFromItem`,
`NexoBlocks.isCustomBlock`, `NexoFurniture.isFurniture`, events `NexoItemsLoadedEvent`, ...).

AAEngine doit pouvoir **prendre la place de Nexo** pour ces plugins sans qu'ils soient modifiés.

## Principe : façade d'API, pas réimplémentation

Un module dédié `engine-compat-nexo` expose la *surface publique* observée (noms de packages,
classes, signatures) et délègue chaque appel à l'API AAEngine (`AAEngine.items`, `.blocks`,
`.furniture`). Il ne contient aucune logique métier ni aucun code copié.

```text
plugin tiers ──> com.nexomc.nexo.api.NexoItems.itemFromId("forest_axe")
                        │  (façade, engine-compat-nexo)
                        ▼
                 AAEngineProvider.get().items.create(Key.key("aaengine:forest_axe"))
```

Règles :

- la façade est **opt-in** (`compat.nexo.enabled: true`), désactivée par défaut ;
- le module est chargé uniquement si aucun vrai Nexo n'est présent sur le serveur ;
- `paper-plugin.yml` déclare `provides: [Nexo]` quand la façade est activée, pour que les
  `softdepend`/`depend` des plugins tiers soient satisfaits ;
- les IDs sans namespace reçus par la façade sont résolus dans le namespace `aaengine` ;
- les events Nexo attendus par les tiers sont re-émis depuis les events AAEngine correspondants ;
- chaque méthode de façade non supportée lève une erreur claire, jamais un `null` silencieux.

## Clean-room

Reproduire une surface d'API publique (noms et signatures) pour l'interopérabilité est
distinct de copier l'implémentation. Le code de la façade est écrit à partir de la
documentation publique et des appels observés dans les plugins tiers open source, jamais
depuis le binaire décompilé. Aucune ressource, texte ou asset du produit observé n'est inclus.

## Périmètre initial

| Surface | Priorité | Dépend de |
|---|---|---|
| `NexoItems` (exists, itemFromId, idFromItem, builderFromItem minimal) | haute | Phase 2 Items |
| `NexoBlocks` (isCustomBlock, place, remove, customBlockMechanic) | moyenne | Phase 4 Blocks |
| `NexoFurniture` (isFurniture, place, remove, furnitureMechanic) | moyenne | Phase 5 Furniture |
| events (items loaded, furniture/block place/break) | moyenne | Phase 2 à 5 |
| `NexoPack` | basse | Phase 3 Pack |

La même approche pourra servir pour une façade Oraxen (`io.th0rgal.oraxen.api.OraxenItems`).

## Voir aussi

Pour remplacer ItemsAdder (et pas seulement offrir une façade d'API), voir
[`itemsadder-compat.md`](itemsadder-compat.md) : réutilisation des états vanilla déjà alloués,
couche legacy du pack, verrou des `note_block`, glyphes, furniture et façade optionnelle
`engine-compat-itemsadder` construite sur le même modèle que la façade Nexo ci-dessus. Le runbook
appliqué au serveur DinoRealms est un mode opératoire privé, conservé hors dépôt (données d'exploitation).
