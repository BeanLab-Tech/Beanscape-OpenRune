package org.rsmod.content.beanlab.admin.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The owner's map edits, stored as a JSON file so they come back after a restart. An add that is
 * later removed is dropped. A removal of something that was never added is kept, so a cache object
 * or NPC stays gone.
 */
public class MapEditLog(public val path: Path) {
    public val locs: MutableList<LocEdit> = mutableListOf()
    public val npcs: MutableList<NpcEdit> = mutableListOf()

    public fun load() {
        locs.clear()
        npcs.clear()
        if (!Files.isRegularFile(path)) return
        val file = mapper.readValue<File>(Files.readString(path))
        locs += file.locs
        npcs += file.npcs
    }

    public fun save() {
        val parent = path.parent
        if (parent != null) Files.createDirectories(parent)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(tmp, mapper.writeValueAsString(File(locs.toList(), npcs.toList())))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** @return true when a previous add of the same object was removed instead of recording a delete */
    public fun recordLoc(edit: LocEdit): Boolean {
        val same = { other: LocEdit ->
            other.x == edit.x &&
                other.z == edit.z &&
                other.level == edit.level &&
                other.shape == edit.shape &&
                other.name == edit.name
        }
        if (edit.op == OP_REMOVE) {
            val removedAdd = locs.removeAll { same(it) && it.op == OP_ADD }
            if (removedAdd) return true
            locs.removeAll { same(it) && it.op == OP_REMOVE }
            locs += edit
            return false
        }
        locs.removeAll(same)
        locs += edit.copy(op = OP_ADD)
        return false
    }

    /** @return true when a previous add of the same NPC was removed instead of recording a delete */
    public fun recordNpc(edit: NpcEdit): Boolean {
        val same = { other: NpcEdit ->
            other.x == edit.x &&
                other.z == edit.z &&
                other.level == edit.level &&
                other.name == edit.name
        }
        if (edit.op == OP_REMOVE) {
            val removedAdd = npcs.removeAll { same(it) && it.op == OP_ADD }
            if (removedAdd) return true
            npcs.removeAll { same(it) && it.op == OP_REMOVE }
            npcs += edit
            return false
        }
        npcs.removeAll(same)
        npcs += edit.copy(op = OP_ADD)
        return false
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public data class File(val locs: List<LocEdit> = emptyList(), val npcs: List<NpcEdit> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    public data class LocEdit(
        val op: String,
        val x: Int,
        val z: Int,
        val level: Int,
        val name: String,
        val shape: Int,
        val angle: Int,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    public data class NpcEdit(
        val op: String,
        val x: Int,
        val z: Int,
        val level: Int,
        val name: String,
    )

    public companion object {
        public const val OP_ADD: String = "add"
        public const val OP_REMOVE: String = "remove"

        private val mapper =
            ObjectMapper().registerModule(KotlinModule.Builder().build())
    }
}
