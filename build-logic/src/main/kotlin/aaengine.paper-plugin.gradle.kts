import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.accessors.dm.LibrariesForLibs
import java.util.zip.ZipFile

plugins {
    id("aaengine.kotlin-library")
    id("com.gradleup.shadow")
}

val libs = the<LibrariesForLibs>()

dependencies {
    compileOnly(libs.paper.api)
}

tasks.named<ProcessResources>("processResources") {
    val projectVersion = project.version.toString()
    inputs.property("version", projectVersion)
    filesMatching("paper-plugin.yml") {
        expand("version" to projectVersion)
    }
}

// Jar du plugin : modules internes + bibliothèques runtime embarqués. Paper, Bukkit et Adventure sont
// fournis par le serveur et ne doivent jamais être embarqués. Les bibliothèques tierces sont relocalisées
// pour ne pas entrer en conflit avec celles du serveur ou d'autres plugins ; Kotlin reste à son nom
// (chargeur de classes isolé par plugin, et l'API publique est appelée depuis Java).
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    relocate("org.spongepowered.configurate", "dev.aaengine.libs.configurate")
    relocate("io.leangen.geantyref", "dev.aaengine.libs.geantyref")
    relocate("net.kyori.option", "dev.aaengine.libs.option")
    relocate("org.yaml.snakeyaml", "dev.aaengine.libs.snakeyaml")
    relocate("kotlinx.coroutines", "dev.aaengine.libs.coroutines")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/maven/**")
    exclude("module-info.class", "META-INF/versions/*/module-info.class")
    mergeServiceFiles()
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("plain")
}

tasks.named("assemble") {
    dependsOn("shadowJar")
}

// Contrôle du jar final : modules internes et Kotlin présents, aucune classe du serveur embarquée,
// bibliothèques tierces bien relocalisées.
val verifyPluginJar by tasks.registering {
    group = "verification"
    description = "Vérifie le contenu du jar du plugin AAEngine."
    val jarFile = tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile }
    inputs.file(jarFile)
    doLast {
        ZipFile(jarFile.get().asFile).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            val forbidden = listOf(
                "org/bukkit/", "io/papermc/", "com/destroystokyo/", "net/kyori/", "net/md_5/",
                "org/spongepowered/configurate/", "org/yaml/snakeyaml/", "kotlinx/coroutines/",
            )
            val bad = names.filter { n -> forbidden.any { n.startsWith(it) } }
            check(bad.isEmpty()) { "Classes interdites dans le jar du plugin : ${bad.take(10)}" }
            val required = listOf(
                "paper-plugin.yml",
                "dev/aaengine/paper/AAEnginePlugin.class",
                "dev/aaengine/api/AAEngineProvider.class",
                "dev/aaengine/core/engine/DefaultAAEngine.class",
                "kotlin/Unit.class",
            )
            val missing = required.filter { it !in names }
            check(missing.isEmpty()) { "Entrées manquantes dans le jar du plugin : $missing" }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyPluginJar)
}
