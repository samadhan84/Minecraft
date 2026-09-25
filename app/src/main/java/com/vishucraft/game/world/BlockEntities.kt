package com.vishucraft.game.world

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Extra state for chests and furnaces, keyed by packed block position. */
sealed class BlockEntity

class ChestEntity : BlockEntity() {
    val slots = arrayOfNulls<ItemStack>(27)
}

class FurnaceEntity : BlockEntity() {
    var input: ItemStack? = null
    var fuel: ItemStack? = null
    var output: ItemStack? = null
    var burnLeft = 0f
    var burnTotal = 0f
    var progress = 0f

    companion object { const val SMELT_SECONDS = 4f }

    /** Advances smelting. Returns true while burning. */
    fun tick(dt: Float): Boolean {
        val result = input?.let { Recipes.smelting[it.id] }
        val out = output
        val canOutput = result != null && (out == null || (out.id == result && out.count < Items.maxStack(result)))
        if (burnLeft <= 0f && canOutput) {
            val f = fuel
            if (f != null && Items.fuel(f.id) > 0f) {
                burnTotal = Items.fuel(f.id); burnLeft = burnTotal
                f.count--; if (f.count <= 0) fuel = null
            }
        }
        if (burnLeft > 0f) {
            burnLeft -= dt
            if (canOutput) {
                progress += dt
                if (progress >= SMELT_SECONDS) {
                    progress = 0f
                    val inp = input!!
                    inp.count--; if (inp.count <= 0) input = null
                    if (out == null) output = ItemStack(result!!, 1) else out.count++
                }
            } else progress = 0f
            return true
        }
        progress = maxOf(0f, progress - dt * 2)
        return false
    }

    fun contents(): List<ItemStack> = listOfNotNull(input, fuel, output)
}

class BlockEntities {
    val map = ConcurrentHashMap<Long, BlockEntity>()

    fun get(x: Int, y: Int, z: Int) = map[RedstoneIds.pack(x, y, z)]
    fun chest(x: Int, y: Int, z: Int) = map.getOrPut(RedstoneIds.pack(x, y, z)) { ChestEntity() } as? ChestEntity
    fun furnace(x: Int, y: Int, z: Int) = map.getOrPut(RedstoneIds.pack(x, y, z)) { FurnaceEntity() } as? FurnaceEntity
    fun remove(x: Int, y: Int, z: Int) = map.remove(RedstoneIds.pack(x, y, z))

    fun write(file: File) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { d ->
            d.writeInt(1)
            val entries = map.entries.toList()
            d.writeInt(entries.size)
            for ((pos, e) in entries) {
                d.writeLong(pos)
                when (e) {
                    is ChestEntity -> { d.writeByte(0); for (s in e.slots) Inventory.writeStack(d, s) }
                    is FurnaceEntity -> {
                        d.writeByte(1)
                        Inventory.writeStack(d, e.input); Inventory.writeStack(d, e.fuel); Inventory.writeStack(d, e.output)
                        d.writeFloat(e.burnLeft); d.writeFloat(e.burnTotal); d.writeFloat(e.progress)
                    }
                }
            }
        }
        tmp.renameTo(file)
    }

    fun read(file: File) {
        if (!file.exists()) return
        try {
            DataInputStream(file.inputStream().buffered()).use { d ->
                if (d.readInt() != 1) return
                repeat(d.readInt()) {
                    val pos = d.readLong()
                    when (d.readByte().toInt()) {
                        0 -> map[pos] = ChestEntity().also { c -> for (i in c.slots.indices) c.slots[i] = Inventory.readStack(d) }
                        else -> map[pos] = FurnaceEntity().also { f ->
                            f.input = Inventory.readStack(d); f.fuel = Inventory.readStack(d); f.output = Inventory.readStack(d)
                            f.burnLeft = d.readFloat(); f.burnTotal = d.readFloat(); f.progress = d.readFloat()
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }
}
