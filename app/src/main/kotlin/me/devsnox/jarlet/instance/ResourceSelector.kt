package me.devsnox.jarlet.instance

import me.devsnox.jarlet.service.JarletServiceException

/** The intentionally small semantic resource vocabulary for environment transfer. */
sealed interface ResourceSelector {
    data object ServerPackage : ResourceSelector
    data object AllPluginPackages : ResourceSelector
    data class PluginPackage(val identifier: String) : ResourceSelector
    data object AllWorlds : ResourceSelector
    data class World(val name: String) : ResourceSelector
    data object All : ResourceSelector

    companion object {
        fun parse(value: String): ResourceSelector = when {
            value == "package.server" -> ServerPackage
            value == "package.plugin.*" -> AllPluginPackages
            value.startsWith("package.plugin.") -> PluginPackage(pluginName(value.removePrefix("package.plugin.")))
            value == "data.world.*" -> AllWorlds
            value.startsWith("data.world.") -> World(simpleName(value.removePrefix("data.world."), "world"))
            value == "all" -> All
            else -> throw JarletServiceException.InvalidInput("Invalid resource selector '$value'")
        }

        private fun pluginName(value: String): String {
            if (!Regex("^[0-9A-Za-z_.:/-]+$").matches(value) || value == "." || value == "..") {
                throw JarletServiceException.InvalidInput("Invalid plugin package selector '$value'")
            }
            return value
        }

        private fun simpleName(value: String, kind: String): String {
            if (!Regex("^[0-9A-Za-z_.-]+$").matches(value) || value == "." || value == "..") {
                throw JarletServiceException.InvalidInput("Invalid $kind selector '$value'")
            }
            return value
        }
    }
}
