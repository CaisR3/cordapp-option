package net.corda.option.plugin

import net.corda.core.messaging.CordaRPCOps
import net.corda.option.api.OptionApi
import net.corda.webserver.services.WebServerPluginRegistry
import java.util.function.Function

/**
 * Installing this plugin causes the CorDapp to offer a REST API and serve static web content.
 * Registered under src/resources/META-INF/services/.
 */
class OptionPlugin : WebServerPluginRegistry {

    override val webApis: List<Function<CordaRPCOps, out Any>> = listOf(Function(::OptionApi))

    override val staticServeDirs: Map<String, String> = mapOf(
            // This will serve the optionWeb directory in resources to /web/template
            "option" to javaClass.classLoader.getResource("optionWeb").toExternalForm()
    )
}