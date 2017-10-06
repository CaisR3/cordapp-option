package net.corda.option.plugin

import net.corda.core.messaging.CordaRPCOps
import net.corda.option.api.OptionApi
import net.corda.webserver.services.WebServerPluginRegistry
import java.util.function.Function

class OptionPlugin : WebServerPluginRegistry {
    /**
     * A list of classes that expose web APIs.
     */
    override val webApis: List<Function<CordaRPCOps, out Any>> = listOf(Function(::OptionApi))

    /**
     * A list of directories in the resources directory that will be served by Jetty under /web.
     * The option's web frontend is accessible at /web/option.
     */
    override val staticServeDirs: Map<String, String> = mapOf(
            // This will serve the optionWeb directory in resources to /web/template
            "option" to javaClass.classLoader.getResource("optionWeb").toExternalForm()
    )
}