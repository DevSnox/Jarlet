package me.devsnox.jarlet.server

import me.devsnox.jarlet.Log
import me.devsnox.jarlet.adapter.server.ServerSoftwareAdapters
import me.devsnox.jarlet.config.InstalledServer
import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.config.ServerStateStore
import me.devsnox.jarlet.service.JarletServiceException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Core "materialize a server instance directory from a template" logic,
 * factored out of [me.devsnox.jarlet.service.ServerService.setup] (called
 * for `jarlet setup`) so [me.devsnox.jarlet.service.ServerService.prepareStart]
 * can call it directly for its own auto-setup-if-missing fallback.
 */
internal object ServerSetup {
    private val VALID_VERSION = Regex("^[0-9A-Za-z._-]+$")

    /** Where a server instance ended up, and the template it was created (or found) with. */
    data class Result(val serverDir: Path, val toml: JarletToml)

    /**
     * Creates the instance directory for [name] from the template at
     * [templateFileArgument] (defaulting to the bundled default template
     * -- see [resolveConfigPath] -- when not given), installing the server
     * jar and writing `eula.txt`/`server.properties` defaults if they don't
     * already exist. Fails if a server named [name] already exists.
     */
    fun ensure(name: String, templateFileArgument: String?): Result {
        ServerPaths.validateName(name)

        val templateName = ServerPaths.templateFilename()
        val configPath = resolveConfigPath(templateFileArgument, templateName)

        val serverDir = ServerPaths.serverDir(name)
        if (Files.exists(serverDir)) {
            throw JarletServiceException.Conflict("A server named '$name' already exists at $serverDir")
        }

        val toml = readToml(configPath)
        val server = toml.server

        if (!VALID_VERSION.matches(server.packageInfo.version)) {
            throw JarletServiceException.InvalidInput("Invalid [server.package].version")
        }
        if (server.runtime.port !in 1..65535) {
            throw JarletServiceException.InvalidInput("Invalid [server.runtime].port")
        }

        // Resolves (and thereby validates) the package before touching the
        // filesystem.
        val adapter = ServerSoftwareAdapters.find(server.packageInfo.name)

        try {
            Files.createDirectories(serverDir)

            val serverJar = serverDir.resolve("server.jar")
            if (!Files.isRegularFile(serverJar)) {
                val installedVersion = adapter.install(server.packageInfo.name, server.packageInfo.version, serverJar, server.policy)
                // Recording this here means StartCommand's own drift-check
                // (ServerStateStore.read(serverDir) == null) sees a real
                // record right after `jarlet setup`, instead of
                // unconditionally reinstalling on the very next `jarlet
                // start` and wasting a redundant network round-trip.
                ServerStateStore.write(serverDir, InstalledServer(pkg = server.packageInfo.name, minecraftVersion = installedVersion))
            }

            if (adapter.isMinecraftServer(server.packageInfo.name)) {
                val eulaFile = serverDir.resolve("eula.txt")
                if (!Files.isRegularFile(eulaFile)) {
                    Files.writeString(eulaFile, "eula=true\n")
                }

                val propertiesFile = serverDir.resolve("server.properties")
                if (!Files.isRegularFile(propertiesFile)) {
                    Files.writeString(
                        propertiesFile,
                        "server-port=${server.runtime.port}\n" +
                            "online-mode=${server.runtime.onlineMode}\n" +
                            "motd=A Jarlet Minecraft Server\n" +
                            "enable-command-block=false\n",
                    )
                }
            }

            // The instance name lives only in the directory name / CLI arg,
            // never in the template itself, so the per-server copy is a
            // plain, unmodified (byte-for-byte) copy of the source template
            // -- not a re-serialize through [JarletToml], which would drop
            // comments/formatting (see [JarletToml.write]'s doc comment).
            Files.copy(configPath, serverDir.resolve(templateName))
        } catch (exception: Exception) {
            // Roll back: everything under serverDir was created by THIS call
            // (the Files.exists(serverDir) guard above means we only ever
            // reach here when the directory didn't exist beforehand), so a
            // failure at any point -- e.g. adapter.install() throwing because
            // the requested Minecraft version isn't supported -- must not
            // leave a half-built directory behind. Otherwise a retry with the
            // same name trips the "already exists" guard forever, even
            // though setup never actually completed.
            serverDir.toFile().deleteRecursively()
            throw exception
        }

        return Result(serverDir, toml)
    }

    /** Reads a `jarlet.toml`-shaped file at [path], wrapping I/O and parse failures into a [me.devsnox.jarlet.service.JarletServiceException]. */
    fun readToml(path: Path): JarletToml =
        try {
            JarletToml.read(path)
        } catch (exception: IOException) {
            throw JarletServiceException.OperationFailed(
                "Could not read $path: ${exception.message}", exception,
            )
        } catch (exception: Exception) {
            throw JarletServiceException.InvalidInput(
                "Could not parse $path as TOML: ${exception.message}"
            )
        }

    /**
     * Resolves the template to use: an explicit [templateFileArgument] if
     * given (must exist), else the bundled default template
     * ([extractBundledDefaultTemplate]) -- so `jarlet setup <name>` with no
     * argument always still has a template to work from, per [templateName]
     * (`TEMPLATE_FILENAME` in `jarlet-sys.conf`) naming the bundled resource
     * to fall back to.
     */
    private fun resolveConfigPath(templateFileArgument: String?, templateName: String): Path {
        if (templateFileArgument != null) {
            val explicit = resolvePath(templateFileArgument)
            if (!Files.isRegularFile(explicit)) {
                throw JarletServiceException.NotFound("$explicit does not exist")
            }
            return explicit
        }

        return extractBundledDefaultTemplate(templateName)
    }

    /**
     * Copies the bundled `/$templateName` classpath resource (see
     * `app/src/main/resources/jarlet.toml`, registered for the native image
     * via `app/build.gradle.kts`'s `graalvmNative.binaries.main.resources`)
     * out to a temp file, since the rest of this flow (parsing, and the
     * final byte-for-byte copy into the new server directory) works off a
     * real [Path], not a resource stream.
     */
    private fun extractBundledDefaultTemplate(templateName: String): Path {
        val resourcePath = "/$templateName"
        val bytes = ServerSetup::class.java.getResourceAsStream(resourcePath)?.readBytes()
            ?: throw JarletServiceException.OperationFailed("Bundled default template resource $resourcePath is missing")

        val tempFile = Files.createTempFile("jarlet-default-template-", ".toml")
        tempFile.toFile().deleteOnExit()
        Files.write(tempFile, bytes)

        Log.info("No template file given -- using the bundled default $templateName template")
        return tempFile
    }

    /** Resolves to an absolute path, failing if its parent directory doesn't exist. */
    private fun resolvePath(path: String): Path {
        val requested = Paths.get(path)
        val resolved = requested.toAbsolutePath().normalize()
        val parent = resolved.parent
        if (parent == null || !Files.isDirectory(parent)) {
            throw JarletServiceException.NotFound("Directory does not exist: ${requested.toAbsolutePath().parent ?: Paths.get(".")}")
        }
        return resolved
    }
}
