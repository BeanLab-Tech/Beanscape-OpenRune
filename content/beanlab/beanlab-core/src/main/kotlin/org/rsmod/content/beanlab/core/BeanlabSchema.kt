package org.rsmod.content.beanlab.core

import org.rsmod.api.db.DatabaseConnection

/**
 * Migrations for Beanscape's own tables, kept in the `beanlab` Postgres schema.
 *
 * OpenRune's only migrations are Central's, shipped inside its library, so our modules apply their
 * own. Each module passes its name and an ordered list of `V<n>__name.sql` classpath resources;
 * applied versions are recorded in `beanlab.schema_migrations` and never run twice. Call it at the
 * start of a database request: the gateway runs that request in one transaction, so a failed
 * migration rolls back together with the work that needed it.
 */
public object BeanlabSchema {
    private val VERSION = Regex("""V(\d+)__.+\.sql""")

    public fun migrate(conn: DatabaseConnection, module: String, owner: Class<*>, files: List<String>) {
        conn.prepareStatement("CREATE SCHEMA IF NOT EXISTS beanlab").use { it.execute() }
        conn
            .prepareStatement(
                "CREATE TABLE IF NOT EXISTS beanlab.schema_migrations (" +
                    "module TEXT NOT NULL, version INTEGER NOT NULL, name TEXT NOT NULL, " +
                    "applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "PRIMARY KEY (module, version))"
            )
            .use { it.execute() }
        val applied = HashSet<Int>()
        conn.prepareStatement("SELECT version FROM beanlab.schema_migrations WHERE module = ?").use {
            it.setString(1, module)
            it.executeQuery().use { rs -> while (rs.next()) applied += rs.getInt(1) }
        }
        val ordered =
            files
                .map { name ->
                    val version =
                        VERSION.matchEntire(name.substringAfterLast('/'))?.groupValues?.get(1)?.toInt()
                            ?: error("Migration file must be named V<n>__name.sql: $name")
                    version to name
                }
                .sortedBy { it.first }
        for ((version, name) in ordered) {
            if (version in applied) continue
            val sql =
                owner.getResourceAsStream(name)?.use { String(it.readAllBytes(), Charsets.UTF_8) }
                    ?: error("Missing migration resource $name for $module")
            conn.prepareStatement(sql).use { it.execute() }
            conn
                .prepareStatement(
                    "INSERT INTO beanlab.schema_migrations (module, version, name) VALUES (?, ?, ?)"
                )
                .use {
                    it.setString(1, module)
                    it.setInt(2, version)
                    it.setString(3, name.substringAfterLast('/'))
                    it.executeUpdate()
                }
        }
    }
}
