package org.rsmod.content.beanlab.admin.api

import org.rsmod.content.beanlab.hub.HubLayoutService

/** What the hub routes need from the layout file, so the router can be tested without a disk. */
public interface HubLayoutPort {
    /** Layout JSON text and where it came from (`override` or `bundled`). */
    public fun read(): Pair<String, String>

    /** Validates and saves [text]; returns the problems, empty when it was saved. */
    public fun write(text: String): List<String>
}

/** The real port: the hub module's [HubLayoutService] and its override file. */
public class FileHubLayoutPort(private val service: HubLayoutService = HubLayoutService()) :
    HubLayoutPort {
    override fun read(): Pair<String, String> = service.read().let { it.text to it.source }

    override fun write(text: String): List<String> = service.write(text)
}
