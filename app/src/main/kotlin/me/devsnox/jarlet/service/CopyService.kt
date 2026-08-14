package me.devsnox.jarlet.service

import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.config.write
import me.devsnox.jarlet.instance.InstanceRef
import me.devsnox.jarlet.instance.InstanceResolver
import me.devsnox.jarlet.instance.PluginPackageResource
import me.devsnox.jarlet.instance.ResolvedResource
import me.devsnox.jarlet.instance.ResourceResolver
import me.devsnox.jarlet.instance.ResourceSelector
import me.devsnox.jarlet.instance.ServerPackageResource
import me.devsnox.jarlet.instance.WorldResource
import java.nio.file.Files
import java.nio.file.Path

/** Explicit semantics for package declaration transfer. */
enum class PackageTransferMode { POLICY, RESOLVED_PIN }

data class CopyRequest(
    val source: InstanceRef,
    val target: InstanceRef,
    val selectors: Set<ResourceSelector>,
    val packageMode: PackageTransferMode = PackageTransferMode.POLICY,
)

data class ResourceDiff(
    val selector: ResourceSelector,
    val resources: List<ResolvedResource>,
)

data class CopyPlan(
    val request: CopyRequest,
    val changes: List<ResourceDiff>,
    val targetToml: JarletToml,
)

data class ResourceTransferResult(
    val resourceId: String,
    val type: String,
    val status: Status,
) {
    enum class Status { RECONCILED, REPLACED }
}

data class CopyResult(
    val source: InstanceRef,
    val target: InstanceRef,
    val changes: List<ResourceDiff>,
    val results: List<ResourceTransferResult>,
)

data class EnvironmentCopyResult(
    val sourceEnvironment: String,
    val targetEnvironment: String,
    val instances: List<CopyResult>,
)

/** Plans semantic package/world resources and dispatches typed handlers. */
object CopyService {
    private val resolver: InstanceResolver
        get() = InstanceResolver()

    fun plan(request: CopyRequest): CopyPlan {
        if (request.source == request.target) {
            throw JarletServiceException.InvalidInput("Source and target instances must differ")
        }
        if (request.selectors.isEmpty()) {
            throw JarletServiceException.InvalidInput("At least one resource selector is required")
        }

        val sourceDir = requireConfigured(request.source)
        requireConfigured(request.target)
        val sourceToml = readToml(request.source)
        val targetToml = readToml(request.target)
        val resolver = ResourceResolver(sourceDir, sourceToml)
        val changes = request.selectors.sortedBy(::selectorKey).map { selector ->
            val resources = resolver.resolve(selector)
            if (resources.isEmpty()) {
                throw JarletServiceException.NotFound("No resources matched selector ${selectorKey(selector)}")
            }
            ResourceDiff(selector, resources)
        }

        val selectedServer = changes.flatMap { it.resources }.filterIsInstance<ServerPackageResource>().distinctBy { it.id }
        val selectedPlugins = changes.flatMap { it.resources }.filterIsInstance<PluginPackageResource>().distinctBy { it.id }
        val mergedServer = selectedServer.firstOrNull()?.targetDeclaration(request.packageMode)
            ?: targetToml.server
        val mergedPlugins = targetToml.plugins
            .filterNot { target -> selectedPlugins.any { it.declaration.source == target.source && it.declaration.id == target.id } } +
            selectedPlugins.map { it.targetDeclaration(request.packageMode) }

        return CopyPlan(request, changes, targetToml.copy(server = mergedServer, plugins = mergedPlugins))
    }

    fun apply(plan: CopyPlan): CopyResult {
        val targetDir = resolver.directory(plan.request.target)
        Files.createDirectories(targetDir)
        val resources = plan.changes.flatMap { it.resources }
            .distinctBy { resourceKey(it) }
            .sortedWith(compareBy({ transferPriority(it) }, { resourceKey(it) }))

        // Validate every selected resource before mutating the target.
        resources.forEach(::validateSourceResource)

        if (resources.any { it is ServerPackageResource || it is PluginPackageResource }) {
            plan.targetToml.write(resolver.config(plan.request.target))
        }

        val results = resources.map { transfer(it, targetDir, plan.request.target) }
        validateTarget(plan, targetDir, resources)
        return CopyResult(plan.request.source, plan.request.target, plan.changes, results)
    }

    fun copy(request: CopyRequest): CopyResult = apply(plan(request))

    fun copy(
        source: String,
        target: String,
        selectors: Set<String>,
        packageMode: PackageTransferMode = PackageTransferMode.POLICY,
    ): CopyResult = copy(
        CopyRequest(
            source = InstanceRef.parse(source),
            target = InstanceRef.parse(target),
            selectors = selectors.map(ResourceSelector::parse).toSet(),
            packageMode = packageMode,
        ),
    )

    /** Plans all same-named configured instances before applying any pair. */
    fun copyEnvironment(
        sourceEnvironment: String,
        targetEnvironment: String,
        selectors: Set<ResourceSelector>,
        packageMode: PackageTransferMode = PackageTransferMode.POLICY,
    ): EnvironmentCopyResult {
        InstanceRef.of("placeholder", sourceEnvironment)
        InstanceRef.of("placeholder", targetEnvironment)
        if (sourceEnvironment == targetEnvironment) {
            throw JarletServiceException.InvalidInput("Source and target environments must differ")
        }
        if (selectors.isEmpty()) {
            throw JarletServiceException.InvalidInput("At least one resource selector is required")
        }

        val sourceNames = EnvironmentService.list(sourceEnvironment).map { it.ref.name }.toSet()
        val targetNames = EnvironmentService.list(targetEnvironment).map { it.ref.name }.toSet()
        val requests = (sourceNames intersect targetNames).sorted().map { name ->
            CopyRequest(InstanceRef.of(name, sourceEnvironment), InstanceRef.of(name, targetEnvironment), selectors, packageMode)
        }
        val plans = requests.map(::plan)
        return EnvironmentCopyResult(sourceEnvironment, targetEnvironment, plans.map(::apply))
    }

    private fun transfer(resource: ResolvedResource, targetDir: Path, target: InstanceRef): ResourceTransferResult = when (resource) {
        is ServerPackageResource -> ServerPackageTransferHandler.transfer(resource, target)
        is PluginPackageResource -> PluginPackageTransferHandler.transfer(resource, target)
        is WorldResource -> WorldTransferHandler.transfer(resource, targetDir)
    }

    private fun requireConfigured(ref: InstanceRef): Path {
        val directory = resolver.directory(ref)
        if (!Files.isDirectory(directory) || !Files.isRegularFile(resolver.config(ref))) {
            throw JarletServiceException.NotFound("Instance '$ref' is not configured at $directory")
        }
        return directory
    }

    private fun readToml(ref: InstanceRef): JarletToml = try {
        JarletToml.read(resolver.config(ref))
    } catch (exception: Exception) {
        throw JarletServiceException.InvalidInput("Could not parse ${resolver.config(ref)} as TOML: ${exception.message}")
    }

    private fun validateSourceResource(resource: ResolvedResource) {
        when (resource) {
            is ServerPackageResource -> if (resource.declaration.pkg.isBlank() || resource.declaration.minecraftVersion.isBlank()) {
                throw JarletServiceException.InvalidInput("Source server package is incomplete")
            }
            is PluginPackageResource -> if (resource.declaration.id.isBlank() || resource.declaration.source.isBlank()) {
                throw JarletServiceException.InvalidInput("Source plugin package is incomplete")
            }
            is WorldResource -> if (!Files.isDirectory(resource.directory)) {
                throw JarletServiceException.NotFound("World ${resource.id} does not exist")
            }
        }
    }

    private fun validateTarget(plan: CopyPlan, targetDir: Path, resources: List<ResolvedResource>) {
        val targetToml = try { JarletToml.read(resolver.config(plan.request.target)) } catch (exception: Exception) {
            throw JarletServiceException.OperationFailed("Target configuration is invalid after transfer: ${exception.message}", exception)
        }
        val expectedServer = plan.targetToml.server
        if (resources.any { it is ServerPackageResource } &&
            (targetToml.server.pkg != expectedServer.pkg ||
                targetToml.server.minecraftVersion != expectedServer.minecraftVersion ||
                targetToml.server.policy != expectedServer.policy)
        ) {
            throw JarletServiceException.OperationFailed("Target server package did not transfer correctly")
        }
        resources.filterIsInstance<PluginPackageResource>().forEach { resource ->
            if (targetToml.plugins.none { it.source == resource.declaration.source && it.id == resource.declaration.id }) {
                throw JarletServiceException.OperationFailed("Target plugin package ${resource.id} did not transfer correctly")
            }
        }
        resources.filterIsInstance<WorldResource>().forEach { resource ->
            if (!Files.isDirectory(targetDir.resolve(resource.id))) {
                throw JarletServiceException.OperationFailed("World transfer did not produce ${resource.id}")
            }
        }
    }

    private fun selectorKey(selector: ResourceSelector): String = when (selector) {
        ResourceSelector.ServerPackage -> "package.server"
        ResourceSelector.AllPluginPackages -> "package.plugin.*"
        is ResourceSelector.PluginPackage -> "package.plugin.${selector.identifier}"
        ResourceSelector.AllWorlds -> "data.world.*"
        is ResourceSelector.World -> "data.world.${selector.name}"
        ResourceSelector.All -> "all"
    }

    private fun resourceKey(resource: ResolvedResource): String = "${resource::class.simpleName}:${resource.id}"

    private fun transferPriority(resource: ResolvedResource): Int = when (resource) {
        is ServerPackageResource -> 0
        is PluginPackageResource -> 1
        is WorldResource -> 2
    }
}

private fun ServerPackageResource.targetDeclaration(mode: PackageTransferMode): JarletToml.Server = when (mode) {
    PackageTransferMode.POLICY -> declaration
    PackageTransferMode.RESOLVED_PIN -> {
        val version = installed?.minecraftVersion
            ?: throw JarletServiceException.Conflict("Server package has no resolved installed version")
        declaration.copy(minecraftVersion = version, policy = JarletToml.Policy(pin = version))
    }
}

private fun PluginPackageResource.targetDeclaration(mode: PackageTransferMode): JarletToml.Plugin = when (mode) {
    PackageTransferMode.POLICY -> declaration
    PackageTransferMode.RESOLVED_PIN -> {
        val version = installed?.versionName
            ?: throw JarletServiceException.Conflict("Plugin ${declaration.id} has no resolved installed version")
        declaration.copy(policy = JarletToml.Policy(pin = version))
    }
}
