package dev.aaengine.pack

import dev.aaengine.core.io.BoundedFiles
import dev.aaengine.core.io.SafePaths
import dev.aaengine.pack.json.Json
import dev.aaengine.pack.json.JsonArray
import dev.aaengine.pack.json.JsonException
import dev.aaengine.pack.json.JsonNumber
import dev.aaengine.pack.json.JsonObject
import dev.aaengine.pack.json.JsonString
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

/**
 * Cache incrémental (§14) : `cache/pack/manifest.json` contient l'empreinte SHA-256 de toutes les entrées
 * (chemin, origine et contenu de chaque source, entrées issues des items, version du compilateur, version des
 * règles, bornes et format), les SHA-256 des sorties et les empreintes du zip. Si l'empreinte n'a pas changé
 * et que le zip sur disque correspond toujours (taille, SHA-1 et SHA-256 recalculés), rien n'est reconstruit.
 *
 * Le manifeste n'est jamais cru seul : les empreintes renvoyées sont toujours celles des octets réels du zip.
 */
class PackCache(
    private val layout: PackLayout,
) {
    /** Contenu utile du manifeste. */
    class Manifest(
        val fingerprint: String,
        val size: Long,
        val sha1: String,
        val sha256: String,
        val fileCount: Int,
    )

    /** Pack annoncé aux joueurs (dernier `/aaengine pack publish`). */
    class Published(
        val size: Long,
        val sha1: String,
        val sha256: String,
    )

    /** Le zip écrit dépasse la taille acceptée par les clients : il n'est pas installé. */
    class PackTooLargeException(
        val size: Long,
    ) : IOException("Pack trop volumineux ($size octets)")

    /** Empreinte des entrées d'une compilation. */
    fun fingerprint(
        sources: List<PackFile>,
        input: PackInput,
        limits: PackLimits,
    ): String {
        val md = MessageDigest.getInstance("SHA-256")

        fun put(s: String) {
            md.update(s.toByteArray(Charsets.UTF_8))
            md.update(0)
        }
        put(PackFormat.COMPILER_VERSION)
        put(PackFormat.RULES_VERSION)
        put(limits.toString())
        put("${PackFormat.RESOURCE_MAJOR}.${PackFormat.RESOURCE_MINOR}")
        put(Json.write(PackFormat.packMcmeta()))
        put(input.canonical())
        for (f in sources) {
            put(f.origin)
            put(f.path)
            put(f.sha256)
        }
        return Hashes.hex(md.digest())
    }

    /** Manifeste lu, ou `null` s'il est absent, illisible ou d'un autre schéma (le build repart de zéro). */
    fun readManifest(): Manifest? {
        val root = readJson { layout.manifestFile } ?: return null
        if ((root["schema"] as? JsonNumber)?.text != SCHEMA.toString()) return null
        val zip = root["zip"] as? JsonObject ?: return null
        return Manifest(
            fingerprint = root.string("fingerprint") ?: return null,
            size = (zip["size"] as? JsonNumber)?.text?.toLongOrNull() ?: return null,
            sha1 = zip.string("sha1")?.takeIf { HEX40.matches(it) } ?: return null,
            sha256 = zip.string("sha256")?.takeIf { HEX64.matches(it) } ?: return null,
            fileCount = (root["outputs"] as? JsonObject)?.members?.size ?: 0,
        )
    }

    /**
     * Pack en cache valide pour [fingerprint] (`null` pour ignorer l'empreinte). Taille, SHA-1 et SHA-256 sont
     * recalculés en une passe sur le zip et doivent tous correspondre au manifeste ; les valeurs renvoyées
     * sont celles calculées.
     */
    fun current(fingerprint: String?): BuiltPack? {
        val manifest = readManifest() ?: return null
        if (fingerprint != null && manifest.fingerprint != fingerprint) return null
        val zip =
            try {
                layout.zipFile
            } catch (_: SafePaths.UnsafePathException) {
                return null
            }
        val actual = hashFile(zip) ?: return null
        if (actual.size != manifest.size || actual.sha256 != manifest.sha256 || actual.sha1 != manifest.sha1) return null
        return BuiltPack(zip, actual.size, actual.sha1, actual.sha256, manifest.fileCount)
    }

    /** Écrit le manifeste (remplacement atomique). */
    fun writeManifest(
        fingerprint: String,
        sources: List<PackFile>,
        input: PackInput,
        outputs: Collection<PackFile>,
        pack: BuiltPack,
    ) {
        val inputs =
            sources.sortedWith(compareBy({ it.origin }, { it.path })).map {
                "${it.origin}!${it.path}" to
                    Json.str("sha256:${it.sha256}")
            }
        val json =
            Json.obj(
                "schema" to Json.num(SCHEMA),
                "compiler" to Json.str(PackFormat.COMPILER_VERSION),
                "rules" to Json.str(PackFormat.RULES_VERSION),
                "format" to JsonArray(listOf(Json.num(PackFormat.RESOURCE_MAJOR), Json.num(PackFormat.RESOURCE_MINOR))),
                "fingerprint" to Json.str(fingerprint),
                "inputs" to JsonObject(linkedMapOf(*inputs.toTypedArray())),
                "items" to Json.str("sha256:" + Hashes.sha256(input.canonical().toByteArray(Charsets.UTF_8))),
                "outputs" to
                    JsonObject(
                        linkedMapOf(*outputs.sortedBy { it.path }.map { it.path to Json.str("sha256:${it.sha256}") }.toTypedArray()),
                    ),
                "zip" to
                    Json.obj(
                        "file" to JsonString(PackLayout.ZIP_NAME),
                        "size" to JsonNumber(pack.size.toString()),
                        "sha1" to Json.str(pack.sha1),
                        "sha256" to Json.str(pack.sha256),
                    ),
            )
        val bytes = Json.writeBytes(json)
        atomicWrite(layout.manifestFile, { it.write(bytes) })
    }

    /** Pack annoncé lu depuis `published.json`, `null` si absent ou invalide. */
    fun readPublished(): Published? {
        val root = readJson { layout.publishedFile } ?: return null
        if ((root["schema"] as? JsonNumber)?.text != SCHEMA.toString()) return null
        return Published(
            size = (root["size"] as? JsonNumber)?.text?.toLongOrNull() ?: return null,
            sha1 = root.string("sha1")?.takeIf { HEX40.matches(it) } ?: return null,
            sha256 = root.string("sha256")?.takeIf { HEX64.matches(it) } ?: return null,
        )
    }

    /** Enregistre [pack] comme pack annoncé (remplacement atomique). */
    fun writePublished(pack: BuiltPack): Published {
        val json =
            Json.obj(
                "schema" to Json.num(SCHEMA),
                "size" to JsonNumber(pack.size.toString()),
                "sha1" to Json.str(pack.sha1),
                "sha256" to Json.str(pack.sha256),
            )
        val bytes = Json.writeBytes(json)
        atomicWrite(layout.publishedFile, { it.write(bytes) })
        return Published(pack.size, pack.sha1, pack.sha256)
    }

    /**
     * Écrit [target] via un fichier temporaire du même dossier (`CREATE_NEW`, sans suivre de lien), le force
     * sur disque, appelle [verify] (qui peut refuser en levant une exception : la cible n'est alors jamais
     * touchée) puis remplace la cible atomiquement. Le temporaire est supprimé dans tous les cas.
     */
    fun <T> atomicWrite(
        target: Path,
        writer: (OutputStream) -> T,
        verify: (T) -> Unit = {},
    ): T {
        val dir = target.parent
        Files.createDirectories(dir)
        SafePaths.requireNoSymlink(layout.root, dir)
        SafePaths.requireRealPathUnder(layout.root, dir)
        val tmp = dir.resolve(".${target.fileName}.${UUID.randomUUID()}$TMP_SUFFIX")
        try {
            val result =
                FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                    val r = writer(KeepOpen(Channels.newOutputStream(channel)))
                    channel.force(true)
                    r
                }
            verify(result)
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            return result
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /**
     * Supprime les temporaires orphelins (`.<nom>.<uuid>.tmp`, fichiers ordinaires uniquement) laissés par un
     * arrêt brutal. Renvoie le nombre de fichiers supprimés ; aucune erreur n'est propagée.
     */
    fun cleanTemporaries(): Int {
        val dir =
            try {
                layout.cacheDir
            } catch (_: SafePaths.UnsafePathException) {
                return 0
            }
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || SafePaths.isLinkLike(dir)) return 0
        var removed = 0
        try {
            Files.newDirectoryStream(dir).use { stream ->
                for (p in stream) {
                    if (!ORPHAN.matches(p.fileName.toString())) continue
                    val attrs = Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    if (!attrs.isRegularFile || attrs.isSymbolicLink || attrs.isOther) continue
                    if (Files.deleteIfExists(p)) removed++
                }
            }
        } catch (_: IOException) {
            // Nettoyage opportuniste : un échec ne bloque pas le build.
        }
        return removed
    }

    private fun readJson(file: () -> Path): JsonObject? =
        try {
            val path = file()
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                null
            } else {
                Json.parse(BoundedFiles.readUtf8(path, MAX_MANIFEST_BYTES)) as? JsonObject
            }
        } catch (_: IOException) {
            null
        } catch (_: JsonException) {
            null
        } catch (_: SecurityException) {
            null
        }

    private class FileDigest(
        val size: Long,
        val sha1: String,
        val sha256: String,
    )

    /** Taille, SHA-1 et SHA-256 de [file] en une seule lecture (sans suivre de lien), `null` si illisible. */
    private fun hashFile(file: Path): FileDigest? {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null
        return try {
            val sha1 = MessageDigest.getInstance("SHA-1")
            val sha256 = MessageDigest.getInstance("SHA-256")
            var size = 0L
            Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sha1.update(buf, 0, n)
                    sha256.update(buf, 0, n)
                    size += n
                }
            }
            FileDigest(size, Hashes.hex(sha1.digest()), Hashes.hex(sha256.digest()))
        } catch (_: IOException) {
            null
        }
    }

    /** Le canal est fermé par [atomicWrite] après `force`, pas par l'écrivain. */
    private class KeepOpen(
        out: OutputStream,
    ) : FilterOutputStream(out) {
        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) = out.write(b, off, len)

        override fun close() = out.flush()
    }

    companion object {
        const val SCHEMA: Int = 1
        private const val TMP_SUFFIX = ".tmp"
        private const val MAX_MANIFEST_BYTES = 32 * 1024 * 1024
        private const val BUFFER = 64 * 1024
        private val HEX40 = Regex("[0-9a-f]{40}")
        private val HEX64 = Regex("[0-9a-f]{64}")
        private val ORPHAN = Regex("""\.[a-z0-9._-]+\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.tmp""")
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
