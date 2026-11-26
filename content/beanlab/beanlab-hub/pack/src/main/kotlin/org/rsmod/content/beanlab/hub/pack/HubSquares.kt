package org.rsmod.content.beanlab.hub.pack

/**
 * Where the Market Hub sits: arena-local tile (0, 0) is world (5120, 3072), level 0. The hub covers
 * map squares 80..81 x 48..49, outside every real map square and below x 6400, where the engine's
 * instance space starts.
 */
public object HubSquares {
    /** South-west map square of the hub (x, z). */
    public const val FIRST_X: Int = 80
    public const val FIRST_Z: Int = 48

    public const val MIN_X: Int = FIRST_X * 64
    public const val MIN_Z: Int = FIRST_Z * 64

    /** The hub spans this many map squares in x and in z. */
    public const val SQUARE_SPAN: Int = 2

    /** `[x]_[z]` map square keys covered by the hub (at most 128 x 128 tiles). */
    public val SQUARES: List<String> = listOf("80_48", "80_49", "81_48", "81_49")

    /** [SQUARES] as (x, z) pairs. */
    public fun squares(): List<Pair<Int, Int>> =
        SQUARES.map { square -> square.split("_").let { it[0].toInt() to it[1].toInt() } }

    /** The layout bundled with this module, next to this class. */
    public const val RESOURCE: String = "hub-layout.json"

    public fun bundledText(): String {
        val stream =
            HubSquares::class.java.getResourceAsStream(RESOURCE)
                ?: error("Missing bundled hub layout: $RESOURCE")
        return stream.use { String(it.readAllBytes(), Charsets.UTF_8) }
    }
}
