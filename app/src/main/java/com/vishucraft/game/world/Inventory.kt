package com.vishucraft.game.world

import java.io.DataInputStream
import java.io.DataOutputStream

/** A stack of blocks or items. [damage] counts uses for tools. */
class ItemStack(val id: Int, var count: Int = 1, var damage: Int = 0) {
    fun copy() = ItemStack(id, count, damage)
    val maxStack get() = Items.maxStack(id)
}

/** 36 slots (0..8 are the hotbar) plus 4 armor slots. */
class Inventory {
    companion object {
        const val SIZE = 36

        fun writeStack(d: DataOutputStream, s: ItemStack?) {
            if (s == null || s.count <= 0) { d.writeInt(-1); return }
            d.writeInt(s.id); d.writeInt(s.count); d.writeInt(s.damage)
        }

        fun readStack(d: DataInputStream): ItemStack? {
            val id = d.readInt()
            if (id < 0) return null
            val count = d.readInt(); val damage = d.readInt()
            return if (Items.isValidSlot(id) || id == Blocks.WATER) ItemStack(id, count, damage) else null
        }
    }

    val slots = arrayOfNulls<ItemStack>(SIZE)
    val armor = arrayOfNulls<ItemStack>(4)

    /** Adds items, filling existing stacks first. Returns how many did not fit. */
    fun add(id: Int, count: Int, damage: Int = 0): Int {
        var left = count
        val max = Items.maxStack(id)
        if (max > 1) {
            for (s in slots) {
                if (left == 0) break
                if (s != null && s.id == id && s.count < max) {
                    val n = minOf(left, max - s.count); s.count += n; left -= n
                }
            }
        }
        for (i in slots.indices) {
            if (left == 0) break
            if (slots[i] == null) {
                val n = minOf(left, max)
                slots[i] = ItemStack(id, n, damage); left -= n
            }
        }
        return left
    }

    fun count(id: Int): Int = slots.sumOf { if (it != null && it.id == id) it.count else 0 }

    /** Removes [n] items of [id] if available (all or nothing). */
    fun remove(id: Int, n: Int): Boolean {
        if (count(id) < n) return false
        var left = n
        for (i in slots.indices.reversed()) {
            val s = slots[i] ?: continue
            if (s.id != id) continue
            val take = minOf(left, s.count)
            s.count -= take; left -= take
            if (s.count <= 0) slots[i] = null
            if (left == 0) break
        }
        return true
    }

    fun armorPoints(): Int = armor.sumOf { s -> s?.let { Items[it.id]?.armorPoints } ?: 0 }

    fun clear() { slots.fill(null); armor.fill(null) }

    fun write(d: DataOutputStream) {
        for (s in slots) writeStack(d, s)
        for (s in armor) writeStack(d, s)
    }

    fun read(d: DataInputStream) {
        for (i in slots.indices) slots[i] = readStack(d)
        for (i in armor.indices) armor[i] = readStack(d)
    }
}
