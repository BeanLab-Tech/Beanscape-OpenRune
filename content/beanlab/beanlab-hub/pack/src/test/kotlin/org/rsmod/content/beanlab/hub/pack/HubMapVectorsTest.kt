package org.rsmod.content.beanlab.hub.pack

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs the validator test vectors shared with the web panel (`web/tests/test_hub_layout.py`)
 * through [HubMap.parse], so the Kotlin and Python validators report the same problems.
 */
class HubMapVectorsTest {
    private val mapper = ObjectMapper()

    private fun problems(text: String): List<String> =
        try {
            HubMap.parse(text)
            emptyList()
        } catch (e: HubMapException) {
            e.problems
        }

    @Test
    fun `kotlin validator matches the shared vectors`() {
        val stream = HubMapVectorsTest::class.java.getResourceAsStream("/hub-layout-vectors.json")
        assertNotNull(stream, "missing hub-layout-vectors.json")
        val vectors = stream.use { mapper.readTree(it) }
        val base = vectors["base"] as ObjectNode
        val failures = ArrayList<String>()
        for (case in vectors["cases"]) {
            val name = case["name"].asText()
            val text =
                if (case.has("raw")) {
                    case["raw"].asText()
                } else {
                    val layout = base.deepCopy()
                    for ((key, value) in case["merge"].properties()) {
                        if (value.isNull) layout.remove(key) else layout.set<ObjectNode>(key, value)
                    }
                    mapper.writeValueAsString(layout)
                }
            val expect = case["expect"].map { it.asText() }
            val got = problems(text)
            val matches = got.size == expect.size && got.zip(expect).all { (p, f) -> f in p }
            if (!matches) failures += "$name: expected $expect, got $got"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
