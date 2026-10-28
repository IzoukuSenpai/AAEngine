package dev.aaengine.pack

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Fabrique de zips synthétiques octet par octet, pour construire des archives malveillantes en test. */
class RawZip {
    class E(
        val name: String,
        val data: ByteArray,
        var method: Int = 8,
        var flags: Int = 0x0800,
        var localName: String = name,
        var crc: Long? = null,
        var declaredSize: Long? = null,
        var madeBy: Int = 20,
        var external: Long = 0,
        var compressed: ByteArray? = null,
        /** Octets compressés annoncés en plus des octets réellement écrits (chevauchement). */
        var claimExtra: Int = 0,
        var localOffset: Int? = null,
    )

    fun build(
        entries: List<E>,
        trailing: ByteArray = ByteArray(0),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        val meta = ArrayList<LongArray>()
        for (e in entries) {
            val payload = e.compressed ?: if (e.method == 8) deflate(e.data) else e.data
            val crc = e.crc ?: CRC32().apply { update(e.data) }.value
            val usize = e.declaredSize ?: if (e.method == 0) payload.size.toLong() + e.claimExtra else e.data.size.toLong()
            val csize = payload.size.toLong() + e.claimExtra
            offsets.add(out.size())
            meta.add(longArrayOf(crc, csize, usize))
            val local = e.localName.toByteArray(if (e.flags and 0x0800 != 0) Charsets.UTF_8 else Charsets.ISO_8859_1)
            u32(out, 0x04034b50)
            u16(out, 20)
            u16(out, e.flags)
            u16(out, e.method)
            u16(out, 0)
            u16(out, 0x21)
            u32(out, crc)
            u32(out, csize)
            u32(out, usize)
            u16(out, local.size)
            u16(out, 0)
            out.write(local)
            out.write(payload)
        }
        val cdStart = out.size()
        entries.forEachIndexed { i, e ->
            val name = e.name.toByteArray(if (e.flags and 0x0800 != 0) Charsets.UTF_8 else Charsets.ISO_8859_1)
            u32(out, 0x02014b50)
            u16(out, e.madeBy)
            u16(out, 20)
            u16(out, e.flags)
            u16(out, e.method)
            u16(out, 0)
            u16(out, 0x21)
            u32(out, meta[i][0])
            u32(out, meta[i][1])
            u32(out, meta[i][2])
            u16(out, name.size)
            u16(out, 0)
            u16(out, 0)
            u16(out, 0)
            u16(out, 0)
            u32(out, e.external)
            u32(out, (e.localOffset ?: offsets[i]).toLong())
            out.write(name)
        }
        val cdSize = out.size() - cdStart
        u32(out, 0x06054b50)
        u16(out, 0)
        u16(out, 0)
        u16(out, entries.size)
        u16(out, entries.size)
        u32(out, cdSize.toLong())
        u32(out, cdStart.toLong())
        u16(out, 0)
        out.write(trailing)
        return out.toByteArray()
    }

    companion object {
        fun deflate(data: ByteArray): ByteArray {
            val d = Deflater(9, true)
            d.setInput(data)
            d.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            d.end()
            return out.toByteArray()
        }

        private fun u16(
            out: ByteArrayOutputStream,
            v: Int,
        ) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
        }

        private fun u32(
            out: ByteArrayOutputStream,
            v: Long,
        ) {
            u16(out, (v and 0xFFFF).toInt())
            u16(out, ((v ushr 16) and 0xFFFF).toInt())
        }

        private fun u32(
            out: ByteArrayOutputStream,
            v: Int,
        ) = u32(out, v.toLong() and 0xFFFFFFFFL)
    }
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
