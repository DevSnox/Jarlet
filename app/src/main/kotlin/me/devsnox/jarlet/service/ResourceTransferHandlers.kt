package me.devsnox.jarlet.service

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import me.devsnox.jarlet.instance.InstanceRef
import me.devsnox.jarlet.instance.PluginPackageResource
import me.devsnox.jarlet.instance.ServerPackageResource
import me.devsnox.jarlet.instance.WorldResource

/** Reconciles server package declarations through the existing server service. */
internal object ServerPackageTransferHandler {
    fun transfer(resource: ServerPackageResource, target: InstanceRef): ResourceTransferResult {
        ServerService.reconcilePackage(target.toString())
        return ResourceTransferResult(resource.id, "server-package", ResourceTransferResult.Status.RECONCILED)
    }
}

/** Reconciles plugin declarations through the existing package resolver. */
internal object PluginPackageTransferHandler {
    fun transfer(resource: PluginPackageResource, target: InstanceRef): ResourceTransferResult {
        // PluginService owns downloads, verification, stale-file cleanup, and
        // plugins-state.json. No plugin artifact is copied by this handler.
        PluginService.update(target.toString(), resource.declaration.id, trust = false, resolveDependencies = false)
        return ResourceTransferResult(resource.id, "plugin-package", ResourceTransferResult.Status.RECONCILED)
    }
}

/** Replaces a world from a stable snapshot, with backup and rollback. */
internal object WorldTransferHandler {
    fun transfer(resource: WorldResource, targetRoot: Path): ResourceTransferResult {
        val source = resource.directory
        if (!Files.isDirectory(source)) throw JarletServiceException.NotFound("World source does not exist: $source")
        requireStopped(source.parent)
        requireStopped(targetRoot)

        val before = fingerprint(source)
        val staging = Files.createTempDirectory(targetRoot, ".jarlet-world-")
        val stagedWorld = staging.resolve(resource.id)
        try {
            copyTree(source, stagedWorld)
            if (fingerprint(source) != before) {
                throw JarletServiceException.Conflict("World ${resource.id} changed while it was being copied")
            }

            val target = targetRoot.resolve(resource.id)
            val backup = targetRoot.resolve(".${resource.id}.jarlet-backup")
            if (Files.exists(backup)) backup.toFile().deleteRecursively()
            if (Files.exists(target)) Files.move(target, backup)
            try {
                Files.move(stagedWorld, target)
                if (Files.exists(backup)) backup.toFile().deleteRecursively()
            } catch (exception: Exception) {
                target.toFile().deleteRecursively()
                if (Files.exists(backup)) Files.move(backup, target)
                throw JarletServiceException.OperationFailed(
                    "Could not replace world ${resource.id}: ${exception.message}", exception,
                )
            }
            return ResourceTransferResult(resource.id, "world", ResourceTransferResult.Status.REPLACED)
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    private fun copyTree(source: Path, destination: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val target = destination.resolve(source.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(target)
                else Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun fingerprint(root: Path): Map<String, Pair<Long, Long>> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) }
            .associate { path ->
                val attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
                root.relativize(path).toString() to (attributes.size() to attributes.lastModifiedTime().toMillis())
            }
    }

    private fun requireStopped(instanceRoot: Path?) {
        if (instanceRoot == null) return
        val pidFile = instanceRoot.resolve(".jarlet/server.pid")
        val pid = if (Files.isRegularFile(pidFile)) Files.readString(pidFile).trim().toLongOrNull() else null
        if (pid != null && ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) {
            throw JarletServiceException.Conflict("World transfer requires stopped instance at $instanceRoot")
        }
    }
}
