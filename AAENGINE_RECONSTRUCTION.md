# AAEngine — Reconstruction technique et plan d’architecture

> **Document de travail clean-room** construit à partir de l’observation du binaire `nexo-1.30-dev-6f24f4e35.jar` fourni comme référence comportementale.
>
> Objectif : définir **AAEngine**, un moteur Paper moderne pour items, blocs, furniture, resource packs, recettes, glyphes et compatibilités multi-version, sans publier ni recopier le code source décompilé du produit observé.

---

## 0. Résumé exécutif

AAEngine doit être pensé comme un **moteur de contenu Minecraft côté serveur**, et non comme un simple plugin d’items custom.

Le binaire étudié montre une architecture composée de plusieurs sous-systèmes fortement liés :

- registre d’items custom ;
- système extensible de mechanics ;
- custom blocks ;
- furniture et entités d’affichage ;
- génération / fusion / validation / upload de resource packs ;
- glyphes et fontes ;
- recettes custom ;
- abstraction NMS multi-version ;
- compatibilités avec des plugins tiers ;
- mise à jour d’items existants ;
- système de reload partiel ;
- scheduling compatible Folia ;
- import / conversion depuis d’autres moteurs ;
- serveurs de resource pack multiples, dont self-host et S3.

La reconstruction proposée ne doit pas reproduire aveuglément cette structure. AAEngine peut conserver les bons concepts tout en réduisant le couplage, le nombre de singletons globaux, le coût des reloads et la surface d’attaque réseau/fichiers.

---

# 1. Empreinte de l’artefact analysé

## 1.1 Métadonnées observées

Artefact :

```text
nexo-1.30-dev-6f24f4e35.jar
```

Métadonnées principales :

```text
Plugin API : Paper
api-version : 1.21
Main class : com.nexomc.nexo.NexoPlugin
Bootstrap : com.nexomc.nexo.NexoBootstrap
Loader : com.nexomc.nexo.PaperPluginLoader
Version : 1.30-dev-6f24f4e35
Folia supported : true
Langage dominant : Kotlin
Build : Gradle 9.8.0
Build JDK : 25
```

Inventaire :

```text
Entrées JAR : 2 560
Classes JVM totales : 2 190
Classes sous com.nexomc.nexo : 1 410
```

Le JAR conserve les noms des classes, packages et fichiers source Kotlin dans les métadonnées JVM. Il n’est donc pas fortement obfusqué sur le plan structurel.

## 1.2 Dépendances runtime identifiées

Le JAR déclare notamment :

```text
Kotlin stdlib 2.4.20
Spring Expression 6.2.19
Configurate YAML 4.2.0
Configurate Kotlin 4.2.0
mclo Java 2.2.1
Apache HttpClient 5.4.3
AWS SDK S3 2.32.4
```

Le moteur utilise également Paper, Adventure, Brigadier, FastUtil, Creative et une bibliothèque de scheduling Folia.

## 1.3 Compatibilités plugin déclarées

Le descriptor Paper référence des intégrations optionnelles avec notamment :

- AxiomPaper ;
- BentoBox ;
- BlockLocker ;
- Factions ;
- HuskClaims ;
- HuskTowns ;
- Lands ;
- MMOItems ;
- ModelEngine ;
- MythicCrucible ;
- MythicMobs ;
- PlaceholderAPI ;
- PlotSquared ;
- Spartan ;
- TAB ;
- Towny ;
- ViaBackwards ;
- ViaRewind ;
- ViaVersion ;
- Vulcan ;
- WorldEdit ;
- WorldGuard.

AAEngine ne doit pas charger ces intégrations dans le core. Elles devraient être isolées dans des modules `integration-*`.

---

# 2. Vue d’ensemble de l’architecture observée

Les plus gros domaines du JAR sont approximativement :

```text
nms               ~470 classes
mechanics         ~312 classes
pack              ~135 classes
utils             ~124 classes
api                ~79 classes
recipes            ~68 classes
items              ~38 classes
compatibilities    ~35 classes
glyphs             ~29 classes
commands           ~28 classes
converter          ~20 classes
dialog             ~19 classes
configs            ~17 classes
```

Architecture fonctionnelle :

```text
                      ┌─────────────────────┐
                      │      AAEngine       │
                      │     Bootstrap       │
                      └──────────┬──────────┘
                                 │
       ┌─────────────────────────┼─────────────────────────┐
       │                         │                         │
       ▼                         ▼                         ▼
┌──────────────┐         ┌──────────────┐         ┌──────────────┐
│ Config Core  │         │ Version/NMS  │         │ Integrations │
└──────┬───────┘         └──────┬───────┘         └──────────────┘
       │                         │
       ▼                         ▼
┌───────────────────────────────────────────────────────────┐
│                     Content Registry                      │
│  Items │ Blocks │ Furniture │ Glyphs │ Recipes │ Sounds  │
└──────────────────────────┬────────────────────────────────┘
                           │
                           ▼
                 ┌───────────────────┐
                 │ Mechanics Engine  │
                 └─────────┬─────────┘
                           │
        ┌──────────────────┴───────────────────┐
        ▼                                      ▼
┌─────────────────┐                   ┌────────────────────┐
│ Runtime gameplay │                   │ Resource Pack      │
│ events/entities  │                   │ compiler pipeline  │
└─────────────────┘                   └─────────┬──────────┘
                                               │
                                               ▼
                                      ┌────────────────────┐
                                      │ Pack distribution  │
                                      │ selfhost/S3/etc.   │
                                      └────────────────────┘
```

---

# 3. Nommage AAEngine proposé

Namespace recommandé :

```text
dev.aaengine
```

Mapping conceptuel :

| Référence observée | AAEngine proposé | Rôle |
|---|---|---|
| `NexoPlugin` | `AAEnginePlugin` | entrée Paper |
| `NexoBootstrap` | `AAEngineBootstrap` | bootstrap Paper |
| `PaperPluginLoader` | `AAEngineLoader` | dépendances/runtime |
| `NexoItems` | `AAItemRegistry` | registre d’items |
| `NexoBlocks` | `AABlockRegistry` | custom blocks |
| `NexoFurniture` | `AAFurnitureRegistry` | furniture runtime |
| `NexoPack` | `AAPackService` | API resource pack |
| `PackGenerator` | `AAPackCompiler` | build du pack |
| `MechanicsManager` | `AAMechanicRegistry` | factories / listeners / tasks |
| `ConfigsManager` | `AAConfigService` | chargement configs |
| `RecipesManager` | `AARecipeRegistry` | recettes |
| `NMSHandlers` | `AAVersionBridgeRegistry` | implémentations versionnées |
| `NMSHandler` | `AAVersionBridge` | interface NMS |

Le nom `Engine` doit être assumé : les API doivent pouvoir être consommées par d’autres plugins sans dépendre d’implémentations internes.

---

# 4. Structure du projet recommandée

Au lieu d’un seul module énorme :

```text
AAEngine/
├─ build.gradle.kts
├─ settings.gradle.kts
├─ gradle.properties
├─ engine-api/
├─ engine-core/
├─ engine-paper/
├─ engine-items/
├─ engine-mechanics/
├─ engine-blocks/
├─ engine-furniture/
├─ engine-recipes/
├─ engine-pack/
├─ engine-fonts/
├─ engine-integrations/
│  ├─ integration-worldedit/
│  ├─ integration-worldguard/
│  ├─ integration-placeholderapi/
│  ├─ integration-modelengine/
│  └─ ...
├─ engine-version-common/
├─ engine-version-1_21_x/
├─ engine-version-26_x/
├─ engine-testkit/
└─ docs/
```

## Pourquoi séparer ?

Cela permet :

- compilation plus rapide ;
- API stable ;
- bridges NMS remplaçables ;
- tests unitaires sans Paper complet ;
- intégrations optionnelles réellement optionnelles ;
- réduction du classpath runtime ;
- possibilité de publier `engine-api` séparément.

---

# 5. Cycle de vie du plugin

Le cycle observé peut être résumé ainsi.

## 5.1 Phase `onLoad`

Le plugin :

1. stocke l’instance globale ;
2. installe un serveur de pack vide par défaut ;
3. initialise la couche Folia ;
4. enregistre des flags WorldGuard ;
5. nettoie d’anciens datapacks ;
6. effectue un nettoyage legacy lié aux armures custom.

### AAEngine

AAEngine doit déplacer les opérations dépendantes de plugins tiers hors de `onLoad` lorsque possible.

Proposition :

```text
BOOTSTRAP
  ↓
VERSION DETECTION
  ↓
STATIC CONFIG LOAD
  ↓
SERVICE GRAPH CREATION
  ↓
PAPER ENABLE
  ↓
CONTENT SNAPSHOT LOAD
  ↓
PACK COMPILE
  ↓
RUNTIME LISTENERS START
```

## 5.2 Phase `onEnable`

Les appels observés incluent :

- initialisation de la protection ;
- reload initial ;
- propagation du mode debug ;
- migration de settings ;
- création du générateur de pack ;
- listeners d’armures et update d’items ;
- initialisation proxy ;
- initialisation inventory créatif NMS ;
- métriques ;
- enregistrement des mechanics natives ;
- enregistrement des pack servers ;
- compatibilités tierces ;
- plusieurs tâches différées.

### Risque architectural

Un `onEnable()` qui orchestre autant de domaines devient difficile à tester et à rollback.

### AAEngine recommandé

Créer un orchestrateur :

```kotlin
interface EngineStage {
    fun start(context: EngineContext): StageResult
    fun stop(context: EngineContext)
}
```

Stages :

```text
ConfigStage
VersionBridgeStage
RegistryStage
MechanicsStage
PackStage
IntegrationStage
RuntimeStage
```

Chaque stage doit pouvoir échouer proprement sans laisser l’engine dans un état partiellement initialisé.

---

# 6. Reload : point critique

Le binaire observé possède plusieurs chemins de reload :

```text
reload all
reload items
reload pack
reload recipes
reload configs
reload dialogs
```

Le reload principal recharge notamment :

- plugin utils ;
- config manager ;
- inventory ;
- fonts ;
- sounds ;
- converters ;
- inventory manager ;
- multipack ;
- conversions Oraxen / ItemsAdder.

## Problème classique

Modifier plusieurs singletons et maps en place peut produire :

- états intermédiaires ;
- items visibles avant la fin du parsing ;
- recipes cohérentes avec l’ancienne config mais items de la nouvelle ;
- pack généré sur un snapshot incomplet ;
- races entre CompletableFuture et thread serveur.

## AAEngine : reload transactionnel

Le reload doit fonctionner ainsi :

```text
FILES
  ↓
PARSE
  ↓
VALIDATE
  ↓
COMPILE SNAPSHOT
  ↓
PREPARE RESOURCE PACK
  ↓
ATOMIC SWAP
  ↓
POST-RELOAD EVENTS
```

Pseudo-architecture :

```kotlin
data class EngineSnapshot(
    val items: ItemSnapshot,
    val blocks: BlockSnapshot,
    val furniture: FurnitureSnapshot,
    val recipes: RecipeSnapshot,
    val fonts: FontSnapshot,
    val packModel: PackSnapshot
)
```

Puis :

```kotlin
snapshotRef.set(newSnapshot)
```

Aucune registry runtime ne doit être mutée progressivement pendant le parsing.

---

# 7. Système d’items

Le registre observé maintient plusieurs structures :

- map fichier → item IDs → builders ;
- liste ordonnée des noms ;
- liste ordonnée des builders ;
- map directe ID → builder ;
- callbacks de mise à jour ;
- items externes enregistrés par d’autres plugins ;
- cache des sections de configuration ;
- future de chargement asynchrone.

API exposée observée :

```text
exists(id)
exists(itemStack)
idFromItem(itemStack)
itemFromId(id)
builderFromItem(itemStack)
updateItem(itemStack)
loadItems()
reloadItem(id)
registerExternalItem(...)
unregisterExternalItems(plugin)
registerUpdateCallback(...)
```

## AAEngine Item API

```kotlin
interface AAItemService {
    fun exists(id: Key): Boolean
    fun definition(id: Key): AAItemDefinition?
    fun create(id: Key, amount: Int = 1): ItemStack
    fun idOf(stack: ItemStack): Key?
    fun update(stack: ItemStack): UpdateResult
}
```

Éviter d’exposer `ItemBuilder` mutable publiquement.

Préférer :

```kotlin
data class AAItemDefinition(...)
```

immutable.

## Identification d’item

Utiliser un `NamespacedKey` dans le PDC :

```text
aaengine:item_id
```

Ajouter :

```text
aaengine:schema_version
```

pour permettre les migrations.

---

# 8. Format item recommandé

Le format observé comporte notamment :

```yaml
itemname: "Forest Axe"
material: NETHERITE_AXE
Pack:
  model: nexo:item/nexo_tools/forest_axe
```

et peut contenir :

```yaml
Components:
Mechanics:
CustomArmor:
Pack:
```

## Format AAEngine proposé

Standardiser la casse et supprimer les blocs historiques :

```yaml
id: aaengine:forest_axe

item:
  material: NETHERITE_AXE
  name: "<green>Forest Axe"
  lore: []

components:
  unbreakable: false

model:
  item_model: aaengine:forest_axe

mechanics:
  commands:
    enabled: false
```

Un fichier peut contenir plusieurs items, mais chaque item doit avoir un ID pleinement namespacé.

---

# 9. Mechanics Engine

Le système observé utilise :

```text
Mechanic
MechanicFactory
MechanicsManager
```

`MechanicsManager` maintient :

```text
factory par mechanic ID
tasks par mechanic ID
listeners par mechanic ID
```

Une `MechanicFactory` :

- possède un ID ;
- parse une section YAML ;
- crée une mechanic ;
- garde la mechanic associée à un item ;
- peut enregistrer ses listeners.

## Mechanics natives observées

Catégories majeures :

### Custom blocks

```text
noteblock
stringblock
chorusblock
saplings
stackable blocks
directional blocks
log stripping
beacon behavior
custom sounds
```

### Furniture

```text
hitboxes
seats
beds
storage
lights
doors
states
particles
text entities
evolution
rotatable
connectable
preview
culling
jukebox
ModelEngine compatibility
```

### Gameplay

```text
harvesting
smelting
repair
trident
backpack
soulbound
armor effects
commands
custom events
item type
misc
```

## AAEngine mechanics API

```kotlin
interface AAMechanicFactory<T : AAMechanic> {
    val id: Key
    fun parse(ctx: MechanicParseContext): T
    fun validators(): List<MechanicValidator>
}
```

```kotlin
interface AAMechanic {
    val itemId: Key
}
```

Lifecycle :

```kotlin
interface RuntimeMechanic {
    fun start(runtime: RuntimeContext)
    fun stop()
}
```

## Amélioration importante

Les listeners et tasks doivent être gérés par un `MechanicScope` :

```kotlin
interface MechanicScope : AutoCloseable {
    fun listen(listener: Listener)
    fun schedule(task: ScheduledTask)
    override fun close()
}
```

La désactivation d’une mechanic ferme automatiquement son scope.

---

# 10. Custom Blocks

Trois familles majeures sont visibles :

```text
NoteBlock
String/Tripwire-like block
ChorusPlant-based block
```

Le moteur fournit une API de type :

```text
isCustomBlock(block)
place(id, location)
remove(location)
blockData(id)
customBlockMechanic(...)
```

## AAEngine

Créer une abstraction générique :

```kotlin
interface AABlockBackend {
    val type: BlockBackendType
    fun allocate(definition: AABlockDefinition): BlockStateHandle
    fun resolve(block: Block): Key?
    fun place(id: Key, location: Location): PlaceResult
    fun remove(location: Location): RemoveResult
}
```

Backends possibles :

```text
NOTE_BLOCK
TRIPWIRE
CHORUS
DISPLAY_ENTITY
VANILLA_COMPONENT
```

Le choix du backend ne doit pas être visible dans l’API principale.

---

# 11. Furniture

Le sous-système furniture est l’un des plus complexes.

L’API observée sait notamment :

```text
identifier un furniture par location/item/entity
retrouver l’ItemDisplay racine
placer
retirer
récupérer la mechanic
changer l’item affiché
rafraîchir l’affichage
teinter
allumer/éteindre
mettre à jour
convertir
trouver le furniture visé par un joueur
```

## Composition observée

Un furniture peut inclure :

- `ItemDisplay` principal ;
- barrières ou hitboxes ;
- sièges ;
- lits ;
- lumière ;
- stockage ;
- jukebox ;
- texte ;
- particules ;
- états ;
- porte ;
- rotation ;
- placement limité ;
- évolution ;
- ModelEngine ;
- culling.

## Exemple comportemental observé

Configuration conceptuelle :

```yaml
furniture:
  placement:
    floor: true
    wall: false
    roof: false
  hitbox:
    barriers:
      - "0,0,0"
  seats:
    - "0,0,0"
  sounds:
    place: block.wood.place
    break: block.wood.break
```

## AAEngine furniture model

```kotlin
data class AAFurnitureDefinition(
    val id: Key,
    val model: Key,
    val transform: TransformDefinition,
    val hitbox: HitboxDefinition?,
    val seats: List<SeatDefinition>,
    val lights: List<LightDefinition>,
    val states: Map<String, FurnitureStateDefinition>,
    val behaviors: List<FurnitureBehaviorDefinition>
)
```

### Optimisation indispensable

Ne jamais rechercher les furniture en scannant les entités d’un chunk à chaque interaction.

Maintenir :

```text
world UUID + entity ID -> FurnitureInstance
chunk key -> set<FurnitureInstanceId>
block position -> FurnitureInstanceId
```

avec invalidation sur :

```text
chunk unload
entity removal
furniture break
world unload
```

---

# 12. Culling furniture

Le binaire contient un `FurnitureCullingManager` et une tâche dédiée.

AAEngine doit aller plus loin :

- index spatial par chunk ;
- distance squared, jamais `sqrt` dans les hot loops ;
- updates différentielles quand le joueur change de chunk ;
- budget maximum d’entités envoyées par tick ;
- cache des packets statiques ;
- aucune reconstruction d’ItemStack dans la boucle de culling.

Option avancée :

```text
viewer-specific visibility graph
```

pour masquer des furniture individuellement sans supprimer les entités réelles côté serveur.

---

# 13. Resource Pack Pipeline

Le `PackGenerator` observé possède de nombreux composants :

```text
PackDownloader
PackObfuscator
TextureMerger
PackSquash
PackValidator
CustomArmorGenerator
ModelGenerator
FurniturePreviewModelGenerator
AtlasGenerator
GifGenerator
BBModelGenerator
ModernVersionPatcher
LegacyItemOverridesConverter
```

Le pack peut :

- importer un pack requis ;
- importer un pack par défaut ;
- importer des packs externes ;
- importer ModelEngine ;
- ajouter glyphes ;
- ajouter sounds ;
- ajouter langues ;
- générer atlas ;
- générer modèles ;
- convertir des overrides legacy ;
- appliquer des patchs de versions modernes ;
- valider ;
- obfusquer ;
- compresser ;
- uploader.

## AAEngine : compilation par DAG

Ne pas faire un pipeline monolithique de 20 étapes codées en dur.

```kotlin
interface PackStage {
    val id: String
    val dependencies: Set<String>
    suspend fun execute(ctx: PackBuildContext): StageOutput
}
```

Exemple :

```text
LoadSources
  ├─> ParseItems ─> GenerateItemModels
  ├─> ParseFonts ─> GenerateFonts
  ├─> ParseSounds ─> GenerateSounds
  └─> ParseFurniture ─> GeneratePreviewModels

GenerateItemModels
GenerateFonts
GenerateSounds
GeneratePreviewModels
       ↓
     Merge
       ↓
     Atlas
       ↓
    Validate
       ↓
    Optimize
       ↓
   Obfuscate?
       ↓
     Build ZIP
       ↓
      Upload
```

---

# 14. Compilation incrémentale du pack

Le plus gros gain potentiel pour AAEngine.

Chaque entrée doit être hashée :

```text
SHA-256(path + content + relevant config)
```

Cache :

```text
.cache/pack/
  manifests/
  models/
  textures/
  fonts/
  sounds/
```

Si seul :

```text
items/swords.yml
```

change, AAEngine ne doit pas retraiter toutes les textures, GIFs et fonts.

## Build manifest

```json
{
  "schema": 1,
  "inputs": {
    "items/swords.yml": "sha256:...",
    "assets/aaengine/textures/item/sword.png": "sha256:..."
  },
  "outputs": {
    "assets/aaengine/models/item/sword.json": "sha256:..."
  }
}
```

---

# 15. Pack Validation

Le binaire observé expose des settings séparés pour :

```text
models
fonts
atlas
languages
mipmap
UV clamping
```

AAEngine doit produire un rapport structuré :

```text
ERROR
WARN
INFO
```

Exemple :

```text
PACK-E104 Missing texture
asset: aaengine:item/forest_sword
model: assets/aaengine/models/item/forest_sword.json
reference: aaengine:item/forest_sword
```

La validation doit être exécutable indépendamment :

```text
/aaengine validate
```

et idéalement en CI.

---

# 16. Obfuscation du pack

Le système observé peut remapper :

- textures ;
- modèles ;
- sons ;
- namespaces ;
- equipment layers ;
- references atlas/font/blockstate/items.

AAEngine doit considérer cette fonctionnalité comme **protection légère**, pas comme DRM.

Ne jamais promettre qu’un pack client peut rester secret : Minecraft doit télécharger les assets pour les afficher.

Le mapping d’obfuscation doit être stable entre builds lorsque le contenu ne change pas afin d’améliorer le cache client/CDN.

---

# 17. Distribution du resource pack

Backends observés :

```text
Empty
SelfHost
S3
Polymath
Hermes
LobFile
```

AAEngine interface :

```kotlin
interface PackPublisher {
    suspend fun publish(pack: BuiltPack): PublishedPack
}
```

```kotlin
data class PublishedPack(
    val url: URI,
    val sha1: String,
    val metadata: Map<String, String>
)
```

Backends :

```text
LocalHttpPublisher
S3Publisher
ExternalPublisher
NoopPublisher
```

---

# 18. Sécurité — Resource Pack / Réseau

Le moteur observé possède plusieurs fonctionnalités réseau sensibles :

- import depuis URL ;
- téléchargement d’assets ;
- self-host HTTP ;
- upload S3 ;
- credentials externes ;
- imports de packs.

AAEngine doit appliquer les protections suivantes.

## 18.1 Anti-SSRF

Pour les URLs configurables :

Bloquer par défaut :

```text
127.0.0.0/8
10.0.0.0/8
172.16.0.0/12
192.168.0.0/16
169.254.0.0/16
::1
fc00::/7
fe80::/10
```

sauf opt-in explicite.

Revalider l’IP après chaque redirect.

Limiter les redirects.

## 18.2 Limites téléchargements

```text
connect timeout
read timeout
max compressed bytes
max decompressed bytes
max files
max depth
max redirects
```

## 18.3 Zip Slip

Avant extraction :

```kotlin
val target = root.resolve(entry.name).normalize()
require(target.startsWith(root))
```

Refuser :

```text
../
absolute paths
symlinks sortant du dossier
```

## 18.4 Zip bombs

Limiter :

```text
ratio compression
nombre d’entrées
poids décompressé cumulé
poids individuel
```

## 18.5 Secrets

Les clés S3/API ne doivent pas être stockées en clair dans un YAML partagé.

Support :

```text
ENV
system properties
secret file avec permissions strictes
external secret provider
```

Exemple :

```yaml
s3:
  access_key: ${ENV:AAENGINE_S3_ACCESS_KEY}
  secret_key: ${ENV:AAENGINE_S3_SECRET_KEY}
```

---

# 19. Self-host HTTP

Le serveur de pack interne doit :

- binder explicitement sur une adresse ;
- avoir une limite de connexions ;
- limiter les threads ;
- mettre des headers corrects ;
- utiliser ETag ;
- supporter cache immutable si hash dans URL ;
- ne servir qu’un ensemble de paths connus ;
- refuser tout path traversal ;
- ne jamais exposer le data folder ;
- logger sans exposer token ou secret.

URL recommandée :

```text
/pack/<sha256>/resourcepack.zip
```

et pas :

```text
/download?path=...
```

---

# 20. Glyphes, fonts et GIF

Classes observées :

```text
Glyph
AnimatedGlyph
ShaderAnimatedGlyph
SpriteAnimatedGlyph
ReferenceGlyph
RequiredGlyph
Shift
GlyphTag
ShiftTag
GifDecoder
GifConverter
```

AAEngine doit séparer :

```text
FontDefinition
GlyphDefinition
GlyphAllocator
AnimatedGlyphCompiler
TextTagResolver
```

## Allocation Unicode

L’allocation automatique doit être persistée.

Ne jamais réassigner aléatoirement les codepoints lors d’un reload, sinon les composants stockés dans des inventaires/livres peuvent devenir incohérents.

Créer :

```text
.glyph-map.json
```

avec mapping stable.

---

# 21. Sons

Le format observé permet :

```text
id
sound / sounds
stream
preload
volume
pitch
weight
attenuation_distance
jukebox_playable
```

AAEngine peut conserver ce concept mais utiliser :

```yaml
sounds:
  aaengine:music.example:
    files:
      - aaengine:music/example
    stream: true
    preload: false
    jukebox:
      comparator_output: 15
      length_seconds: 120
```

Le compiler produit automatiquement `sounds.json`.

---

# 22. Custom Armor

Le moteur observé génère des modèles/couches d’équipement et gère :

- armor classique ;
- elytra ;
- wolf armor ;
- llama armor ;
- horse armor.

AAEngine doit utiliser une définition équipement indépendante de l’item :

```yaml
equipment_sets:
  forest:
    humanoid: aaengine:equipment/forest
    humanoid_leggings: aaengine:equipment/forest_leggings
```

Puis :

```yaml
forest_chestplate:
  equipment_set: forest
```

Cela évite de dupliquer la définition du set sur les pièces.

---

# 23. Recipes

Le moteur observé inclut :

```text
shaped
shapeless
furnace
blasting
smoking
campfire
stonecutting
smithing
anvil
brewing
crafter
transmute
```

Architecture :

```text
RecipeLoader
RecipeBuilder
CustomRecipe
RecipeListener
RecipesManager
```

## AAEngine

Créer un codec par type :

```kotlin
interface RecipeCodec<T : AARecipe> {
    val type: Key
    fun decode(node: ConfigNode): T
    fun register(recipe: T, runtime: RecipeRuntime)
}
```

Ne pas mélanger parsing YAML et enregistrement Bukkit.

---

# 24. NMS / multi-version

Versions identifiées :

```text
v1_21_R4
v1_21_R5
v1_21_R8
v1_21_R10
v1_21_R11
v26_R1
v26_R2
v26_R3
```

Fonctions NMS communes visibles :

```text
packet handler
item utils
block utils
player utils
recipe utils
plugin converter
creative inventory
custom entity handler
furniture packet manager
resource pack format
datapack format
block-state correction
chunk access
occlusion checks
```

## AAEngine version bridge

```kotlin
interface AAVersionBridge {
    val minecraftVersion: MinecraftVersion

    val packets: PacketBridge
    val items: ItemBridge
    val blocks: BlockBridge
    val players: PlayerBridge
    val furniture: FurnitureBridge
    val recipes: RecipeBridge

    fun resourcePackFormat(): PackFormat
    fun datapackFormat(): Int
}
```

## Optimisation maintenance

Ne pas dupliquer 95 % des classes pour chaque `R`.

Faire :

```text
engine-version-common
  ↓
version-specific adapters uniquement quand mapping/packet change
```

Les transformations partagées doivent vivre dans `common`.

---

# 25. Packets et Netty

Le JAR contient :

```text
NexoChannelHandler
PacketHandler
ComponentTransformer
ItemTransformer
MerchantTransformer
DialogTransformer
```

Il existe également un setting permettant de désactiver l’injection Netty.

## AAEngine

Principe :

1. utiliser Paper API lorsque possible ;
2. utiliser ProtocolLib-like abstraction si choisi ;
3. injection Netty directe uniquement quand indispensable ;
4. retrait garanti du handler sur disconnect/reload/disable ;
5. aucun travail lourd dans l’event loop Netty.

Ne jamais :

```text
parser YAML
lecture disque
HTTP
compression
construction massive de pack
```

dans un channel handler.

---

# 26. Threading et Folia

Le plugin observé annonce `folia-supported: true` et utilise une lib de scheduling Folia.

AAEngine doit formaliser les contextes d’exécution.

```kotlin
interface EngineScheduler {
    fun global(task: () -> Unit)
    fun region(location: Location, task: () -> Unit)
    fun entity(entity: Entity, task: () -> Unit)
    fun async(task: () -> Unit)
}
```

Règle :

```text
Parsing fichiers          -> async
Hashing/compression       -> async
HTTP/S3                   -> async
Bukkit entity mutation    -> region/entity scheduler
Registry atomic swap      -> global safe point
```

Chaque service doit documenter son thread contract.

---

# 27. Configuration

Les settings observés couvrent notamment :

```text
debug
language
text formatting
glyphs
item updates
furniture
recipes
pack generation
pack validation
pack import
pack obfuscation
pack server
S3
selfhost
pack sending
```

Le problème d’un enum géant de settings est qu’il finit par devenir un registre global de toutes les fonctionnalités.

## AAEngine config typée

```kotlin
data class EngineConfig(
    val core: CoreConfig,
    val items: ItemConfig,
    val furniture: FurnitureConfig,
    val pack: PackConfig,
    val security: SecurityConfig,
    val integrations: IntegrationConfig
)
```

Validation au démarrage.

Si invalide :

```text
fail fast
+ chemin précis
+ valeur reçue
+ valeur attendue
```

---

# 28. Gestion des erreurs

Ne pas avaler les erreurs avec des `runCatching` généraux sur de gros blocs.

AAEngine doit avoir des erreurs structurées :

```kotlin
sealed interface EngineFailure
```

Exemples :

```text
ConfigFailure
ItemParseFailure
PackBuildFailure
PackPublishFailure
VersionUnsupportedFailure
IntegrationFailure
```

Chaque erreur inclut :

```text
code
module
message
cause
source file
config path
suggestion
```

---

# 29. API publique

L’API doit être petite et stable.

```kotlin
interface AAEngine {
    val items: AAItemService
    val blocks: AABlockService
    val furniture: AAFurnitureService
    val recipes: AARecipeService
    val packs: AAPackService
    val mechanics: AAMechanicService
}
```

Provider :

```kotlin
AAEngineProvider.get()
```

Éviter que les plugins externes accèdent directement aux classes internes.

---

# 30. API externe d’items

Le binaire observé permet à un plugin externe d’enregistrer dynamiquement des items et de les retirer par propriétaire.

AAEngine doit conserver cette bonne idée :

```kotlin
val handle = engine.items.register(
    owner = plugin,
    definition = definition
)
```

Puis :

```kotlin
handle.close()
```

Tous les registrations externes doivent être automatiquement supprimés quand le plugin propriétaire est désactivé.

---

# 31. Events

Events observés :

```text
items loaded
pre items load
mechanics registered
custom block place/break/damage/interact/drop
furniture place/break/damage/interact/drop
resource pack pre/post generate
resource pack upload/finished
```

AAEngine doit distinguer :

```text
mutable pre-event
immutable post-event
```

Exemple :

```text
AAItemRegistryPreCommitEvent
AAItemRegistryCommittedEvent
AAPackBuildStartedEvent
AAPackBuildFinishedEvent
```

---

# 32. Commands

Le moteur observé utilise Brigadier/Paper commands.

Commandes identifiées conceptuellement :

```text
main command
item
block info
debug
dye
glyph
glyph info
log dump
multipack
pack
recipes
reload
update
version
```

AAEngine :

```text
/aaengine
  info
  item
  block
  furniture
  glyph
  recipe
  pack
    build
    publish
    send
    validate
  reload
    all
    items
    pack
    recipes
    configs
  debug
  diagnostics
```

Permissions :

```text
aaengine.admin
aaengine.command.item
aaengine.command.reload
aaengine.command.pack
aaengine.command.debug
```

---

# 33. Diagnostics

Créer une commande :

```text
/aaengine diagnostics
```

Rapport :

```text
AAEngine version
Minecraft/Paper version
Java version
Folia oui/non
Version bridge sélectionné
Items chargés
Blocks chargés
Furniture chargés
Recipes chargées
Pack hash
Pack size
Pack build time
Pack publisher
Integrations actives
Async queues
Memory cache sizes
```

Le dump doit automatiquement masquer :

```text
passwords
API keys
S3 secret/access keys
tokens
signed URLs
```

---

# 34. Sécurité — Commands mechanic

Une mechanic permettant d’exécuter des commandes est extrêmement sensible.

Le moteur doit différencier :

```text
PLAYER_COMMAND
CONSOLE_COMMAND
```

et appliquer :

- liste d’actions autorisées ;
- placeholders explicitement échappés ;
- refus des retours ligne ;
- refus des séparateurs injectables ;
- permission explicite ;
- rate limit par item/joueur ;
- possibilité de désactiver entièrement les console commands.

Ne jamais concaténer directement du texte utilisateur dans une commande console.

---

# 35. Sécurité — Config expressions

Spring Expression est présent dans les dépendances runtime observées.

Si AAEngine introduit un langage d’expression, **ne pas exposer SpEL complet** sur des fichiers non fiables.

Préférer un DSL fermé :

```text
player.level >= 10
permission("vip")
world == "spawn"
```

avec AST interne.

Interdire :

```text
reflection
class lookup
method invocation arbitraire
filesystem
runtime execution
```

---

# 36. Optimisations CPU

Priorité élevée :

1. snapshots immutables ;
2. indexes directs par Key ;
3. pas de scan de toutes les entités ;
4. cache des transformations furniture ;
5. cache ItemStack templates puis clone ;
6. cache parsing MiniMessage si texte constant ;
7. resource pack incrémental ;
8. debounce des rebuilds ;
9. batching des packets ;
10. travail disque/compression hors thread serveur.

---

# 37. Optimisations mémoire

Ne pas garder simultanément :

```text
raw YAML
ConfigSection complète
Builder mutable
ItemStack généré
JSON pack intermédiaire
ZIP pack
```

pour chaque item si inutile.

Pipeline recommandé :

```text
YAML
 ↓ parse
Definition immutable
 ↓ compile
Runtime representation
```

Puis libérer l’AST/config brute si le hot reload ciblé n’en a pas besoin.

---

# 38. Performance du registre

Le binaire observé utilise FastUtil, ce qui est pertinent pour certains hot paths.

AAEngine doit cependant choisir ses structures selon l’usage et non systématiquement.

Exemple :

```text
Key -> definition         HashMap / Object2ObjectMap
entityId -> furniture     Int2ObjectMap
chunkLong -> instances    Long2ObjectMap
blockPosLong -> instance  Long2ObjectMap
```

Pour des registres de quelques centaines d’items, la simplicité peut être plus importante que quelques nanosecondes.

---

# 39. Persistence et versioning

Tout PDC AAEngine doit contenir un schema version.

Exemple :

```text
aaengine:item_id = aaengine:forest_sword
aaengine:schema = 3
```

Migrations :

```kotlin
interface ItemMigration {
    val from: Int
    val to: Int
    fun migrate(stack: ItemStack): ItemStack
}
```

La mise à jour d’items ne doit pas écraser automatiquement les données runtime personnalisées d’un autre plugin.

---

# 40. Multipack

Le binaire contient un système de templates de packs activables par joueur avec :

```text
required
default
conflicts_with
menu
TOGGLE
CYCLING
```

AAEngine peut généraliser :

```text
PackProfile
PackLayer
PackVariant
```

Exemple :

```yaml
profiles:
  default:
    layers:
      - core
  pastel:
    layers:
      - core
      - style_pastel
```

Le pack final d’un joueur est identifié par :

```text
hash(sorted layer IDs + versions)
```

et doit être caché.

---

# 41. Conversion depuis d’autres moteurs

Des convertisseurs Oraxen et ItemsAdder sont observés.

AAEngine doit isoler les migrations en outils séparés :

```text
aaengine-migrator-oraxen
aaengine-migrator-itemsadder
```

Ils ne doivent pas faire partie du chemin runtime normal.

Commande :

```text
/aaengine migrate oraxen --dry-run
```

Puis :

```text
report.json
converted/
```

Aucune modification destructive sans backup.

---

# 42. Compatibilité WorldEdit

Le binaire contient une couche dédiée :

```text
CustomBlocksFactory
WorldEdit extent
processor
handlers
listener
```

AAEngine doit représenter chaque custom block avec une forme sérialisable stable permettant :

```text
copy/paste
schematics
undo/redo
```

Il ne faut pas dépendre uniquement du BlockData vanilla si celui-ci ne représente pas l’ID AAEngine.

---

# 43. Compatibilité WorldGuard / protections

Les appels placement/break/interact doivent passer par une abstraction :

```kotlin
interface ProtectionService {
    fun canPlace(player: Player, location: Location): Boolean
    fun canBreak(player: Player, location: Location): Boolean
    fun canInteract(player: Player, location: Location): Boolean
}
```

Puis adaptateurs :

```text
WorldGuard
Lands
HuskClaims
Towny
Factions
PlotSquared
BentoBox
```

Politique par défaut en cas d’erreur d’intégration : configurable, mais documentée.

---

# 44. Test strategy

## Unit tests

Tester :

```text
config codecs
item definitions
mechanic parsing
pack graph
hash/cache
security URL validation
zip extraction
migration
```

## Integration tests

Avec MockBukkit quand possible.

## E2E Paper

Lancer de vrais serveurs de test :

```text
Paper 1.21.x
Folia compatible target
versions modernes supportées
```

Scénarios :

```text
place/break block
place/remove furniture
seat
storage
reload while players online
pack rebuild
pack resend
recipe craft
chunk unload/reload
restart persistence
```

---

# 45. CI recommandée

GitHub Actions :

```text
compile
unit tests
ktlint/detekt
spotbugs si Java
resource pack fixture tests
integration tests
security checks
dependency review
```

Build matrix uniquement sur JDK supportés.

Ne pas compiler chaque bridge NMS contre des dépendances dynamiques non verrouillées.

Utiliser dependency locking.

---

# 46. Templates de développement

## 46.1 Template mechanic

```kotlin
class ExampleMechanicFactory : AAMechanicFactory<ExampleMechanic> {
    override val id = Key.key("aaengine:example")

    override fun parse(ctx: MechanicParseContext): ExampleMechanic {
        return ExampleMechanic(
            itemId = ctx.itemId,
            value = ctx.node.node("value").getInt(0)
        )
    }
}
```

## 46.2 Template service

```kotlin
interface EngineService {
    fun start()
    fun stop()
}
```

## 46.3 Template event-safe runtime

```kotlin
class ExampleRuntime(
    private val scheduler: EngineScheduler,
    private val scope: MechanicScope
) {
    fun start() {
        scope.listen(ExampleListener())
    }
}
```

---

# 47. Template item AAEngine

```yaml
items:
  forest_sword:
    item:
      material: NETHERITE_SWORD
      name: "<green>Forest Sword"
      lore:
        - "<gray>A weapon from the old forest."

    model:
      item_model: aaengine:forest_sword

    mechanics:
      soulbound:
        enabled: true
```

---

# 48. Template furniture AAEngine

```yaml
items:
  chair:
    item:
      material: PAPER
      name: "<gold>Chair"

    model:
      item_model: aaengine:chair

    mechanics:
      furniture:
        placement:
          floor: true
          wall: false
          ceiling: false

        rotation:
          mode: STRICT

        hitbox:
          blocks:
            - [0, 0, 0]

        seats:
          - position: [0.0, 0.0, 0.0]
```

---

# 49. Template custom block AAEngine

```yaml
items:
  basalt_bricks:
    item:
      material: PAPER
      name: "Basalt Bricks"

    mechanics:
      block:
        backend: AUTO
        hardness: 2.0
        blast_resistance: 6.0
        tool:
          type: PICKAXE
          minimum_tier: STONE
```

`AUTO` laisse AAEngine choisir le backend compatible avec la version.

---

# 50. Design des dossiers utilisateur

```text
plugins/AAEngine/
├─ config.yml
├─ security.yml
├─ items/
├─ mechanics/
├─ recipes/
├─ glyphs/
├─ sounds.yml
├─ languages/
├─ pack/
│  ├─ assets/
│  ├─ external/
│  └─ templates/
├─ cache/
├─ logs/
└─ migrations/
```

Les fichiers générés doivent être séparés des fichiers utilisateurs.

---

# 51. Politique de chemins fichiers

Tous les chemins de config doivent être résolus sous un root autorisé.

```kotlin
fun safeResolve(root: Path, relative: String): Path {
    val result = root.resolve(relative).normalize()
    require(result.startsWith(root))
    return result
}
```

Pas d’accès arbitraire au filesystem via YAML.

---

# 52. Observabilité

Chaque build pack doit mesurer :

```text
parse_ms
generate_models_ms
generate_fonts_ms
atlas_ms
validate_ms
compress_ms
upload_ms
total_ms
input_files
output_files
zip_size
cache_hits
cache_misses
```

Commande :

```text
/aaengine debug timings
```

---

# 53. Logging

Catégories :

```text
BOOT
CONFIG
ITEMS
MECHANICS
BLOCKS
FURNITURE
PACK
NETWORK
VERSION
INTEGRATION
SECURITY
```

Mode debug sélectif :

```yaml
debug:
  categories:
    - PACK
    - FURNITURE
```

Évite un unique booléen debug qui spam tout.

---

# 54. Version bridge : sécurité de compatibilité

Au lieu de sélectionner silencieusement le bridge “le plus proche”, AAEngine doit échouer clairement si la version est inconnue et que le fallback n’est pas sûr.

```text
Unsupported Minecraft internals version
Detected: X
Supported: [...]
```

Un mode expérimental peut être opt-in :

```yaml
version_bridge:
  allow_unsafe_fallback: false
```

---

# 55. Compatibilité des données après update

AAEngine doit distinguer :

```text
engine version
config schema version
item schema version
pack cache version
world furniture schema version
```

Une mise à jour du plugin ne doit pas automatiquement impliquer une migration destructrice du monde.

---

# 56. Roadmap de reconstruction

## Phase 0 — Specification

- architecture ;
- formats ;
- API ;
- tests attendus ;
- boundaries clean-room.

## Phase 1 — Core

- bootstrap Paper ;
- config typée ;
- scheduler ;
- registry snapshots ;
- command core ;
- diagnostics.

## Phase 2 — Items

- item parser ;
- PDC ID ;
- ItemStack creation ;
- update/migration ;
- external registration API.

## Phase 3 — Resource pack

- graph compiler ;
- item models ;
- textures ;
- fonts ;
- sounds ;
- validation ;
- incremental cache.

## Phase 4 — Blocks

- backend abstraction ;
- placement/removal ;
- drops ;
- sounds ;
- mining ;
- protections.

## Phase 5 — Furniture

- ItemDisplay base ;
- hitboxes ;
- seats ;
- lights ;
- storage ;
- state ;
- culling.

## Phase 6 — Recipes

- codecs ;
- vanilla types ;
- custom anvil/brewing ;
- reload.

## Phase 7 — Version bridges

- packets ;
- furniture optimisations ;
- creative inventory ;
- special block behavior.

## Phase 8 — Integrations

- WorldGuard ;
- WorldEdit ;
- PlaceholderAPI ;
- ModelEngine ;
- migration tools.

---

# 57. Priorités de sécurité avant release

Checklist bloquante :

```text
[ ] URL allow/deny policy
[ ] SSRF protection
[ ] download size limits
[ ] redirect validation
[ ] zip-slip prevention
[ ] zip-bomb limits
[ ] secrets externalisés
[ ] command mechanic sanitization
[ ] PDC schema version
[ ] packet handler cleanup
[ ] safe async/thread boundaries
[ ] self-host path allowlist
[ ] diagnostics secret redaction
[ ] config path sandbox
[ ] external plugin registrations auto-cleanup
```

---

# 58. Priorités performances avant release

```text
[ ] immutable registry snapshots
[ ] atomic reload
[ ] incremental pack compilation
[ ] pack stage cache
[ ] chunk furniture index
[ ] entity-id lookup map
[ ] no full-world entity scans
[ ] pack compression async
[ ] network calls async
[ ] no heavy work in Netty
[ ] cached ItemStack templates
[ ] debounce pack rebuilds
[ ] viewer culling budget
```

---

# 59. Ce qui doit encore être reverse-engineered précisément

Le présent document reconstruit l’architecture visible, les signatures et les responsabilités majeures.

Pour une compatibilité comportementale plus fine, il reste à documenter précisément :

1. algorithme d’allocation des Custom Model Data ;
2. mapping exact NoteBlock/StringBlock/Chorus states ;
3. protocole exact de furniture packets ;
4. représentation persistence des furniture ;
5. ordre exact de toutes les étapes du PackGenerator ;
6. format complet de chaque mechanic YAML ;
7. comportement des item components modernes ;
8. règles de migration des items ;
9. modèle complet des custom drops ;
10. gestion des recipes fallback ;
11. traitement des packets de merchant/dialog/component ;
12. creative inventory custom ;
13. converter ItemsAdder/Oraxen ;
14. format multipack complet ;
15. algorithme d’obfuscation et stabilité du mapping ;
16. logique exacte de culling furniture ;
17. gestion des bed/mannequin par version ;
18. comportement des tridents custom ;
19. gestion des datapacks ;
20. règles exactes de compatibilité WorldEdit/WorldGuard.

Ces domaines peuvent être analysés classe par classe pour compléter la spec AAEngine.

---

# 60. Définition cible d’AAEngine

AAEngine doit finir comme :

> Un moteur de contenu Paper/Folia modulaire permettant de définir des items, blocs, furniture, glyphes, sons et recettes via configuration, de compiler un resource pack de façon incrémentale, de gérer les différences de versions Minecraft via bridges isolés et d’exposer une API stable aux plugins tiers.

Principes :

```text
API petite
core modulaire
snapshots immutables
reload atomique
Folia-safe
pack compiler incrémental
sécurité réseau/fichiers stricte
bridges versionnés isolés
intégrations optionnelles
observabilité native
```

---

# 61. Arborescence GitHub finale proposée

```text
AAEngine/
├─ README.md
├─ AAENGINE_RECONSTRUCTION.md
├─ SECURITY.md
├─ CONTRIBUTING.md
├─ build.gradle.kts
├─ settings.gradle.kts
├─ gradle.properties
├─ engine-api/
├─ engine-core/
├─ engine-paper/
├─ engine-items/
├─ engine-mechanics/
├─ engine-blocks/
├─ engine-furniture/
├─ engine-recipes/
├─ engine-pack/
├─ engine-fonts/
├─ engine-version-common/
├─ engine-integrations/
├─ engine-testkit/
├─ docs/
│  ├─ architecture.md
│  ├─ security.md
│  ├─ performance.md
│  ├─ configuration.md
│  ├─ pack-pipeline.md
│  └─ reverse-engineering-notes.md
└─ .github/
   ├─ workflows/
   └─ ISSUE_TEMPLATE/
```

---

# 62. Convention clean-room du repository

À ajouter dans le README :

```text
AAEngine is an independent implementation based on documented behavior,
public APIs, runtime observations and clean-room specifications.
The repository must not contain copied or decompiled proprietary source code.
```

Les noms de classes de la référence peuvent rester dans les notes techniques de comparaison, mais le code AAEngine doit utiliser sa propre architecture et son propre naming.

---

# 63. Prochaine étape technique recommandée

Ordre optimal :

```text
1. Créer repository + Gradle multi-module
2. Implémenter engine-api
3. Implémenter EngineSnapshot / reload transactionnel
4. Implémenter config codecs
5. Implémenter ItemRegistry
6. Implémenter Pack DAG + cache
7. Ajouter blocks
8. Ajouter furniture
9. Ajouter recipes
10. Ajouter bridges NMS
```

Le piège serait de commencer directement par les furniture ou les packets NMS. Le registre, le modèle de données et le système de reload doivent être stabilisés avant.

---

# 64. Conclusion

Le JAR observé montre un moteur extrêmement large, avec une quantité importante de logique autour du pack, des mechanics et de la compatibilité Minecraft interne.

La meilleure reconstruction n’est donc pas une décompilation “classe pour classe”.

AAEngine doit conserver :

- le concept de registre ;
- les mechanics extensibles ;
- les abstractions custom blocks/furniture ;
- le compiler resource pack ;
- les bridges de versions ;
- l’API externe ;
- le support Folia.

Mais il doit améliorer :

- isolation des modules ;
- sécurité ;
- reload atomique ;
- testabilité ;
- compilation incrémentale ;
- gestion de secrets ;
- observabilité ;
- cache furniture ;
- maintenance NMS ;
- séparation parsing/runtime.

**AAEngine doit être construit comme une plateforme, pas comme un clone monolithique.**
