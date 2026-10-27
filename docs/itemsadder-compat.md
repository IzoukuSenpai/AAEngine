# Compatibilité ItemsAdder

> Document de conception AAEngine (AzurHOSTS). Statut : **proposition**, rien n'est implémenté.
> AAEngine est en phase 1 : il ne charge encore ni item, ni bloc, ni pack, et n'a ni listeners,
> ni commandes, ni configuration (`AAEnginePlugin.onEnable` publie un snapshot vide ;
> `engine-pack` ne contient que `PackModule.ID`). Ce document décrit comment AAEngine pourra
> **remplacer ItemsAdder sur un serveur existant** sans perdre le monde. Le runbook appliqué au
> serveur DinoRealms est dans un mode opératoire privé, conservé hors dépôt (données d'exploitation).
>
> **Publication** : le dépôt `IzoukuSenpai/AAEngine` est public. Ce document ne décrit pas la
> protection du pack ItemsAdder ni de méthode pour la lever (section 3). Toute publication d'un
> outil d'import de pack est soumise à validation d'AzurHOSTS (Hugo CORBIN), voir section 8.

## 0. Sources et conventions

Toute affirmation sur un format est rattachée à une source. Ce qui n'a pas été observé dans les
données ni lu dans une documentation publique est marqué **[A VÉRIFIER]**.

| Repère | Source | Nature |
|---|---|---|
| **[P]** | `ia-data/generated.zip` | pack de ressources compilé par ItemsAdder sur le serveur live (donnée de l'utilisateur) |
| **[C]** | `ia-data/font_images_unicode_cache.yml` | cache d'allocation des codepoints des font images (donnée de l'utilisateur, complet) |
| **[D]** | `ia-data/resource-pack-diagnostics.txt` | diagnostics de compilation du pack (donnée de l'utilisateur) |
| **[G]** | `ia-data/ia_gui.yml` | configuration du menu ItemsAdder (donnée de l'utilisateur) |
| **[A]** | `ia-analysis/*.json` | tables d'analyse en lecture seule, produites à partir de [P] et [C] |
| **[W]** | <https://wiki.itemsadder.com/> | documentation publique ItemsAdder. L'adresse désignée <https://itemsadder.devs.beer/> redirige (HTTP 301) vers ce wiki (vérifié le 2026-10-27). Les pages citées sont à relire et dater individuellement **[A VÉRIFIER : date de consultation par page]** |
| **[J]** | <https://lonedev6.github.io/API-ItemsAdder/> | javadoc publique de l'API ItemsAdder (signatures seulement) |
| **[MC]** | <https://minecraft.wiki/> | données vanilla (états de blocs, format du pack, format Anvil) |
| **[PM]** | <https://docs.papermc.io/paper/reference/global-configuration> | documentation Paper (`config/paper-global.yml`), consultée le 2026-10-27 |
| *tertiaire* | tickets <https://github.com/PluginBugs/Issues-ItemsAdder/issues> | rapports d'utilisateurs, non normatifs, toujours **[A VÉRIFIER]** |

Le rapport d'architecture de l'utilisateur (`ia-data/ItemsAdder_4.0.18_REA_Report.md`) n'est
**pas** une source de ce document : il est issu d'une analyse du bytecode et ne doit pas entrer
dans le dossier clean-room (section 8). Aucun constat ci-dessous n'en dépend.

Les fichiers `ia-data/`, `ia-analysis/`, `ia-scripts/` sont des données utilisateur conservées
**hors du dépôt** (le pack contient des assets achetés). Ils sont exclus par `.gitignore`, ainsi
que `migration-out/`, `**/converted/`, `report.json`, `world-scan.json`, `*.zip`, `rollback/` et
`backups/`. Les sorties `--out` du migrateur doivent être **hors du dépôt** (section 5.5).

---

## 1. Objectif

Remplacer ItemsAdder (et Nexo s'il est présent) par AAEngine sur un serveur **déjà en
production**, sans :

- modifier les blocs du monde (le spawn est construit avec des blocs custom ItemsAdder) ;
- faire perdre aux joueurs l'apparence des items qu'ils détiennent ;
- casser les furniture déjà posées ;
- casser les emojis et font images déjà présents dans le chat, les menus et les configs
  d'autres plugins.

La migration doit être **réversible** : tant que la fenêtre de retour arrière est ouverte,
réinstaller ItemsAdder doit suffire à revenir à l'état d'avant (section 4.10).

## 2. Principe central : réutiliser à l'identique les états vanilla déjà alloués

Un bloc custom ItemsAdder « réel » est, dans le monde, **un bloc vanilla dans un état précis**.
Le type de placement détermine le bloc support [W] (<https://wiki.itemsadder.com/adding-content/blocks>) :

| Type IA | Bloc vanilla support | Limite documentée |
|---|---|---|
| `REAL_NOTE` (défaut) | `note_block` (instrument × note × powered) | 750 |
| `REAL` | `brown_mushroom_block`, `red_mushroom_block`, `mushroom_stem` (6 faces booléennes) | 191 |
| `REAL_TRANSPARENT` | `chorus_plant` (6 faces booléennes) | 63 |
| `REAL_WIRE` | `tripwire` (attached, disarmed, powered, north/east/south/west) | 127 |
| `FIRE` | `fire` | 14 |
| `TILE` | « modified spawner with a custom skin » ; **pas un état de bloc** | aucune |
| `ENTITY` (4.0.18+, MC 1.21.4+) | bloc de repli vanilla + display ; « ne consomme pas de slot de blockstate » | — |

Propriétés vanilla des supports : [MC] <https://minecraft.wiki/w/Note_Block>,
<https://minecraft.wiki/w/Tripwire> ; noms des propriétés champignon et chorus confirmés par les
clés de variantes de [P] `assets/minecraft/blockstates/{brown_mushroom_block,red_mushroom_block,mushroom_stem,chorus_plant}.json`.

Le rendu d'un tel bloc ne dépend **que du resource pack** : le fichier
`assets/minecraft/blockstates/note_block.json` associe chaque état à un modèle. Le principe
AAEngine est donc :

1. **Importer** la table « état vanilla → modèle » (et, quand elle est disponible, « état → id
   ItemsAdder ») depuis les données de l'utilisateur.
2. **Réserver** ces états dans l'allocateur de blocs AAEngine : ils ne seront jamais réalloués à
   un autre bloc, même après un reload ou l'ajout de contenu. AAEngine ne reproduit aucun
   algorithme d'allocation d'IA : il alloue ses propres états uniquement hors de l'ensemble
   réservé.
3. **Émettre** dans le pack AAEngine les mêmes overrides de blockstates, avec les mêmes modèles
   et les mêmes textures.
4. **Reconnaître** ces états comme des blocs AAEngine (`AABlockService.idOf(block)`), y compris
   quand l'id d'origine est inconnu (bloc « orphelin » : rendu conservé, comportement minimal).
5. **Verrouiller** tout ce qui modifierait ces états : `block-updates.disable-noteblock-updates`
   de Paper, plus des gardes événementielles (clic, casse, explosion, piston) ; le verrou est
   **fail-closed** (section 4.1.3).

Résultat, limité à ce qui est observé : **zéro édition du monde pour `REAL_NOTE`**, sous réserve
que le verrou soit actif (option Paper et gardes). Pour les autres types :

- `REAL`, `REAL_TRANSPARENT`, `REAL_WIRE` : **non observés sur DinoRealms** ; leur stabilité sans
  IA dépend des options `block-updates.disable-mushroom-block-updates`,
  `disable-chorus-plant-updates` et `disable-tripwire-updates` [PM] **[A VÉRIFIER]** ;
- `FIRE` : non observé ; **pas stable** sans comportement serveur (le feu vieillit, s'éteint et se
  propage) **[A VÉRIFIER]** ;
- `TILE` et `ENTITY` : non encodés dans un état de bloc, adoption spécifique (section 4.1.4).

> Cas DinoRealms : [P] ne contient que des blocs `REAL_NOTE` (177 états de `note_block`), aucun
> état custom sur champignons, chorus, tripwire ou feu (section 4.1.1).

---

## 3. Sources des assets et lecture sûre des archives

### 3.1 Constat et politique

Le pack compilé du live ([P]) applique une **protection anti-extraction** : ses en-têtes et
métadonnées sont volontairement incohérents, et un lecteur zip standard le refuse. Le fichier
n'est pas tronqué. Le détail des mesures observées est conservé **hors dépôt** ([A], notes
privées), il n'est pas décrit ici.

Politique AAEngine :

- AAEngine **ne contourne pas** cette protection pour obtenir des textures ou des modèles.
- Sources primaires des assets, non protégées :
  1. `plugins/ItemsAdder/contents/**/resourcepack` de la sauvegarde complète (sources propres de
     l'utilisateur) ;
  2. sur le serveur de **répétition** uniquement, avec le jar IA sous licence de l'utilisateur :
     désactiver l'option de protection du pack dans `plugins/ItemsAdder/config.yml` (nom exact de
     l'option selon [W] **[A VÉRIFIER]**), régénérer le pack (`/iazip`), puis comparer
     `note_block.json`, `items/paper.json` et `font/default.json` à ceux du pack live : ils
     doivent être identiques (sinon `ERROR`).
- Depuis le pack protégé, seul le **CLI** peut lire les **JSON de correspondance** (blockstates,
  `items/*.json`, polices), pour vérifier les états, CMD et codepoints. Il refuse d'en extraire
  des PNG. Cette lecture exige le flag explicite `--trust-central-directory` (section 5.3), et le
  flag n'est livré qu'après validation juridique d'AzurHOSTS (section 8).
- Le plugin n'a **aucune** option de lecture assouplie.

### 3.2 Lecture zip stricte (WP0 `SafeZipReader`)

Contrôles, tous actifs en permanence :

- noms pris dans le répertoire central ; un nom local différent du nom central est **refusé**
  (le CLI peut seulement relâcher cette comparaison avec `--trust-central-directory`, tous les
  autres contrôles restant actifs ; le nombre d'entrées concernées est compté dans
  `report.json`) ;
- bornes : `offset + 30 + len(nom_local) + len(extra_local) + compress_size ≤ taille de
  l'archive`, sinon refus ;
- intervalles de données des entrées triés et **disjoints** (refus des entrées qui se chevauchent,
  bombe zip par chevauchement) ;
- inflate : doit consommer **exactement** `compress_size` octets et produire au plus le plafond
  (par entrée, cumulé, ratio) ; la taille déclarée n'est jamais crue ;
- CRC32 et taille calculés = valeurs du data descriptor **et** du répertoire central, sinon
  `ERROR` (hors `--trust-central-directory`, où seul le data descriptor fait foi) ;
- noms : refus de `..`, d'un `/` ou `\` initial, de `\`, d'une lettre de lecteur, de NUL et des
  caractères de contrôle, de `:` à **toute** position (flux de données alternatifs NTFS), des noms
  réservés Windows (`CON`, `PRN`, `AUX`, `NUL`, `COM1`-`COM9`, `LPT1`-`LPT9`, avec ou sans
  extension) à chaque segment, des segments finissant par `.` ou une espace ;
- doublons détectés sur la forme **NFC + casse repliée** (collisions Windows/macOS), refus ;
- refus des entrées symlink ;
- extraction (si nécessaire) uniquement via `SafePaths` (ci-dessous) ; rien n'est jamais exécuté.

`SafePaths` complète `AAENGINE_RECONSTRUCTION.md` §18.3 (`resolve().normalize()` +
`startsWith(root)`), qui ne couvre ni les liens créés entre vérification et écriture (TOCTOU) ni
la casse sous Windows :

- racine résolue par `toRealPath()` au démarrage ;
- création des dossiers parents, puis vérification que `parent.toRealPath()` reste sous la
  racine réelle (comparaison insensible à la casse sous Windows) ;
- ouverture des fichiers en `CREATE_NEW` + `NOFOLLOW_LINKS` ;
- le dossier `--out` est créé vide par l'outil lui-même.

### 3.3 Images

Les PNG viennent des sources non protégées (section 3.1) et sont décodés par un décodeur
standard (ImageIO) ; un PNG corrompu est **refusé** (`ERROR`), jamais réparé. Avant décodage :
dimensions plafonnées (défaut 4096×4096), nombre de chunks plafonné, et taille inflatée des IDAT
plafonnée à `hauteur × (1 + largeur × octets par pixel)`. Réécriture d'un PNG propre sans chunks
privés. Aucun décodeur tolérant n'est implémenté dans le dépôt.

---

## 4. Domaines

Pour chaque domaine : format source (avec citations), stratégie AAEngine, données nécessaires,
risques.

### 4.1 Blocs

#### 4.1.1 Format source observé (DinoRealms)

- `assets/minecraft/blockstates/note_block.json` [P] : **178 variantes**.
  - 1 clé partielle `instrument=harp` → `block/original/note_block` (rendu vanilla, texture
    `ia:5` = `minecraft:block/note_block`).
  - 177 clés au format exact `instrument=<inst>,note=<n>,powered=false`.
  - Plages pleines : `basedrum`, `snare`, `hat`, `bass`, `flute`, `bell`, `guitar` en notes 0..24
    (7 × 25 = 175), plus `chime` notes 0..1. `powered=true` n'est jamais utilisé, `harp` non plus.
  - Les 177 états correspondent à **175 blocs distincts** : le bloc directionnel `0750baa1-…`
    occupe 3 états (`hat 19` sans rotation, `chime 0` avec x=90, `chime 1` avec x=90, y=90).
  - 13 états ont des variantes aléatoires (listes de modèles `<ns>:block/<uuid>_variant_N`,
    72 modèles au total) ; les autres pointent vers `<ns>:item/ia_auto/<uuid>` (parent
    `block/cube_all`).
  - Nombre de modèles distincts référencés : 234 ou 235 selon deux analyses [A]
    **[A VÉRIFIER : écart de comptage d'une unité]** (question ouverte, section 10).
- États **non** listés (tout `powered=true`, `chime` 2..24, `xylophone`, `iron_xylophone`,
  `cow_bell`, `didgeridoo`, `bit`, `banjo`, `pling`, têtes de mob…) : aucune variante ne les
  couvre, le client affiche donc un modèle manquant pour un `note_block` dans un tel état
  **[A VÉRIFIER en jeu]**.
- Champignons [P] : 62 variantes sur 64 pour chacun des 3 blocs, toutes vers `block/original/*`
  (neutralisation, aucun bloc custom) ; les 2 états absents sont « tout false » et « west=true
  seul ». `chorus_plant` : 1 variante (toutes faces true) vers l'original. Aucun `tripwire.json`
  ni `fire.json` : **0 bloc `REAL`, `REAL_TRANSPARENT`, `REAL_WIRE` ou `FIRE`**.
- Namespaces de modèles hachés [A] : `<uuid_dossier_contents>-<uuid_sous_namespace>`. Le premier
  UUID `7a48140e-1bd0-4ec3-b886-f27cc3908727` est celui du dossier
  `contents/vyse_vanilla_pack_7a48140e-…` cité par [D]. Sous-namespace `…-f06dbecf-…` : blocs
  (175 `models/item/ia_auto/<uuid>.json` + 72 `models/block/<uuid>_variant_N.json`).
- Textures et atlas : alias `ia:0` … `ia:615` (sans trou). Dans [P], la racine les résout par
  `assets/minecraft/atlases/blocks.json` (616 sources `single`). Pour les formats 26.1+, les
  overlays `ia_overlay_26_1_plus` / `ia_overlay_modern_atlas` répartissent les sprites entre
  **deux atlas disjoints** : `atlases/blocks.json` (`ia:0..295`, plus la source `directory`
  `entity/models` de ModelEngine) et `atlases/items.json` (`ia:296..615`) [A]
  (`atlas_alias.json`). Les modèles de blocs se résolvent dans le premier, les modèles d'items
  dans le second ; par exemple `ia:559` n'est déclaré que dans
  `ia_overlay_26_1_plus/assets/minecraft/atlases/items.json`. Les textures de blocs sont
  `vyse_textures:item/<uuid>.png` (294 fichiers, aucun nom lisible, 1 `.mcmeta` animé).
- **Aucun nom humain n'est récupérable depuis le pack** pour ces 175 blocs. Hypothèse : la feuille
  `ia_auto/<uuid>` serait l'id IA **[A VÉRIFIER]** ; elle est confirmée pour `_iainternal`
  (les 7 modèles `_iainternal:item/ia_auto/icon_*` sont exactement les icônes citées par [G]).

**Blockstates vanilla retexturés** (pas des blocs custom IA). [P] contient **25** fichiers sous
`assets/minecraft/blockstates/` : `note_block`, les 3 champignons, `chorus_plant`, et **20**
fichiers retexturés venant du pack tiers « Vyse » fusionné (chemins
`contents/vyse_vanilla_pack_…/resourcepack/assets/minecraft/models/block/<uuid>.json` dans [D]) :

- 4 trapdoors : `waxed_copper_trapdoor`, `waxed_exposed_copper_trapdoor`,
  `waxed_oxidized_copper_trapdoor`, `waxed_weathered_copper_trapdoor` ;
- 3 escaliers : `waxed_exposed_cut_copper_stairs`, `waxed_oxidized_cut_copper_stairs`,
  `waxed_weathered_cut_copper_stairs` ;
- 1 dalle : `waxed_oxidized_cut_copper_slab` ;
- `waxed_oxidized_copper_bars` (multipart), `waxed_oxidized_copper_chain` (variantes vides) ;
- 2 bougies : `cyan_candle`, `lime_candle` ;
- 7 coraux morts : `dead_brain_coral_fan`, `dead_bubble_coral_fan`, `dead_fire_coral_fan`,
  `dead_horn_coral`, `dead_horn_coral_fan`, `dead_tube_coral`, `dead_tube_coral_fan` ;
- `flowering_azalea_leaves`.

Couverture partielle de certaines combinaisons (ex. `waxed_exposed_cut_copper_stairs` 5/40). 22
usages de `minecraft:item/iamissing` correspondent aux 5 textures déjà manquantes en production
(`arche_2x1`, `arche_2x2`, `texture/devanture_2x3`, `texture/sprucedoortexture`,
`toit_bordure_top_cote`) [D].

#### 4.1.2 Persistance côté ItemsAdder (documentation publique)

- `plugins/ItemsAdder/storage/real_blocks_ids_cache.yml` contiendrait des **id internes
  entiers**, pas des états de bloc (sources tertiaires **[A VÉRIFIER]** : le ticket
  <https://github.com/PluginBugs/Issues-ItemsAdder/issues/4167> demande d'y écrire les états ;
  <https://github.com/PluginBugs/Issues-ItemsAdder/issues/873> montre des id REAL jusqu'à 188).
  `/iacleancache` purge ces caches
  (<https://wiki.itemsadder.com/faq/i-removed-blocks-but-keep-saying-that-i-used-all-the-ids>).
  Autres noms de caches : `items_ids_cache.yml` (tickets #2621, #3446, tertiaire **[A VÉRIFIER]**),
  `real_blocks_note_ids_cache.yml` **[A VÉRIFIER : non sourcé]**.
  **Format exact et fonction id → (instrument, note, powered) : [A VÉRIFIER sur sauvegarde].**
  Dans la copie actuelle, `real_blocks_ids_cache.yml` et `real_wire_ids_cache.yml` font 0 octet.
- IA tient aussi des **fichiers de région custom** par monde : désactiver les blocs custom d'un
  monde « stop the plugin from generating custom region files »
  (<https://wiki.itemsadder.com/adding-content/blocks/enable-disable-custom-blocks-in-worlds>) ;
  l'API expose `CustomBlock.Advanced.getInCustomRegion(Location)`,
  `getAllBlocksLocations(Chunk)`, `placeInCustomRegion(...)`, `removeFromCustomRegion`
  [J] (<https://lonedev6.github.io/API-ItemsAdder/dev/lone/itemsadder/api/CustomBlock.Advanced.html>).
  **Chemin et format non publics : [A VÉRIFIER sur sauvegarde].** AAEngine ne les lit pas : pour
  les types réels, l'état vanilla suffit au rendu ; ces fichiers ne serviraient qu'à nommer les
  blocs (voir 4.1.3). La sauvegarde copie chaque dossier de monde **en entier** pour les inclure.

#### 4.1.3 Stratégie AAEngine

- **Couche legacy** (WP2b) : le blockstate `note_block.json` importé est réémis tel quel, avec
  ses modèles, ses variantes aléatoires et ses rotations ; les alias `ia:N` sont réécrits en
  `aaengine_legacy:N` dans **le bon atlas** (blocs ou items, section 4.1.1) ; les 20 blockstates
  Vyse sont fusionnés comme overrides vanilla tiers, conflits signalés.
- **Verrou d'états** (WP3a, `NoteBlockStateGuard`), pour tout `note_block` dont l'état fait
  partie des 177 états réservés :
  1. **Première ligne : option Paper** `block-updates.disable-noteblock-updates: true` dans
     `config/paper-global.yml` [PM] (« Whether to disable any form of block updates for note
     blocks on the server » ; les note blocks gardent leur état, même invalide). Elle couvre les
     mises à jour de voisin (recalcul d'instrument par le bloc **en dessous**, et depuis 1.20 par
     le bloc **au-dessus**, têtes de mob [MC]) et la redstone (`powered`). Clé exacte sur Paper
     26.2 **[A VÉRIFIER]**. Relever sa valeur actuelle dans la sauvegarde (IA peut déjà l'exiger)
     et la garder active après la bascule. Effet de bord accepté : les `note_block` vanilla des
     joueurs ne changent plus d'instrument ni d'état `powered` **[A VÉRIFIER : impact sur le jeu
     redstone]**.
  2. **Gardes événementielles**, limitées à ce que l'option ne couvre pas :
     - `PlayerInteractEvent` (accordage par clic droit) et `NotePlayEvent` : annulés ;
     - `BlockBreakEvent` : annulé sur les états réservés tant que WP4 n'est pas en place et
       pendant toute la fenêtre de retour arrière ; ensuite, casse autorisée avec drop de
       l'item-bloc legacy (PAPER + CMD lu dans `block_item_link.json`, 175/175) ;
     - `BlockExplodeEvent` et `EntityExplodeEvent` : retrait des blocs réservés de la liste ;
     - `BlockPistonExtendEvent` / `BlockPistonRetractEvent` : annulés si un bloc réservé serait
       déplacé ou détruit ;
     - `BlockPlaceEvent` : tout `note_block` posé est forcé en `instrument=harp`, pour qu'il
       n'affiche jamais une texture custom par hasard ;
     - `/setblock`, `/fill`, outils d'édition (WorldEdit…) : non interceptés ; procédure staff et
       permissions **[A VÉRIFIER : liste des outils sur DinoRealms]**.
  3. **Fail-closed** : si le verrou ne s'initialise pas (table des états absente ou illisible,
     option Paper désactivée alors que `migration.rollback-window` est actif, enregistrement des
     listeners en échec), AAEngine appelle `Bukkit.shutdown()` au lieu de continuer. Au succès,
     il journalise `NoteBlockStateGuard actif : 177 états` et l'expose dans `/aaengine status`
     (console). Comportement de `Bukkit.shutdown()` sous Folia **[A VÉRIFIER]**.
  4. Folia : handlers exécutés dans la région du bloc, aucun accès inter-régions.
  Ce verrou n'a besoin d'**aucun id**, seulement de l'ensemble des états réservés.
- **Allocateur** (WP3) : `plugins/AAEngine/data/block-state-map.json`, versionné, jamais
  réalloué au reload. Mode import : réserve exactement les états de la table legacy et refuse
  toute allocation qui écraserait un état présent dans le monde. Définition YAML d'un bloc avec
  `legacy_state: {instrument: flute, note: 20}`.
- **Nommage** des 175 blocs, par ordre de préférence :
  - A. `contents/**/configs/*.yml` + `storage/real_blocks_ids_cache.yml` (sauvegarde complète) ;
  - B. export par l'**API publique** sur le serveur de répétition (ci-dessous) : source
    principale dès que `contents/**` ou le cache d'ids est incomplet ;
  - C. chaîne état → UUID du modèle → CMD de l'item-bloc (déjà dans [P], 175/175, voir 4.2) →
    id IA lu sur un ItemStack réel de chaque item-bloc (`/paper dumpitem`) ;
  - D. à défaut : ids synthétiques stables `dinorealms:block_<uuid8>` et blocs « orphelins »
    (rendu conservé, propriétés par défaut).

  **Export par l'API publique (B)** : mini-plugin compilé **uniquement** contre l'artefact public
  `itemsadder-api` [J], exécuté sur le serveur de répétition isolé (sans réseau), avec le jar IA
  sous licence de l'utilisateur utilisé en boîte noire. Il parcourt
  `CustomBlock.getNamespacedIdsInRegistry()` et, pour chaque id, lit
  `CustomBlock.getBaseBlockData(String)` (variante statique) ou `getBaseBlockData()` sur
  l'instance (<https://lonedev6.github.io/API-ItemsAdder/dev/lone/itemsadder/api/CustomBlock.html>,
  signatures seulement), puis écrit un CSV au schéma de la section 5.2. Le plugin public
  IAxPresenceFootsteps (<https://github.com/Andre601/IAxPresenceFootsteps>, README) illustre cet
  usage ; son code n'est pas lu. Licence du jar IA pour un usage sur serveur de test
  **[A VÉRIFIER]** ; validation AzurHOSTS requise (section 8).

#### 4.1.4 Types non encodés dans un état : `TILE` et `ENTITY`

Ils ne laissent **aucune trace** dans les blockstates du pack ; leur présence sur DinoRealms est
inconnue.

- `TILE` : « modified spawner with a custom skin » [W]
  (<https://wiki.itemsadder.com/adding-content/blocks>). Sans IA, rien ne garantit que le spawner
  reste inerte : le contenu de `SpawnData` n'est pas documenté et un spawner vanilla tique quand
  un joueur est proche. **Risque d'apparition d'entités au spawn [A VÉRIFIER]**. Le world-scan
  extrait, pour chaque spawner : `SpawnData`, `SpawnPotentials`, `Delay`, `MinSpawnDelay`,
  `MaxSpawnDelay`, `SpawnCount`, `RequiredPlayerRange`. Si un spawner du spawn a un `SpawnData`
  actif, il est neutralisé par AAEngine (`SpawnerSpawnEvent` annulé pour les positions listées)
  ou par la configuration, **jamais** par une édition de monde sans sauvegarde.
- `ENTITY` : bloc de repli vanilla (`fallback_vanilla_block`, défaut global
  `blocks.custom.entity.fallback-vanilla-block: GRAY_CONCRETE`) + rendu par « display » ; entité
  réelle ou paquets : **[A VÉRIFIER sur sauvegarde]**
  (<https://wiki.itemsadder.com/adding-content/blocks/entity-blocks>). Sans remplaçant, ces blocs
  redeviennent du béton gris.
- Détection : WP-SCAN, lecture hors ligne des régions (format Anvil vanilla, [MC]
  <https://minecraft.wiki/w/Region_file_format>) pour les spawners et les blocs de repli, et des
  `entities/*.mca` pour les displays. Adoption par AAEngine en WP7.

#### 4.1.5 Données nécessaires et risques

| Besoin | Source | État |
|---|---|---|
| Table état → modèle | [P] `note_block.json` | disponible |
| Table état → CMD de l'item-bloc | [P] via UUID ([A] `block_item_link.json`) | disponible (175/175) |
| Textures sources non protégées | `contents/**/resourcepack` ou pack régénéré sans protection | **manquante** (sauvegarde complète requise) |
| Table état → id IA | `storage/real_blocks_ids_cache.yml`, `contents/**`, export API | **manquante** (0 octet / absent) |
| Dureté, outil, drops, sons, directionnalité | `contents/**/configs/*.yml` | **manquante** |
| Valeur de `block-updates.*` | `config/paper-global.yml` | **manquante** (sauvegarde complète requise) |
| Occurrences au spawn, `TILE`/`ENTITY`, spawners | mondes (régions, entities) | **manquante** |

Risques : pack client non identique (SHA-1) → blocs au modèle manquant ; verrou absent →
changement d'apparence d'un bloc du spawn (d'où le fail-closed) ; casse ou explosion d'un bloc
réservé → bloc custom perdu de façon irréversible (d'où les gardes) ; `note_block` posé par un
joueur dans un état réservé (d'où le forçage `harp`) ; `TILE` non détecté → spawner actif au
spawn ; `ENTITY` non détecté → béton gris.

### 4.2 Items en inventaire

#### 4.2.1 Format source

- Matériau de base : `minecraft:paper` (« material n'est plus obligatoire, paper par défaut »
  en 4.0.13+, <https://wiki.itemsadder.com/adding-content/items/modern-items-creation>).
- **CMD** désigne ici `minecraft:custom_model_data.floats[0]` : le composant persisté sur 26.2
  porte des listes (`floats`, `flags`, `strings`, `colors`) et les `range_dispatch` de [P] lisent
  `index: 0`. Les anciens `CustomModelData` entiers ont été convertis en `floats[0]` par le data
  fixer **[A VÉRIFIER par `/paper dumpitem`]**.
- Rendu 1.21.4+ [P] : `ia_overlay_{1_21_4_to_5,1_21_6_plus,modern_atlas,26_1_plus}/assets/minecraft/items/paper.json`,
  `range_dispatch` sur `custom_model_data`, `index: 0`, fallback `minecraft:item/paper` ;
  **342 seuils** de 10000 à 9948344, identiques dans les 4 overlays. Le format legacy
  `assets/minecraft/models/item/paper.json` (344 overrides `predicate.custom_model_data`) est
  ignoré par les clients 1.21.4+ et n'est pas réémis.
- Segments mesurés [A] (`paper_cmd_map.json`) :

| CMD (`floats[0]`) | Contenu | Id lisible depuis [P] |
|---|---|---|
| 10000-10006 | 7 icônes `_iainternal:item/ia_auto/icon_*` (menu IA) | oui, confirmé par [G] |
| 10007-10012 | 6 clés `crystal_creations` (`ia_auto`) | feuille du modèle **[A VÉRIFIER : feuille = id]** |
| 10013-10018 | 6 caisses `crystal_creations` (`crates_expansion/*_crate`) | non |
| 10019-10120 | 102 `emoji:item/ia_auto/*` | feuille du modèle **[A VÉRIFIER]** |
| 14253-9948343 (dispersés) | 175 items-blocs (`_iainternal:item_context/<ns_f06dbecf>/item/ia_auto/<uuid>`) et 45 meubles (`<ns_5f89f940>:item/<uuid>`) | non |
| 9948344 | sentinelle → `item/paper` | — |

- **Modèles `item_context`** : pour les formats 84+ (26.2 inclus), les 175 items-blocs ne
  pointent pas vers le modèle de bloc mais vers `_iainternal:item_context/…`. Les fichiers
  n'existent que dans les overlays `ia_overlay_26_1_plus` et `ia_overlay_modern_atlas` (177
  fichiers chacun, 354 au total : 175 items-blocs + 2 `modelengine/internal_fire/fire_{0,1}`).
  Chacun est un simple `{parent: block/cube_all, textures: ia:N}` dont les sprites sont déclarés
  dans l'atlas `items` [P]. Ce sont des copies mécaniques des modèles de blocs de l'utilisateur,
  pas des assets propres à IA.
- Les CMD et états sont importés **tels quels** depuis [P] ; AAEngine ne reproduit aucun
  algorithme d'allocation d'IA.
- Autres dispatch [P] : `player_head` CMD 10000-10012 → `_iainternal:entity/player/*` (modèle
  joueur IA, exclu) ; `leather_horse_armor` CMD 1-13 → ModelEngine, dont 2 cibles
  `_iainternal:item_context/modelengine/internal_fire/*`.
- Identité dans l'ItemStack : `minecraft:custom_data` → `itemsadder: {namespace, id}` selon une
  source **tierce** (<https://modrinth.com/mod/iaat>) ; format de référence côté plugins tiers
  `itemsadder:<namespace>__<key>` (<https://plugins.auxilor.io/the-item-lookup-system/external-item-integrations>).
  API : `CustomStack.getNamespace()`, `getId()`, `getNamespacedID()` [J]
  (<https://lonedev6.github.io/API-ItemsAdder/dev/lone/itemsadder/api/CustomStack.html>).
  **[A VÉRIFIER sur un item réel]** ; clés de durabilité ou d'usages custom non documentées
  **[A VÉRIFIER]**.

#### 4.2.2 Stratégie AAEngine

- **Porte d'entrée de WP2b (palier A)** : `/paper dumpitem` sur un item, un item-bloc et un item
  de furniture, sur le serveur de répétition. Vérifier : `custom_model_data.floats[0]` présent,
  **absence** de `minecraft:item_model`, présence de `custom_data`. Sans cette preuve, l'apparence
  des items détenus n'est pas garantie.
- **Couche legacy du dispatch `paper`** (WP2b) :
  - (a) **exclues** : les 7 icônes de menu `_iainternal:item/ia_auto/icon_*` (repli
    `minecraft:item/paper`), ainsi que `player_head` → `_iainternal:entity/player/*` ;
  - (b) **conservées** : les 175 entrées `_iainternal:item_context/<ns_f06dbecf>/…`, réécrites
    vers `aaengine_legacy:item_context/<uuid>`, modèles régénérés par une transformation
    mécanique des données de l'utilisateur (parent `block/cube_all`, textures remappées vers les
    sprites de l'atlas `items` AAEngine) ; même traitement pour les 2 cibles `modelengine` de
    `leather_horse_armor.json` (voir aussi WP2c) ;
  - les autres seuils (crystal_creations, emoji, furniture) sont réémis à l'identique, alias
    réécrits.
  Avec ces deux cas, **tous les items détenus gardent leur apparence** dès le retrait d'IA, sans
  migration.
- **Sentinelles** : `range_dispatch` retient le plus grand seuil inférieur ou égal à la valeur
  ([MC] <https://minecraft.wiki/w/Items_model_definition> **[A VÉRIFIER]**). Avec des CMD
  dispersés, un PAPER à CMD d'un autre plugin tomberait sur un modèle legacy. Pour chaque CMD
  legacy N, émettre une entrée N+1 → `minecraft:item/paper`, sauf si N+1 est lui-même un seuil
  legacy.
- Le contenu AAEngine natif utilise `minecraft:item_model` (spec §8), jamais le dispatch CMD de
  `paper` ; collision de seuils détectée au build (`ERROR`).
- `LegacyItemResolver` (WP5), ordre de résolution :
  1. compound `itemsadder {namespace, id}` dans `minecraft:custom_data` **[A VÉRIFIER]** → table
     d'alias `legacy-item-map.json` ;
  2. repli (matériau, `floats[0]`) → id via la même table (CMD → modèle depuis [P], modèle → id
     depuis `contents/**`, export API ou dump d'ItemStack).
- Migration **paresseuse** (join, ouverture d'inventaire, ramassage), limitée par tick et
  exécutée sur le thread de l'entité (Folia) ; jamais sur les conteneurs de chunks non chargés.
  Elle écrit `aaengine:item_id` et `aaengine:schema` (PDC), **préserve** nom renommé,
  enchantements, durabilité et données d'autres plugins (§39), et **laisse le compound
  `itemsadder` intact**.
- Modes : `dry-run` (log et compteurs), `tag-only` (ajout de PDC `aaengine:*` uniquement ; CMD,
  matériau et composants intouchés), `full`. Pendant la fenêtre de retour arrière, le code
  **plafonne** le mode à `tag-only` (section 4.10). Idempotence : deux migrations = une.
- API : `AAItemService.update(stack): UpdateResult` (prévu §7, absent du code), event
  `AAItemMigratedEvent`.

#### 4.2.3 Données nécessaires et risques

Nécessaire : dumps d'ItemStack réels ; `contents/**` ; cache d'ids d'items
(`storage/items_ids_cache.yml`, nom exact **[A VÉRIFIER]**) ; playerdata, ender chests, conteneurs
du monde pour le comptage. Risques : modèles `item_context` manquants (items-blocs rendus en
papier) si la règle d'exclusion est trop large ; écraser des données tierces ; collision de seuils
CMD ; migration trop agressive qui rend le retour arrière impossible (d'où le plafond
`tag-only`).

### 4.3 Furniture et entités

#### 4.3.1 Format source

- [P] : 45 modèles 3D `<ns_5f89f940>:item/<uuid>` (éléments hors 0..16), 6 caisses
  `crystal_creations`, modèles de base
  `minecraft:item/base/furniture_{painting,big_painting,long_painting,tall_painting}` et
  `block/base/block_tile` (affichage `head`, échelle ×3.022). « 5f89 = furniture » est une
  inférence **[A VÉRIFIER]**.
- Teinte : les modèles déclarent `tintindex: 0`, mais **aucune** des 342 entrées de
  `ia_overlay_*/assets/minecraft/items/paper.json` n'a de `tints` (0/342) [P] ; sur les clients
  1.21.4+, ils sont donc rendus non teintés. La couche legacy n'ajoute pas de `tints` (sinon le
  rendu changerait). Seul `leather_horse_armor.json` (ModelEngine) déclare `tints:
  minecraft:dye`.
- Seul `paper.json` porte les 45 modèles `<ns_5f89f940>` : hors `paper`,
  `leather_horse_armor` (ModelEngine) et `player_head`, [P] ne contient aucun dispatch custom. Sur
  un client 26.2, ces modèles ne sont donc atteignables que via PAPER + CMD [P]. Cela ne prouve
  pas qu'une entité persistée porte cet item.
- Entités documentées [W] (<https://wiki.itemsadder.com/adding-content/furnitures>) : entité
  configurable (`item_display`, `armor_stand` par défaut, `item_frame`), matériau réglable par
  `material:`. Hitbox `hitbox: {length, width, height, *_offset}` ; `solid: true` crée une hitbox
  en blocs `BARRIER` ; `light_level` place des blocs `LIGHT`
  (<https://wiki.itemsadder.com/adding-content/furnitures/furniture-complex>,
  <https://wiki.itemsadder.com/adding-content/furnitures/furniture-size>). API :
  `CustomFurniture.byAlreadySpawned(Entity|Block)`, `getEntity()` [J].
- **Entités réelles persistées ou simulées par paquets : non documenté.** Si IA rend les
  furniture par paquets, elles disparaissent au retrait d'IA et seules les barrières restent.
  **[A VÉRIFIER : entités réelles dans `entities/*.mca`, avec item PAPER + `custom_model_data`]**.
- **Marquage concret des entités (tags, PDC, nom) : non documenté, [A VÉRIFIER sur sauvegarde].**

#### 4.3.2 Stratégie AAEngine

- Apparence : **si** le scan des entités du monde confirme des entités réelles portant un PAPER +
  CMD de la plage furniture, elles restent visibles dès que la couche legacy sert le dispatch
  **[A VÉRIFIER : entités réelles persistées dans `entities/*.mca`]**. Sinon, le palier A ne
  garantit pas les furniture.
- **FurnitureGuard** (WP7a, palier A) : pour toute entité portant un PAPER dont le CMD appartient
  aux 45 CMD furniture de `paper_cmd_map.json`, annuler `EntityDamageByEntityEvent`,
  `HangingBreakEvent`, `PlayerItemFrameChangeEvent`, `PlayerArmorStandManipulateEvent` et
  `PlayerInteractAtEntityEvent` ; ces cas sont journalisés pendant la fenêtre. Folia :
  scheduler d'entité.
- Comportement (WP7, palier B) : moteur furniture AAEngine (ItemDisplay racine, hitbox
  Interaction ou barrières, sièges, rotation, casse avec drop, index monde/entité/chunk §11,
  culling §12) et `FurnitureAdopter` qui, au chargement de chunk, reconnaît les entités IA
  existantes (type + marqueur **[A VÉRIFIER]**) et les enregistre comme instances AAEngine
  **sans les respawn**. Pendant la fenêtre de retour arrière, adoption **en mémoire seulement**
  (aucune écriture d'entité), casse et ramassage désactivés.
- Les blocs `BARRIER` et `LIGHT` de hitbox restent dans le monde : ils continuent de bloquer et
  d'éclairer, même avant adoption.

#### 4.3.3 Données nécessaires et risques

Nécessaire : `entities/*.mca` et régions du spawn (type, item, CMD, tags, PDC, position, rotation,
barrières et blocs `light` autour) ; `contents/**` (hitbox, sièges, lumière, drop). Risques :
furniture rendues par paquets (invisibles sans IA) ; furniture non adoptées = indestructibles ou
impossibles à ramasser ; barrières orphelines si une entité est retirée sans ses barrières ; scan
d'entités coûteux (adoption incrémentale, au chargement de chunk).

### 4.4 Font images, emojis, GUI

#### 4.4.1 Format source

- YAML `font_images` : `path`, `scale_ratio`, `y_position`, `symbol` (sinon « ItemsAdder will
  automatically decide the custom character »), `permission`, `show_in_gui`, `shadow`
  (<https://wiki.itemsadder.com/adding-content/font-images/font-images>). Usages : `:nom:` en
  chat, panneaux, livres, titres de GUI ; `%img_nom%` via PlaceholderAPI ; décalages
  `:offset_-16:` / `%img_offset_16%`
  (<https://wiki.itemsadder.com/adding-content/font-images/emojis>,
  <https://wiki.itemsadder.com/adding-content/font-images/guis>).
- Cache [C] : une ligne `namespace:id: <caractère>` par image, **269 entrées, toutes avec un
  codepoint**. Les caractères de la zone d'usage privé s'affichent vides dans un terminal (par
  exemple `_iainternal:no_recipe` = octets `EE 80 80`, U+E000). Le parseur WP6 lit les octets
  UTF-8 et ne traite jamais une valeur visuellement vide comme absente. Toutes les entrées sont
  présentes dans [P] `assets/minecraft/font/default.json` (et `uniform.json`, identique) [A] :

| Namespace | Nb | Codepoints |
|---|---|---|
| `_iainternal` | 27 | U+E000-E01A (GUI, `small_cooldown_bar_0..20`) |
| `crystal_creations` | 6 | U+E01B-E020 (menus de caisses) |
| `iconic` | 150 | U+E900-E995 |
| `emoji` | 86 | **hors PUA** : 35 grecs (U+03B1…), 50 CJK (U+4E00-9FFF), 1 U+3292 ; ex. `emoji:boutique` = `α`, `emoji:caissepirate` = `金` |

- Plus 29 glyphes hors cache dans `default.json` : espaces de décalage sur
  `minecraft:char/black.png`, ascent -5000, U+F801-F80F (négatifs) et U+F822-F82F (positifs).
- Autres polices [P] : `_iainternal:gui` (3 glyphes), `nameplates:*` (plugin CustomNameplates).
- Shaders de texte [P] : `rendertype_text.json/.vsh` (racine, overlays `1_21_6_to_11` et
  `26_1_plus`) et `ia_overlay_26_2_plus/assets/minecraft/shaders/core/text.vsh` (seul contenu de
  l'overlay 26.2). Leur effet visuel sur les font images (ombre, décalage) est inconnu
  **[A VÉRIFIER par comparaison A/B]**.

#### 4.4.2 Stratégie AAEngine (WP6)

- Import du cache dans `.glyph-map.json` (§20), **mêmes codepoints, même police
  `minecraft:default`**, jamais réassignés au reload. Ainsi les textes déjà stockés (messages,
  configs de menus, scoreboard) qui contiennent le caractère brut restent valides.
- Exclusion des 27 `_iainternal` (assets propres à IA) ; si une config tierce les utilise
  (recherche à faire dans les configs du serveur) **[A VÉRIFIER]**, fournir un glyphe AAEngine
  de remplacement au même codepoint.
- Espaces de décalage : AAEngine les émet par un provider de police `space` aux mêmes
  codepoints U+F801-F80F / U+F822-F82F ([MC] <https://minecraft.wiki/w/Font>), au lieu de
  bitmaps `black.png`.
- Shaders : non repris. Les effets nécessaires, s'il y en a, sont reproduits par des moyens
  vanilla : composant texte `shadow_color` (1.21.4+), `ascent` des providers. Comparaison A/B
  des GUI pendant la répétition ; tout écart restant est signalé dans `report.json`.
- Collision grec/CJK : conservée pour compatibilité, **WARN** dans le rapport, migration
  ultérieure possible vers la PUA avec alias.
- Syntaxes : MiniMessage `<glyph:id>`, remplacement `:nom:` (avec permission si configurée),
  expansion PlaceholderAPI compatible `%img_<id>%` et `%img_offset_<n>%` **[A VÉRIFIER : liste
  exacte des placeholders publics]**.
- API : `AAGlyphService.byCodepoint(Int)`, `placeholder(id)`.

Données : bitmaps sources dans `contents/**` (section 3.1) ; [C] et les JSON de [P] pour la
correspondance ; `contents/**` pour permissions et `show_in_gui`. Risques : réallocation
accidentelle d'un codepoint (casse tous les menus) ; titres de GUI différents si les shaders
avaient un effet ; affichage d'emojis à la place de vrais caractères grecs ou chinois (déjà le
cas aujourd'hui).

### 4.5 HUD

- Activables par monde, `huds.force_color` dans `config.yml`
  (<https://wiki.itemsadder.com/adding-content/font-images/huds>). Canal (actionbar, bossbar) et
  persistance par joueur non documentés **[A VÉRIFIER]**.
- [C] ne contient, côté HUD, que les `small_cooldown_bar_*` de `_iainternal` : aucun HUD custom
  du serveur n'est visible dans les données **[A VÉRIFIER dans `contents/**`]**.
- Stratégie : hors palier A ; s'il existe des HUD custom, ils seront recréés comme glyphes
  AAEngine + service HUD (phase ultérieure).

### 4.6 Sons

- [P] `assets/minecraft/sounds.json` (20 clés) : `block.wood.*` et `block.stone.*` (break, fall,
  hit, place, step) sont **vidés** (`replace: true`) puis recopiés sous `internal.block.wood.*` /
  `internal.block.stone.*`. Interprétation : couper le son bois du `note_block` côté client pour que
  le serveur joue les sons custom ; effet de bord, **tous** les blocs vanilla bois et pierre
  deviennent muets côté client si le serveur ne rejoue pas leurs sons **[A VÉRIFIER en jeu]**.
- Stratégie : **ne pas reprendre** cet override en palier A (sans le rejeu serveur il rendrait
  muets les blocs vanilla). Conséquence acceptée : les blocs custom du spawn émettent le son bois
  du `note_block`. Les sons custom par bloc (WP4) viendront de `contents/**` ; la stratégie pour
  couper le son de base sans casser le vanilla est **[A VÉRIFIER]** (WP4).

### 4.7 Armures

- Aucun asset `equipment/*.json` dans [P]. Présents : `textures/models/armor/leather_layer_{1,2}.png`
  (128×32, 2 colonnes, couleur-clé colonne 2 = `#A06540`, couleur cuir vanilla par défaut) et le
  shader `rendertype_armor_cutout_no_cull` qui choisit la colonne selon la teinte.
- Conclusion : probablement **0 armure custom** **[A VÉRIFIER dans `contents/**`]**.
- Stratégie : ne pas reprendre le shader ni les textures d'armure IA ; si des armures custom
  existent, les recréer via le format vanilla `equipment` (1.21.2+).

### 4.8 Recettes

- Aucune information dans le pack : les recettes sont dans `contents/**/configs/*.yml`
  (absent). Stratégie : conversion par le migrateur vers le format recettes AAEngine (phase 6 de
  la spec, `engine-recipes`) une fois `contents/**` disponible. Hors palier A.

### 4.9 Autres éléments du pack et émission pour 26.2

| Élément [P] | Décision |
|---|---|
| `_iainternal:item/ia_auto/icon_*` (7 icônes de menu) | **exclu** |
| `_iainternal:entity/player/*`, `textures/item/player_model`, `items/player_model` (modèle joueur IA, 3449 modèles `player_model`) | **exclu** |
| `_iainternal:item_context/**` (175 items-blocs + 2 `modelengine/internal_fire`, dans 2 overlays) | **régénéré** sous `aaengine_legacy:item_context/...` par transformation mécanique (section 4.2.2) ; cibles de `paper.json` et `leather_horse_armor.json` réécrites |
| Shaders (`ia_overlay_*/…/shaders`, `rendertype_*`, `text.vsh`, `player_shader.glsl`) | **exclus** ; effets de texte reproduits par moyens vanilla si nécessaire (section 4.4.2) |
| `lang/*.json` (117 fichiers, 3 clés vides masquant la ligne « Teint ») | exclus ; effet cosmétique accepté |
| `optifine/emissive.properties` | exclu sauf besoin avéré |
| `models/item/paper.json` (344 overrides `predicate`, ignorés depuis 1.21.4) | non réémis |
| Namespaces de contenu : `emoji`, `iconic`, `crystal_creations`, `vyse_textures`, 2 namespaces `7a48…` | repris (couche legacy), textures depuis les sources non protégées, sous réserve des licences (section 4.11) |
| `modelengine` (34 fichiers + overlays `modelengine_1_19_4` à `modelengine_26_2`, shaders `entity.fsh/vsh`), `nameplates` (79 fichiers) | **WP2c, palier A** : fusion depuis les dossiers de sortie de ModelEngine et CustomNameplates (chemins **[A VÉRIFIER sur la sauvegarde]**), pas depuis la copie IA |
| `pack.mcmeta` | régénéré pour la **cible unique 26.2** (ci-dessous) |

**Émission pour 26.2** (WP2-min, WP2b) :

- `pack.mcmeta` au schéma `min_format` / `max_format` (celui de la racine et des overlays de
  [P]) ; format exact de 26.2 **[A VÉRIFIER sur <https://minecraft.wiki/w/Pack_format>]** (dans
  [P], les overlays 26.2 sont déclarés pour 88-96) ; **aucun overlay** tant qu'une seule version
  est visée ;
- deux atlas cohérents, `atlases/blocks.json` et `atlases/items.json`, chaque sprite déclaré dans
  l'atlas où ses modèles le cherchent ;
- `items/*.json` uniquement (pas d'overrides legacy) ;
- validateur de références : tout modèle → textures existantes, tout sprite présent dans le bon
  atlas, toute cible de dispatch résolue (test golden au format 26.2) ;
- test client réel 26.2 : journal sans modèle ni texture manquants, hors les 5 connus de [D].

**Fusion des packs externes** (WP2c) : détection de conflits sur `items/leather_horse_armor.json`,
`atlases/blocks.json` (source `directory` `entity/models`) et les shaders ModelEngine. Autre
option : envoyer plusieurs packs au join via l'API Paper `Player#sendResourcePacks` avec une
`ResourcePackRequest` (1.20.3+) ; `server.properties` n'accepte qu'un pack.

**Distribution** (WP2d) : le pack est construit hors ligne (`--apply`), son SHA-1 est figé, puis
il est servi soit par une URL HTTPS statique avec le hash dans le chemin (spec §19), soit par le
`PackPublisher` d'AAEngine avec `ResourcePackRequest` au join, en reproduisant la politique
actuelle d'IA (obligatoire ou proposé). La méthode d'hébergement actuelle d'IA (section de
`plugins/ItemsAdder/config.yml` : auto-hébergement sur un port ou hébergeur externe [W]
**[A VÉRIFIER]**) est relevée avant la bascule, sans recopier de token. Le port choisi doit être
exposé par l'hébergeur de DinoRealms **[A VÉRIFIER]**.

### 4.10 Fenêtre de retour arrière (`migration.rollback-window`)

Réglage `migration.rollback-window: true`, **activé par défaut le jour J**. Tant qu'il est
actif, le code impose :

- pose de blocs custom AAEngine **désactivée** ;
- casse des blocs réservés et de furniture **désactivée** (gardes WP3a, WP7a) ;
- mode de migration des items **plafonné à `tag-only`** : ajout de PDC `aaengine:*` uniquement,
  CMD, matériau et composants (dont `item_model`) intouchés ; une config `full` est refusée ;
- adoption des furniture **en mémoire seulement**, aucune écriture d'entité ;
- création d'items legacy (give, drops, clés de caisses) **refusée**, sauf si le format du
  compound IA est vérifié par dump et reproduit à l'identique **[A VÉRIFIER]** ;
- `block-updates.disable-noteblock-updates` exigé (sinon arrêt, section 4.1.3).

Le réglage ne se coupe qu'avec l'accord de Dylan pour la fermeture de la fenêtre
(mode opératoire privé, §8).

### 4.11 Licences des packs achetés

> Les packs payants (Vyse, Iconic, Crystal Creations, emoji) sont utilisés sous les licences de
> Dylan, **uniquement sur le serveur DinoRealms**.
>
> - Redistribution interdite : dépôt git, templates AAEngine, autres clients AzurHOSTS, archives
>   partagées.
> - Avant le palier A, relever pour chaque pack la clause de licence (plugin cible, droit de
>   modification, exigence de protection) et la consigner **hors dépôt**. Certaines éditions
>   vendues « pour ItemsAdder » peuvent limiter l'usage à ce plugin ou interdire la modification
>   **[A VÉRIFIER licence par licence]**.
> - Le pack servi par AAEngine ne sera **plus protégé** contre l'extraction : Dylan en est
>   prévenu ; une licence exigeant une protection bloque la conversion du pack concerné
>   **[A VÉRIFIER]**.
> - URL du pack propre à DinoRealms, non listée, avec le hash dans le nom.
> - Les crédits restent ceux des créateurs des packs.

---

## 5. Outil hors ligne `aaengine-migrator-itemsadder` (WP8a, WP8)

### 5.1 Positionnement

- Module Gradle séparé `tools/migrator-itemsadder`, jar CLI **hors** du plugin shadé (§41).
- **Pas de commande en jeu** : aucune lecture des mondes ou dossiers du live depuis le serveur.
- Travaille **uniquement sur une copie hors ligne**. Par défaut, refus de toute entrée dont un
  monde contient un `session.lock` verrouillé ou modifié depuis moins de 10 minutes
  (configurable). Interdiction documentée de pointer un montage SFTP ou réseau du live ;
  l'outil refuse les chemins UNC et les lecteurs réseau qu'il sait détecter **[A VÉRIFIER :
  détection fiable sous Windows]**.
- WP8a (palier A) : sous-ensemble produisant le dry-run, la couche legacy, `glyph-map.json` et la
  liste des états réservés. WP8 (palier B) ajoute items, blocs nommés, furniture.

### 5.2 Entrées

| Option | Contenu | Obligatoire |
|---|---|---|
| `--ia-dir <copie de plugins/ItemsAdder>` | `contents/**` (textures sources), `storage/*.yml`, `config.yml` | oui pour les textures |
| `--pack <pack>` | pack IA **non protégé** régénéré sur la répétition (section 3.1) ; le pack protégé du live n'est accepté qu'avec `--trust-central-directory`, pour ses JSON seulement | oui |
| `--font-cache <font_images_unicode_cache.yml>` | cache des codepoints | oui |
| `--world <copie d'un monde>` (répétable) | régions, `entities/` pour le scan | non |
| `--item-dumps <dossier>` | dumps d'ItemStack (`/paper dumpitem`) | non |
| `--ia-api-export <csv>` | export produit sur le serveur de répétition (section 4.1.3) ; colonnes `kind,namespaced_id,instrument,note,powered,material,cmd_float0` | non |
| `--namespace <ns>` | namespace AAEngine cible (ex. `dinorealms`) | oui |
| `--out <dossier>` | dossier de sortie inexistant, **hors de tout dépôt git** | oui |

Si `--pack` est protégé, l'outil vérifie que les PNG référencés existent dans `--ia-dir` (preuve
de possession des sources) et n'extrait jamais de PNG du pack protégé.

### 5.3 Modes

- `--dry-run` (défaut) : analyse et `report.json` seulement, aucune autre écriture.
- `--apply` : écrit `converted/` dans `--out`.
- `--force` : seul moyen d'écrire dans un `--out` non vide.
- `--trust-central-directory` : relâche **uniquement** la comparaison nom local / nom central
  (section 3.2), pour lire les JSON d'un pack protégé ; jamais de PNG. Livré seulement après
  validation juridique d'AzurHOSTS (section 8) ; absent du plugin.

### 5.4 Sorties

```text
<out>/                                # hors dépôt
  report.json
  converted/
    items/*.yml                       # format AAEngine §8
    blocks/*.yml                      # avec legacy_state
    furniture/*.yml                   # si contents/** disponible
    pack/legacy-itemsadder/           # couche legacy (PNG ré-encodés depuis les sources, alias réécrits)
    block-state-map.json              # états réservés -> id AAEngine (ou orphelin)
    glyph-map.json                    # id -> codepoint, police
    legacy-item-map.json              # (matériau, custom_model_data.floats[0]) -> modèle -> id IA -> id AAEngine
    world-scan.json                   # si --world : comptage des états et entités
```

`report.json` contient au minimum : version de l'outil ; SHA-256 de chaque entrée ;
inventaire (blocs, items, furniture, glyphes) ; mappings et leur source (contents, cache,
dump, export API, synthétique) ; orphelins ; collisions (CMD, codepoints, grec/CJK) ; assets
exclus par la règle clean-room ; textures manquantes (les 5 de [D]) ; anomalies de zip (nombre
d'entrées lues avec `--trust-central-directory`) ; écarts GUI connus ; pour `world-scan` : nombre
de `note_block` par état, classés **reconnus** (dans les 177), **vanilla réservés** (`harp`) et
**inconnus**, spawners (avec `SpawnData`, `SpawnPotentials`, délais), blocs de repli `ENTITY`
candidats, entités furniture candidates (type, item, CMD), nombre de blocs `minecraft:barrier` et
`minecraft:light` autour de chacune. Chaque élément porte un niveau `ERROR`/`WARN`/`INFO` et un
code (§15).

### 5.5 Garanties de sécurité

- **Aucun accès réseau**, aucun shell, aucune exécution de contenu importé.
- Lecture zip stricte (section 3.2) ; limites configurables, avec valeurs par défaut proposées :
  20 000 entrées, 64 Mio par entrée décompressée, 1 Gio cumulés, ratio de compression 200:1, PNG
  4096×4096 max **[A VÉRIFIER : à calibrer sur le pack réel]**.
- Lecture Anvil (WP-SCAN), fichiers ouverts en **lecture seule** :
  - longueur d'un chunk ≤ 1 Mio dans le `.mca` ; fichier externe `.mcc` lu seulement au chemin
    **calculé** `c.<X>.<Z>.mcc` dans le même dossier (jamais un chemin lu dans les données), taille
    plafonnée ;
  - types de compression acceptés : zlib, gzip, aucune, LZ4 (réglage Paper de compression des
    régions **[A VÉRIFIER : nom de la clé sur 26.2]** ; format [MC]
    <https://minecraft.wiki/w/Region_file_format>) ; tout autre type refusé ;
  - décompression plafonnée par chunk et au total ;
  - NBT : profondeur ≤ 512, taille des tableaux et listes plafonnée.
- YAML durci (alias limités, taille max, pas de tags custom).
- Aucune écriture hors `--out` (`SafePaths`, section 3.2), pas de suivi de symlinks.
- `--out` refusé s'il se trouve dans un dossier contenant `.git` (test WP8a et hook).
- Jamais d'écriture dans un monde.
- Idempotent : même entrée → mêmes sorties (ordre et horodatages fixes, hash identique).
- Aucun secret dans les sorties : les configs lues (`config.yml` d'IA) peuvent contenir une
  URL d'hébergement de pack ou des tokens ; ils ne sont **jamais** recopiés dans `report.json`.

---

## 6. Façade optionnelle d'API ItemsAdder (WP9)

Même modèle que la façade Nexo de [`compatibility.md`](compatibility.md) : une façade d'API, pas
une réimplémentation.

- Module `engine-compat-itemsadder`, **opt-in** (`compat.itemsadder.enabled: true`), désactivé
  par défaut ; chargé uniquement si aucun vrai ItemsAdder n'est présent.
- `provides: [ItemsAdder]` dans `paper-plugin.yml` quand la façade est activée.
- Ids sans namespace résolus via la table d'alias legacy ; méthode non supportée = erreur claire,
  jamais un `null` silencieux.
- Surface minimale, tirée de la javadoc publique (artefact Maven `beer.devs:itemsadder-api`,
  <https://lonedev6.github.io/API-ItemsAdder/>, version affichée 4.0.17-beta-12-test-8) :

| Classe (package `dev.lone.itemsadder.api`) | Méthodes | Délègue à |
|---|---|---|
| `CustomStack` | `getInstance(String)`, `byItemStack(ItemStack)`, `isInRegistry(String)`, `getNamespacedIdsInRegistry()`, `getItemStack()`, `getNamespace()`, `getId()`, `getNamespacedID()`, `isBlock()` | `AAItemService` |
| `CustomBlock` | `getInstance`, `place(String, Location)`, `byAlreadyPlaced(Block)`, `remove(Location)`, `getBaseBlockData(String)` | `AABlockService` |
| `CustomFurniture` | `spawn(String, Block)`, `byAlreadySpawned(Entity\|Block)`, `remove(Entity, boolean)`, `getEntity()` | `AAFurnitureService` |
| `FontImages.FontImageWrapper` | `FontImageWrapper(String)`, `instance(String)`, `getString()`, `replaceFontImages(...)` | `AAGlyphService` |
| `ItemsAdder` | `getAllItems()`, `areItemsLoaded()` **[A VÉRIFIER : nom exact]** | `AAEngine` |
| `Events.ItemsAdderLoadDataEvent` | constructeur `(boolean isReload, boolean skipRecipes)` | réémis depuis `AAEngineContentLoadedEvent` |
| `Events.CustomBlockPlaceEvent`, `CustomBlockBreakEvent`, `FurniturePlacedEvent`, `FurnitureBreakEvent`… | | réémis depuis les events AAEngine |

Sources :
<https://lonedev6.github.io/API-ItemsAdder/dev/lone/itemsadder/api/package-summary.html>,
<https://lonedev6.github.io/API-ItemsAdder/dev/lone/itemsadder/api/Events/package-summary.html>,
<https://wiki.itemsadder.com/developers/java-api/events>.

À établir avant de développer : la liste des plugins de DinoRealms qui déclarent
`depend`/`softdepend: [ItemsAdder]` et les appels qu'ils font réellement (WP9 n'est utile que si
cette liste est non vide). **Point juridique** : publier un package `dev.lone.itemsadder.api` et
déclarer `provides: [ItemsAdder]` touche à la marque ; à faire valider par AzurHOSTS
**[A VÉRIFIER]**.

---

## 7. Hors périmètre

- Les mécaniques propres à IA non utilisées par DinoRealms : entités custom `.iaentity`, emotes,
  modèle joueur (`_iainternal:entity/player/*`), menus internes d'IA (`ia_gui.yml`).
- La reprise des assets propres à IA (icônes `_iainternal`, modèle joueur, shaders, lang) : exclus
  par principe (section 4.9).
- L'écriture dans les fichiers de région custom d'IA ou dans ses caches : AAEngine ne les écrit
  jamais.
- La conversion du contenu Nexo : si Nexo porte du contenu sur DinoRealms, il faut un import
  dédié (non couvert ici) **[A VÉRIFIER : Nexo réellement utilisé ?]**.
- La redistribution des packs achetés hors de DinoRealms (section 4.11).

## 8. Clean-room

- **Interdit** : décompiler, désassembler, ou copier du code d'ItemsAdder ou de Nexo ;
  télécharger un jar ItemsAdder pour l'analyser ; reconstruire un algorithme d'IA à partir de ses
  sorties (par exemple l'allocation des CMD ou des états). Aucun jar n'a été ouvert pour ce
  document.
- **Autorisé** : les fichiers de données de l'utilisateur ([P], [C], [D], [G]) ; la documentation
  publique ([W]) et la javadoc publique ([J], signatures seulement) ; les données vanilla ([MC]) ;
  la documentation Paper ([PM]) ; l'usage en **boîte noire** du jar IA sous licence de
  l'utilisateur sur le serveur de répétition, via l'API publique (section 4.1.3).
- **Séparation des rôles** : le rapport d'architecture de l'utilisateur (issu d'une analyse du
  bytecode) peut être lu par l'analyste pour l'architecture seulement ; les développeurs de
  WP0, WP2-min, WP2b et WP3a ne le lisent pas ; aucun nom de classe ou de package interne d'IA
  n'apparaît dans le dépôt (les noms de l'API publique `dev.lone.itemsadder.api` de la façade WP9
  ne sont pas concernés).
- Le code source de plugins tiers (dont IAxPresenceFootsteps) n'est pas lu ; seuls leurs README
  peuvent être cités.
- Reproduire un **format de données** (états de blocs, codepoints, CMD) pour l'interopérabilité
  est distinct de copier une implémentation.
- Les identifiants observés (`ia:N`, `ia_auto`, `_iainternal`) sont réécrits vers des namespaces
  AAEngine (`aaengine_legacy`) dans les sorties.
- **Protection du pack** : aucun contournement pour obtenir des assets (section 3.1). Le détail des
  mesures et tout décodeur tolérant restent hors du dépôt public.
- Recommandation : reformuler le titre du §59 de `AAENGINE_RECONSTRUCTION.md` (« Ce qui doit
  encore être reverse-engineered précisément ») en « À établir à partir des données utilisateur
  et de la documentation publique », et retirer « analysés classe par classe ».
- **Validations AzurHOSTS (Hugo CORBIN) requises avant usage ou publication** : export par l'API
  publique sur serveur de test ; flag `--trust-central-directory` du CLI ; publication de ce
  document et de tout outil d'import de pack ; relevé des licences des packs (section 4.11) ;
  façade WP9.

## 9. Lots de travail

Ordre tiré de l'analyse d'écart (AAEngine `main`, commit `27e4fe0`, phase 1). Tailles : S < M < L < XL.

| Lot | Contenu | Dépend de | Taille | Palier | Tests (faisables sans la sauvegarde complète, sauf mention) |
|---|---|---|---|---|---|
| **WP0** | Fondations : `SafePaths`, `SafeZipReader` strict (section 3.2), YAML durci, diagnostics structurés ; **socle runtime** : config typée (dont `migration.rollback-window`), enregistrement de listeners compatible Folia, `/aaengine status` (console) | — | M/L | A | zips synthétiques (zip-slip local et central, bornes, chevauchement, inflate trop long ou trop court, data descriptor incohérent, noms Windows réservés, ADS, NFC/casse, symlink), YAML billion-laughs, TOCTOU de dossier parent |
| **WP2-min** | Écrivain de zip déterministe, `pack.mcmeta` 26.2, fusion d'arbres avec conflits, deux atlas, validateur de références, SHA-1 | WP0 | M | A | déterminisme, golden 26.2 (toute cible de dispatch résolue), sprite dans le mauvais atlas → `ERROR` |
| **WP2b** | Couche legacy depuis sources non protégées (sections 3.1, 4.9) : `note_block.json`, 20 overrides tiers, dispatch `paper` (exclusions explicites, `item_context` régénérés), sentinelles CMD ; porte d'entrée `/paper dumpitem` | WP0, WP2-min | M | A | mini-pack synthétique ; 20 overrides tiers attendus ; **175 seuils CMD d'items-blocs résolus vers un modèle non `paper`** ; sentinelles N+1 ; exclusions clean-room ; test client réel 26.2 : rien de manquant hors les 5 de [D] |
| **WP2c** | Fusion des packs ModelEngine et CustomNameplates | WP2-min | M | A | conflits `leather_horse_armor.json`, atlas `entity/models`, shaders ; contrôle visuel des mobs ModelEngine et nameplates (sauvegarde requise) |
| **WP2d** | Distribution du pack : build hors ligne, SHA-1 figé, URL HTTPS hash-dans-le-chemin ou `PackPublisher` + `ResourcePackRequest`, politique required/prompt | WP2-min | M | A | SHA-1 servi = SHA-1 annoncé ; refus au join selon la politique |
| **WP3a** | Verrou `note_block` : option Paper + gardes (clic, `NotePlayEvent`, casse, explosions, pistons, pose forcée `harp`), fail-closed | WP0, WP2b | M | A | E2E : 177 états posés par `/setblock`, puis clic, bloc dessous et au-dessus (tête), redstone, piston, **casse, explosion**, reload de chunk → état inchangé ; **échec d'init simulé → arrêt du serveur** |
| **WP7a** | `FurnitureGuard` (section 4.3.2) | WP0 | S/M | A | E2E : dégâts, décrochage, rotation d'item frame, manipulation d'armor stand sur une entité PAPER + CMD furniture → annulés |
| **WP6** | Glyphes : import de [C] (octets UTF-8), `.glyph-map.json`, provider `space`, PAPI `%img_…%`, `<glyph:id>` | WP0, WP2-min | M | A | import du cache réel (269 non vides), stabilité après reload, collisions, exclusion `_iainternal` ; A/B GUI (sauvegarde requise) |
| **WP8a** | Migrateur, sous-ensemble palier A (section 5.1) | WP0, WP2b, WP6 | M | A | golden tests sur mini-pack, idempotence, aucune écriture hors `--out`, `--out` dans un dépôt git refusé, aucun réseau, `session.lock` récent refusé |
| **WP-SCAN** | Lecteur Anvil en lecture seule (sections 5.4, 5.5) | WP0 | L | A | régions synthétiques (palette, bits compactés depuis 1.18), compressions zlib/gzip/aucune/LZ4, `.mcc`, chunk trop long, NBT trop profond → refus ; à défaut de ce lot, le world-scan est remplacé par les captures de référence |
| **WP2-full** | Compilateur de pack phase 3 : DAG, cache SHA-256, émetteurs items/blockstates/fonts | WP2-min | XL | B | golden files, couverture exhaustive des états vanilla, collision CMD |
| **WP1** | Items phase 2 : codec §8 + `legacy.custom_model_data`, loader, reload transactionnel, `/aaengine reload\|give\|validate`, `AAItemService.update`, snapshot builder, API 0.2.0 | WP0, WP2-full | L | B | codec, reload atomique, création d'ItemStack (MockBukkit 26.2 **[A VÉRIFIER disponibilité]** sinon E2E Paper 26.2), non-régression AAItems |
| **WP3** | Backend blocs + allocateur d'états réservés, `AABlockService` | WP1, WP2-full, WP3a | L | B | allocation déterministe, import de table synthétique, refus de réallocation, aucune collision avec `harp` ; nommage réel : **sauvegarde requise** |
| **WP4** | Comportements de blocs : pose, casse, dureté, drops, sons, WorldGuard | WP3 | L | B | loot, temps de casse, E2E pose/casse/drop ; valeurs réelles : **`contents/**` requis** |
| **WP5** | Items legacy : `LegacyItemResolver`, migration paresseuse, plafond `tag-only` | WP1, WP8a | L | B | stacks synthétiques, préservation des données tierces, idempotence, dry-run sans mutation, `full` refusé si `rollback-window` ; format réel : **dumps requis** |
| **WP7** | Furniture : moteur + `FurnitureAdopter` | WP1, WP2-full, WP3, WP7a | XL | B | furniture de test (place, sit, break, unload/reload, restart), budget culling, aucune écriture d'entité si `rollback-window` ; adoption : **mondes requis** |
| **WP8** | Migrateur complet (section 5) | WP8a, WP3, WP5 | L | B | idem WP8a + items, blocs nommés, furniture |
| **WP9** | Façade API ItemsAdder (opt-in) | WP1, WP3, WP6, WP7 | M à L | C | contrat de délégation, désactivation si IA présent ; utilité : **liste des plugins requise** |
| **WP10** | Bascule DinoRealms (mode opératoire privé, hors dépôt) | selon palier | M | — | répétition complète sur serveur local |

Paliers :

- **Palier A, parité visuelle** : WP0 → WP2-min → WP2b → WP2c → WP2d → WP3a (avec gardes) → WP7a →
  WP6 → WP-SCAN / WP8a. Exige la **sauvegarde complète** (textures sources, `paper-global.yml`,
  mondes, dossiers ModelEngine et CustomNameplates). Spawn et items détenus gardent leur
  apparence ; furniture seulement si le scan confirme des entités réelles persistées ; pas de
  comportement custom.
- **Palier B, comportements** : WP2-full, WP1, WP3, WP4, WP5, WP7, WP8.
- **Palier C, intégrations** : WP9 si des plugins tiers appellent l'API IA.

## 10. Questions ouvertes

- Écart de comptage 234/235 modèles distincts référencés par `note_block.json` (section 4.1.1).
- Format exact de 26.2 pour `pack.mcmeta` et clés exactes `block-updates.*` / compression des
  régions sur Paper 26.2.
- Furniture : entités réelles persistées ou rendu par paquets (section 4.3.1).
- Nexo : présent et porteur de contenu sur DinoRealms ?
- Nom de l'option de protection du pack dans `config.yml` d'IA et licence du jar IA pour un
  serveur de test (sections 3.1, 4.1.3).
- Shaders de texte : effet réel sur les GUI (section 4.4.1).
- Coupure du son de base des blocs custom sans rendre muets les blocs vanilla (section 4.6).
- Dates de consultation des pages [W] à consigner page par page (section 0).

---

*Document de conception AAEngine, préparé pour AzurHOSTS.*

Developped by AzurHOSTS.com and powered by AzurWare.fr.
