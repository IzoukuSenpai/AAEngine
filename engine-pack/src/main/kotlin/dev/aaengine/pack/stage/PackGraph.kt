package dev.aaengine.pack.stage

/**
 * Étape du compilateur de pack (§13). Une étape ne lit que ce que ses [dependencies] ont produit dans le
 * [PackBuildContext] ; l'ordre d'exécution est déduit du graphe, jamais codé en dur.
 */
interface PackStage {
    val id: String
    val dependencies: Set<String>

    fun execute(ctx: PackBuildContext)
}

/** Graphe invalide : dépendance inconnue, identifiant en double ou cycle. */
class PackGraphException(
    message: String,
) : IllegalStateException(message)

/**
 * Graphe d'étapes : ordre topologique calculé à la construction (Kahn ; à égalité, ordre de déclaration,
 * donc déterministe). Un cycle, une dépendance inconnue ou un identifiant en double lèvent [PackGraphException].
 */
class PackGraph(
    stages: List<PackStage>,
) {
    val order: List<PackStage> = sort(stages)

    /** Exécute les étapes dans l'ordre ; s'arrête dès que [PackBuildContext.halted] est vrai. */
    fun run(ctx: PackBuildContext) {
        for (stage in order) {
            if (ctx.halted) return
            stage.execute(ctx)
        }
    }

    private companion object {
        fun sort(stages: List<PackStage>): List<PackStage> {
            val byId = LinkedHashMap<String, PackStage>()
            for (s in stages) {
                if (byId.put(s.id, s) != null) throw PackGraphException("Étape en double : ${s.id}")
            }
            for (s in stages) {
                for (d in s.dependencies) {
                    if (d !in byId) throw PackGraphException("Dépendance inconnue de ${s.id} : $d")
                }
            }
            val remaining = LinkedHashMap<String, MutableSet<String>>()
            for (s in stages) remaining[s.id] = s.dependencies.toMutableSet()
            val result = ArrayList<PackStage>(stages.size)
            while (remaining.isNotEmpty()) {
                val ready = remaining.entries.firstOrNull { it.value.isEmpty() }?.key
                if (ready == null) {
                    throw PackGraphException("Cycle entre les étapes : ${remaining.keys.sorted().joinToString(", ")}")
                }
                remaining.remove(ready)
                for (deps in remaining.values) deps.remove(ready)
                result.add(byId.getValue(ready))
            }
            return result
        }
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
