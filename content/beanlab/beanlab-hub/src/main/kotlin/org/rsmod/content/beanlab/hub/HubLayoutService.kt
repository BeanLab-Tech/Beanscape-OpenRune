package org.rsmod.content.beanlab.hub

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.rsmod.content.beanlab.hub.pack.HubMap
import org.rsmod.content.beanlab.hub.pack.HubMapException

/** The layout text an editor should start from, and where it came from. */
public class HubLayoutText(public val text: String, public val source: String)

/**
 * Public door to the Market Hub layout for editors (the admin API, the web editor). It keeps the
 * parser internal: callers get the same problem messages [HubMap.parse] produces, and writes go to
 * the override file that [HubMapLoader] reads at startup.
 *
 * Saved files are written in the canonical format of [HubMap.toJson], so they always read back to
 * an equal map. Walls, objects and NPCs take effect on the next server restart; floors are read
 * from the packed cache, not from here.
 */
public class HubLayoutService(private val path: Path = defaultPath()) {
    /** Where the override file lives. */
    public val overridePath: Path
        get() = path

    /** Every problem with [text], or an empty list when it is a valid layout. */
    public fun validate(text: String): List<String> =
        try {
            HubMap.parse(text)
            emptyList()
        } catch (e: HubMapException) {
            e.problems
        }

    /** The override file when it exists, otherwise the layout bundled in the jar. */
    public fun read(): HubLayoutText {
        if (Files.isRegularFile(path)) {
            return HubLayoutText(Files.readString(path), "override")
        }
        return HubLayoutText(HubMapLoader.bundledText(), "bundled")
    }

    /**
     * Validates [text] and, when it is clean, replaces the override file atomically. An existing
     * override is first copied to `hub-layout.json.<timestamp>.bak`. Returns the problems found;
     * nothing is written unless the list is empty.
     */
    public fun write(text: String): List<String> {
        val map =
            try {
                HubMap.parse(text)
            } catch (e: HubMapException) {
                return e.problems
            }
        val parent = path.toAbsolutePath().parent
        Files.createDirectories(parent)
        if (Files.isRegularFile(path)) {
            val stamp = LocalDateTime.now().format(STAMP)
            Files.copy(
                path,
                parent.resolve("${path.fileName}.$stamp.bak"),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        val temp = Files.createTempFile(parent, "hub-layout", ".tmp")
        try {
            Files.writeString(temp, map.toJson())
            try {
                Files.move(
                    temp,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
        return emptyList()
    }

    public companion object {
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /** `HUB_LAYOUT_FILE`, or `.data/hub-layout.json`: the same file [HubMapLoader] reads. */
        public fun defaultPath(): Path =
            Path.of(System.getenv(HubMapLoader.OVERRIDE_ENV) ?: HubMapLoader.DEFAULT_OVERRIDE)
    }
}
