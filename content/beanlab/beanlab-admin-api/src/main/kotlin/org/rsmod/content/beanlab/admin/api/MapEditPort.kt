package org.rsmod.content.beanlab.admin.api

/** Live map edits. The floor is not part of this: it stays in the packed cache. */
public interface MapEditPort {
    public fun editLoc(
        op: String,
        x: Int,
        z: Int,
        level: Int,
        name: String,
        shape: Int,
        angle: Int,
    ): String

    public fun editNpc(op: String, x: Int, z: Int, level: Int, name: String): String
}

/** Used when the router is constructed without a running world, so tests can ignore the map. */
public class UnavailableMapEditPort : MapEditPort {
    override fun editLoc(
        op: String,
        x: Int,
        z: Int,
        level: Int,
        name: String,
        shape: Int,
        angle: Int,
    ): String = throw ApiError(503, "Map editing is not available on this server.")

    override fun editNpc(op: String, x: Int, z: Int, level: Int, name: String): String =
        throw ApiError(503, "Map editing is not available on this server.")
}
