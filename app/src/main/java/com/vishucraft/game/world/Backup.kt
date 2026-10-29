package com.vishucraft.game.world

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Saves every world into one zip file, and brings worlds back from such a file. */
object Backup {
    /** Writes all worlds under [root] (each a folder with level.dat) into [out]. Returns how many worlds. */
    fun write(root: File, out: OutputStream): Int {
        var worlds = 0
        ZipOutputStream(out.buffered()).use { zip ->
            for (w in root.listFiles().orEmpty().filter { File(it, "level.dat").exists() }.sortedBy { it.name }) {
                worlds++
                w.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { f ->
                    zip.putNextEntry(ZipEntry(w.name + "/" + f.relativeTo(w).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        return worlds
    }

    /**
     * Unpacks worlds from a backup into [root]. A world whose folder already exists is restored next to it
     * under a new folder name, so nothing is overwritten. Returns the names of the restored worlds.
     */
    fun restore(input: InputStream, root: File): List<String> {
        root.mkdirs()
        val rename = HashMap<String, String>()
        val restored = ArrayList<String>()
        val base = root.canonicalFile
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val parts = e.name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
                // Ignore anything unexpected: must be world/<file...>, with no ".." tricks.
                if (parts.size < 2 || parts.any { it == ".." || it == "." }) continue
                val folder = rename.getOrPut(parts[0]) {
                    var name = parts[0]; var n = 2
                    while (File(root, name).exists()) name = "${parts[0]}_restored${if (n > 2) n.toString() else ""}".also { n++ }
                    restored.add(name)
                    name
                }
                val target = File(File(root, folder), parts.drop(1).joinToString("/")).canonicalFile
                if (!target.path.startsWith(base.path + File.separator)) continue
                target.parentFile?.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
            }
        }
        // Only keep folders that really are worlds.
        return restored.filter { File(File(root, it), "level.dat").exists().also { ok -> if (!ok) File(root, it).deleteRecursively() } }
    }
}
