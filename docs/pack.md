# Resource pack (phase 3)

AAEngine compile un resource pack pour **Minecraft 26.2** (format de pack **88.0**) à partir de vos
textures, de vos modèles et des fichiers d'items. Le build est **déterministe** (mêmes entrées = même zip,
même SHA-1) et **incrémental** (rien n'est reconstruit si rien n'a changé). Le compilateur tourne hors du
thread principal ; il ne touche jamais au monde ni aux joueurs.

## Arborescence

```text
plugins/AAEngine/
├─ config.yml                     section pack: (distribution)
├─ items/**/*.yml                 items + sets d'équipement (section equipment:)
├─ pack/
│  ├─ assets/<namespace>/...      vos fichiers, copiés tels quels dans le pack
│  └─ external/*.zip              packs tiers (lecture zip stricte)
└─ cache/pack/                    généré, ne pas éditer
   ├─ aaengine-pack.zip           le pack à publier
   └─ manifest.json               empreintes SHA-256 des entrées et sorties, SHA-1 du zip
```

`pack/assets/` et `pack/external/` sont créés au démarrage. Au premier démarrage, la texture de l'item
d'exemple (`pack/assets/aaengine/textures/item/forest_axe.png`) est fournie : `/aaengine pack build`
fonctionne immédiatement.

### Fichiers acceptés dans `pack/assets/`

- Extensions : `png`, `mcmeta`, `json`, `ogg`, `ttf`, `otf`, `bin`, `txt`. Toute autre extension (shaders
  `.fsh`/`.vsh`, exécutables…) est une **erreur** `PACK-E109`.
- Chemins en minuscules : `[a-z0-9_.-]` par segment (règle du client Minecraft). `Epee.png` est refusé.
- Liens symboliques, jonctions NTFS et fichiers spéciaux : refusés (`PACK-E109`). Fichiers cachés (`.xxx`),
  `Thumbs.db` et `desktop.ini` ignorés.
- Bornes : 20 000 fichiers, 16 Mio par fichier, 256 Mio au total, profondeur 24 ; PNG de 4096×4096 max.
- Chaque `.json` / `.mcmeta` doit être du JSON **strict** (sans commentaire ni virgule finale).

### Packs externes `pack/external/*.zip`

Lus en mémoire par un lecteur strict, rien n'est extrait sur disque. Une archive est **refusée en entier**
(`PACK-E110`) si : fin de répertoire central inexacte, ZIP64, chiffrement, multi-volumes, nom local
différent du nom central, entrées qui se chevauchent, données hors limites, inflate incohérent (taille ou
octets consommés), CRC32 ou data descriptor faux, `..`, chemin absolu, `\`, `:` (lecteur ou flux NTFS),
noms réservés Windows (`CON`, `NUL`, `COM1`…), segments finissant par `.` ou espace, doublons (casse ou
Unicode NFC), lien symbolique, ratio de compression > 200 par entrée, volume décompressé total > 50 × la
taille de l'archive (bombe répartie sur de nombreuses entrées), plus de 20 000 entrées, 64 Mio compressés,
16 Mio par entrée, 96 Mio décompressés.

Budget mémoire : une archive est lue entièrement en mémoire, puis ses entrées sont décompressées en
mémoire ; le pic par archive est donc d'environ 64 + 96 Mio, et l'ensemble des sources (archives et
`pack/assets`) reste plafonné à 256 Mio cumulés. Les octets lus ne sont jamais recopiés entre les étapes. `pack.mcmeta` et `pack.png` de l'archive sont ignorés ; les
entrées hors `assets/` ou d'extension non autorisée sont ignorées avec un avertissement `PACK-W201`.

Ordre de fusion : archives externes (ordre alphabétique), puis `pack/assets/`, puis fichiers générés.
Un même chemin présent deux fois avec **le même contenu** est accepté ; avec un **contenu différent**,
c'est une erreur `PACK-E108` qui nomme les deux sources (aucune priorité silencieuse). Exception : les
atlas (`assets/<ns>/atlases/*.json`) sont fusionnés (union des sources).

## Clés d'item liées au pack

Section `model` (un seul mode par item : `texture`, `model` **ou** `item_model`) :

| Clé | Valeur | Effet |
|---|---|---|
| `model.texture` | `<ns>:<chemin>` ou liste de calques (8 acceptés, 5 émis : `layer0` à `layer4`, avertissement `PACK-W203` au-delà) | génère `models/item/<id>.json` et `items/<id>.json`, pose `item_model` = id de l'item |
| `model.parent` | `<ns>:<chemin>` (avec `texture` uniquement) | parent du modèle généré ; défaut : `minecraft:item/handheld` pour épées, haches, pioches, pelles, houes, masse, bâton, tiges, trident ; `minecraft:item/generated` sinon |
| `model.model` | `<ns>:<chemin>` | modèle JSON fourni (`assets/<ns>/models/<chemin>.json`, Blockbench) ; génère `items/<id>.json`, pose `item_model` |
| `model.item_model` | `<ns>:<id>` | mode manuel : vous fournissez `assets/<ns>/items/<id>.json` (vérifié si le namespace n'est pas `minecraft`) |
| `model.custom_model_data` | entier ou table | inchangé (phase 2), cumulable |

Section `components` :

| Clé | Valeur | Effet |
|---|---|---|
| `components.tooltip_style` | `<ns>:<style>` | composant `minecraft:tooltip_style` ; sprites `assets/<ns>/textures/gui/sprites/tooltip/<style>_background.png` et `_frame.png` (+ `.png.mcmeta` `nine_slice` conseillé) |
| `components.equippable.slot` | `head` \| `chest` \| `legs` \| `feet` | composant `minecraft:equippable` |
| `components.equippable.asset` | `<ns>:<set>` | set d'équipement (`assets/<ns>/equipment/<set>.json`) |

Section racine `equipment:` d'un fichier d'items (sets d'équipement, partagés par plusieurs pièces) :

| Clé | Valeur | Texture attendue |
|---|---|---|
| `equipment.<set>.humanoid` | `<ns>:<chemin>` | `assets/<ns>/textures/entity/equipment/humanoid/<chemin>.png` (casque, plastron, bottes) |
| `equipment.<set>.humanoid_leggings` | `<ns>:<chemin>` | `assets/<ns>/textures/entity/equipment/humanoid_leggings/<chemin>.png` (jambières) |

Namespace par défaut : celui du fichier (`namespace:`) pour `texture`, `model`, `tooltip_style`,
`equippable.asset` et les couches d'équipement ; `minecraft` pour `parent` et `item_model`. Un set
d'équipement défini dans deux fichiers est une erreur nommant les deux.

## Exemples complets

### Épée 2D

```yaml
namespace: aaengine
items:
  ruby_sword:
    item:
      material: DIAMOND_SWORD
      name: "<red>Épée de rubis"
    model:
      texture: aaengine:item/ruby_sword     # pack/assets/aaengine/textures/item/ruby_sword.png
```

Fichiers générés :

```jsonc
// assets/aaengine/models/item/ruby_sword.json
{ "parent": "minecraft:item/handheld", "textures": { "layer0": "aaengine:item/ruby_sword" } }
// assets/aaengine/items/ruby_sword.json
{ "model": { "type": "minecraft:model", "model": "aaengine:item/ruby_sword" } }
```

### Modèle 3D (Blockbench)

Exportez le modèle « Java Block/Item » dans `pack/assets/aaengine/models/item/statue.json` et ses textures
dans `pack/assets/aaengine/textures/item/statue.png` (référencée `"aaengine:item/statue"` dans le JSON).

```yaml
items:
  statue:
    item: { material: PAPER, name: "<gold>Statue" }
    model:
      model: aaengine:item/statue
```

Seul `items/statue.json` est généré ; parent et textures du modèle sont vérifiés. Une texture hors de
`item/` et `block/` (ex. `aaengine:custom/statue`) est ajoutée automatiquement à
`assets/minecraft/atlases/items.json` **et** `assets/minecraft/atlases/blocks.json`.

### Armure 4 pièces

```yaml
namespace: aaengine
equipment:
  forest:
    humanoid: aaengine:forest              # textures/entity/equipment/humanoid/forest.png (64×32)
    humanoid_leggings: aaengine:forest     # textures/entity/equipment/humanoid_leggings/forest.png
items:
  forest_helmet:
    item: { material: NETHERITE_HELMET, name: "<green>Casque de la forêt" }
    model: { texture: item/forest_helmet }
    components: { equippable: { slot: head, asset: forest } }
  forest_chestplate:
    item: { material: NETHERITE_CHESTPLATE, name: "<green>Plastron de la forêt" }
    model: { texture: item/forest_chestplate }
    components: { equippable: { slot: chest, asset: forest } }
  forest_leggings:
    item: { material: NETHERITE_LEGGINGS, name: "<green>Jambières de la forêt" }
    model: { texture: item/forest_leggings }
    components: { equippable: { slot: legs, asset: forest } }
  forest_boots:
    item: { material: NETHERITE_BOOTS, name: "<green>Bottes de la forêt" }
    model: { texture: item/forest_boots }
    components: { equippable: { slot: feet, asset: forest } }
```

Le matériau garde ses attributs vanilla (points d'armure, durabilité) ; `equippable` remplace seulement le
rendu porté (son d'équipement du matériau conservé). Généré :

```jsonc
// assets/aaengine/equipment/forest.json
{ "layers": { "humanoid": [ { "texture": "aaengine:forest" } ],
              "humanoid_leggings": [ { "texture": "aaengine:forest" } ] } }
```

### Tooltip personnalisé

```yaml
items:
  relic:
    item: { material: PAPER, name: "<light_purple>Relique" }
    model: { texture: item/relic }
    components:
      tooltip_style: aaengine:relic
```

Fichiers à fournir :

```text
pack/assets/aaengine/textures/gui/sprites/tooltip/relic_background.png
pack/assets/aaengine/textures/gui/sprites/tooltip/relic_background.png.mcmeta
pack/assets/aaengine/textures/gui/sprites/tooltip/relic_frame.png
pack/assets/aaengine/textures/gui/sprites/tooltip/relic_frame.png.mcmeta
```

```json
{ "gui": { "scaling": { "type": "nine_slice", "width": 100, "height": 100, "border": 9 } } }
```

Sans `.mcmeta`, le build passe avec un avertissement `PACK-W202` (sprite étiré).

## Commandes

| Commande | Permission | Effet |
|---|---|---|
| `/aaengine pack build` | `aaengine.command.pack` | compile ; si rien n'a changé, « Pack à jour » sans réécriture |
| `/aaengine pack build force` | `aaengine.command.pack` | recompile en ignorant le cache |
| `/aaengine pack publish` | `aaengine.command.pack` | annonce le dernier build aux joueurs (à lancer après le téléversement du zip) |
| `/aaengine validate` | `aaengine.command.validate` | relit items et sources et vérifie toutes les références, sans rien écrire |

Permissions `op` par défaut, accordées par `aaengine.admin`. Une seule opération de pack à la fois, sur
un thread dédié (`AAEngine-Pack`, compatible Folia). Le rapport affiche ERROR / WARN / INFO (15 lignes),
le détail complet est en console. En cas d'erreur, **rien n'est installé** : le zip et le manifeste
précédents restent intacts (un pack trop volumineux est contrôlé dans le fichier temporaire, avant tout
remplacement). Un build ne change jamais le pack annoncé aux joueurs : le message de build rappelle de
téléverser le zip puis de lancer `/aaengine pack publish`.
Les erreurs d'items (fichiers `items/`) bloquent aussi le build.

## Cache incrémental

`cache/pack/manifest.json` enregistre l'empreinte SHA-256 de toutes les entrées (origine, chemin et contenu
de chaque source, entrées issues des items, version du compilateur, version des règles, bornes, format
88.0) et les SHA-256 des sorties. Si l'empreinte est identique **et** que le zip sur disque a toujours la
taille, le SHA-1 et le SHA-256 enregistrés (recalculés en une passe sur les octets réels, le manifeste n'est
jamais cru seul), le build est sauté. Un zip modifié ou supprimé est reconstruit à l'identique. Écritures
atomiques : fichier temporaire `CREATE_NEW`, `FileChannel.force`, contrôle, puis remplacement, jamais à
travers un lien. Les temporaires orphelins (`.<nom>.<uuid>.tmp`) sont supprimés au démarrage et à chaque build.
`cache/pack/published.json` contient le pack annoncé (`/aaengine pack publish`).

Le zip est déterministe : entrées triées, date fixe (2000-01-01), aucun dossier ni commentaire, deflate
niveau 9. Le `pack.mcmeta` est toujours généré :

```json
{ "pack": { "description": "AAEngine resource pack", "min_format": [88, 0], "max_format": [88, 0] } }
```

## Distribution

```yaml
pack:
  distribution: external                              # none (défaut) | external
  url: "https://cdn.example.com/aaengine-{sha1}.zip"  # {sha1} remplacé par le SHA-1 du pack
  required: false
  prompt: "Pack de textures du serveur"               # texte brut
  allow-private-hosts: false
```

- `none` : AAEngine n'envoie rien (vous utilisez `server.properties`, un proxy…).
- `external` : à chaque connexion, AAEngine envoie l'URL et le SHA-1 du pack **annoncé** via
  `ResourcePackRequest` (identifiant de pack stable, `replace: false`). Rien n'est envoyé tant qu'aucun pack
  n'a été publié.
- URL : HTTPS obligatoire ; identifiants, fragment, `localhost`, `.local`, adresses loopback, privées
  (RFC 1918), lien local, CGNAT (100.64/10), multicast, 0/8, 240/4, plages de documentation (192.0.2/24,
  198.51.100/24, 203.0.113/24, 2001:db8::/32), relais 6to4 192.88.99/24, Teredo (2001::/32), ULA IPv6 et
  formes IPv4 non canoniques refusés ; pour NAT64 (64:ff9b::/96) et 6to4 (2002::/16), l'IPv4 embarquée est
  réévaluée. Opt-in `allow-private-hosts` pour un réseau de test (avertissement au démarrage). Une URL
  refusée désactive la distribution.
- Au démarrage, l'hôte est résolu par DNS sur le thread du pack (jamais le thread principal) : une adresse
  privée ou locale en réponse donne seulement un avertissement. Le serveur ne télécharge jamais cette URL.
- **Procédure** : `/aaengine pack build` → téléverser `cache/pack/aaengine-pack.zip` à l'URL (avec `{sha1}`,
  une nouvelle URL par version) → `/aaengine pack publish` → les joueurs qui se connectent reçoivent le
  nouveau SHA-1. Ni un build ni un redémarrage ne changent le SHA-1 annoncé.

Pas de serveur HTTP intégré dans ce lot (voir Suites).

## Codes de diagnostic

| Code | Gravité | Signification |
|---|---|---|
| `PACK-E101` | ERROR | JSON ou `.mcmeta` illisible (ligne et colonne indiquées) |
| `PACK-E102` | ERROR | définition d'item vers un modèle absent, ou `item_model` manuel sans `items/<id>.json` |
| `PACK-E103` | ERROR | parent de modèle absent |
| `PACK-E104` | ERROR | texture absente (rattachée au YAML pour un modèle généré) |
| `PACK-E105` | ERROR | sprite de `tooltip_style` absent |
| `PACK-E106` | ERROR | set d'équipement absent (`equippable.asset`) |
| `PACK-E107` | ERROR | texture d'équipement absente |
| `PACK-E108` | ERROR | conflit de fusion : même chemin, contenus différents (les deux sources nommées) |
| `PACK-E109` | ERROR | fichier refusé dans `pack/assets` (extension, chemin, lien, fichier spécial) |
| `PACK-E110` | ERROR | archive externe refusée par le lecteur strict |
| `PACK-E111` | ERROR | borne dépassée (nombre, taille, volume, pack > 250 Mio) |
| `PACK-E112` | ERROR | structure invalide (définition d'item, modèle, atlas, équipement) |
| `PACK-E113` | ERROR | PNG invalide ou trop grand |
| `PACK-E114` | ERROR | écriture du pack ou du manifeste impossible |
| `PACK-E115` | ERROR | cycle dans la chaîne de parents d'un modèle |
| `PACK-E116` | ERROR | variable de texture `#nom` utilisée (textures, faces des éléments) mais jamais définie dans la chaîne de parents d'un modèle rendu |
| `PACK-W201` | WARN | entrée d'archive ou fichier de `pack/external` ignoré |
| `PACK-W202` | WARN | sprite de `tooltip_style` sans `.mcmeta` |
| `PACK-W203` | WARN | plus de 5 calques : seuls `layer0` à `layer4` sont émis |
| `PACK-I301` | INFO | références `minecraft:` absentes du pack, supposées vanilla |
| `PACK-I302` | INFO | sprites ajoutés à l'atlas des items |
| `PACK-I303` | INFO | bilan du build (fichiers, taille, SHA-1) |

Exemple :

```text
ERROR PACK-E104 items/armes.yml [items.ruby_sword.model.texture] : Texture introuvable aaengine:item/ruby_sword (attendu : assets/aaengine/textures/item/ruby_sword.png) ; fichier généré : assets/aaengine/models/item/ruby_sword.json
```

## [A VÉRIFIER client 26.2]

Points isolés dans `PackFormat` (engine-pack), à confirmer sur un client 26.2 réel (journal sans modèle ni
texture manquants). Le format 88.0 est confirmé par `version.json` du jar serveur Paper 26.2
(`resource_major` 88, `resource_minor` 0). Aucun jar client 26.2 n'était disponible localement pour lire
ses assets JSON (atlas, `item/generated`) : les choix ci-dessous sont prudents.

- `pack.mcmeta` : forme uniforme `min_format: [88, 0]` / `max_format: [88, 0]` **sans** `pack_format`.
- Atlas : faute de savoir lequel des atlas `minecraft:items` et `minecraft:blocks` lit les modèles d'items,
  les sprites hors `item/` et `block/` sont ajoutés en `minecraft:single` aux **deux**.
- Calques de `item/generated` : limités à 5 (`layer0`-`layer4`) sans preuve d'une limite supérieure.
- Vérification des variables `#nom` faite seulement quand toute la chaîne de parents est dans le pack.
- Taille maximale d'un pack serveur acceptée par le client : 250 Mio.
- Sprites de `tooltip_style` : `gui/sprites/tooltip/<style>_background` et `_frame`, `nine_slice` attendu.
- Équipement : couches `humanoid` / `humanoid_leggings` et dossiers `textures/entity/equipment/<couche>/`.
- Parent `item/handheld` choisi aussi pour `MACE`, `TRIDENT`, `STICK`, `BLAZE_ROD`, `BREEZE_ROD`.
- Le client tolère des JSON non stricts (commentaires) ; AAEngine les refuse volontairement (`PACK-E101`).

## Suites (hors de ce lot)

- Serveur HTTP intégré (§19) et publication S3 / CDN (`PackPublisher`).
- Renvoi du pack aux joueurs déjà connectés après un `pack publish`.
- Polices, glyphes, sons (`sounds.json`), langues fusionnées, overlays multi-versions, obfuscation.
- Décodage complet des PNG (chunks, IDAT) au-delà de l'en-tête.

<!-- Developped by AzurHOSTS.com and powered by AzurWare.fr. -->
