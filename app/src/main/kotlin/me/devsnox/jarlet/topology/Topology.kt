package me.devsnox.jarlet.topology

import me.devsnox.jarlet.instance.InstanceRef
import me.devsnox.jarlet.service.JarletServiceException

enum class TopologyScope { GLOBAL, ENVIRONMENT }

data class BackendRef(
    val instance: InstanceRef,
    val address: String,
) {
    init {
        if (address.isBlank() || address.any(Char::isWhitespace)) {
            throw JarletServiceException.InvalidInput("Backend address must be a non-whitespace value")
        }
    }
}

/** Desired network topology; intentionally independent of process state. */
data class Topology(
    val proxy: InstanceRef,
    val scope: TopologyScope = TopologyScope.GLOBAL,
    val backends: Set<BackendRef> = emptySet(),
) {
    init {
        if (backends.any { it.instance == proxy }) {
            throw JarletServiceException.InvalidInput("A proxy cannot register itself as a backend")
        }
        if (scope == TopologyScope.ENVIRONMENT && proxy.environment == null) {
            throw JarletServiceException.InvalidInput("Environment topology requires a namespaced proxy")
        }
        if (scope == TopologyScope.ENVIRONMENT && backends.any { it.instance.environment != proxy.environment }) {
            throw JarletServiceException.InvalidInput("Environment-local backends must share the proxy environment")
        }
    }
}

data class RegisteredBackend(val name: String, val address: String)

data class TopologyState(val backends: Set<RegisteredBackend>)

data class TopologyChange(
    val name: String,
    val address: String,
    val action: Action,
) {
    enum class Action { REGISTER, UNREGISTER, UPDATE }
}

interface ProxyProvider {
    fun discover(): TopologyState
    fun register(backend: RegisteredBackend)
    fun unregister(name: String)
}

/** Reconciles desired state while leaving process lifecycle to ServerService. */
class TopologyService(private val provider: ProxyProvider) {
    fun plan(desired: Topology): List<TopologyChange> {
        val current = provider.discover().backends.associateBy { it.name }
        val wanted = desired.backends.associateBy { backendName(desired, it.instance) }
        return (current.keys + wanted.keys).toSortedSet().mapNotNull { name ->
            val before = current[name]
            val after = wanted[name]
            when {
                before == null && after != null -> TopologyChange(name, after.address, TopologyChange.Action.REGISTER)
                before != null && after == null -> TopologyChange(name, before.address, TopologyChange.Action.UNREGISTER)
                before != null && after != null && before.address != after.address ->
                    TopologyChange(name, after.address, TopologyChange.Action.UPDATE)
                else -> null
            }
        }
    }

    fun reconcile(desired: Topology): List<TopologyChange> {
        val changes = plan(desired)
        changes.forEach { change ->
            when (change.action) {
                TopologyChange.Action.REGISTER -> provider.register(RegisteredBackend(change.name, change.address))
                TopologyChange.Action.UNREGISTER -> provider.unregister(change.name)
                TopologyChange.Action.UPDATE -> {
                    provider.unregister(change.name)
                    provider.register(RegisteredBackend(change.name, change.address))
                }
            }
        }
        return changes
    }

    private fun backendName(topology: Topology, ref: InstanceRef): String = when (topology.scope) {
        TopologyScope.GLOBAL -> ref.toString().replace('/', '-')
        TopologyScope.ENVIRONMENT -> ref.name
    }
}
