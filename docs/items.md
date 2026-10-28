# Items (phase 2)

## Dossier de données

```text
plugins/AAEngine/
├─ config.yml        créé s'il est absent, jamais écrasé
└─ items/            créé au premier démarrage avec items/exemple.yml
   └─ **/*.yml       lus récursivement (512 fichiers max, 1 Mio max chacun)
```

Liens symboliques, fichiers spéciaux et chemins hors de `items/` sont refusés (erreur bloquante).
Fichiers et dossiers cachés (`.xxx`) ignorés.

## Format

```yaml
namespace: aaengine          # optionnel, "aaengine" par défaut
items:
  forest_axe:                # identifiant complet : aaengine:forest_axe
    item:
      material: NETHERITE_AXE          # obligatoire
      name: "<green>Forest Axe"        # MiniMessage (mise en forme uniquement)
      lore: ["<gray>Ligne"]            # 64 lignes max
    model:
      item_model: aaengine:forest_axe  # composant item_model
      custom_model_data: 1001          # ou { floats: [], flags: [], strings: [], colors: ["#RRGGBB"] }
    components:
      unbreakable: true
      max_stack_size: 16               # 1..99, interdit (> 1) sur un item à durabilité
      glint: true                      # enchantment_glint_override
      hide_tooltip: false              # tooltip_display.hide_tooltip
```

Identifiants : namespace `[a-z0-9_.-]{1,64}`, valeur `[a-z0-9_./-]{1,128}` (segments `.`, `..` et vides refusés).
Toute clé inconnue est une erreur, avec la clé la plus proche en suggestion. Un identifiant défini
dans deux fichiers est une erreur qui nomme les deux fichiers.

## Rechargement

Transactionnel : lecture et validation asynchrones, construction des modèles `ItemStack` sur le thread
principal (région globale sous Folia), puis publication atomique et `AAEngineContentLoadedEvent`.
À la moindre erreur, rien n'est publié et le contenu précédent reste actif. Si le premier chargement
échoue, un contenu vide est publié (les consommateurs passent prêts avec 0 item).

Les diagnostics indiquent la gravité, le fichier, le chemin YAML (`items.forest_axe.item.material`),
le message et une suggestion ; la ligne n'est indiquée que lorsque le parseur YAML la fournit.

## Commandes

| Commande | Permission |
|---|---|
| `/aaengine reload` (`/aae`) | `aaengine.command.reload` |
| `/aaengine give <joueur> <id> [quantité]` (1..2304) | `aaengine.command.give` |
| `/aaengine items [page]` | `aaengine.command.items` |
| `/aaengine info` | `aaengine.command.info` |

Toutes par défaut `op` ; `aaengine.admin` les accorde toutes.

<!-- Developped by AzurHOSTS.com and powered by AzurWare.fr. -->
