package me.devsnox.jarlet.server

import me.devsnox.jarlet.adapter.server.ServerSoftwareAdapters
import me.devsnox.jarlet.command.lib.ServerCommandException
import me.devsnox.jarlet.config.JarletToml
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Core "materialize a server instance directory from a template" logic --
 * ported from `src/server/setup.sh`, factored out of [me.devsnox.jarlet.command.SetupCommand] so
 * [me.devsnox.jarlet.command.StartCommand] can call it directly for its auto-setup-if-missing
 * fallback (`start.sh` shells out to `setup.sh` for the same reason; this
 * is the in-process Kotlin equivalent).
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
            throw ServerCommandException("A server named '$name' already exists at $serverDir")
        }

        val toml = readToml(configPath)
        val server = toml.server

        if (!VALID_VERSION.matches(server.minecraftVersion)) {
            throw ServerCommandException("Invalid [server].minecraft_version")
        }
        if (server.port !in 1..65535) {
            throw ServerCommandException("Invalid [server].port")
        }

        // Resolves (and thereby validates) the package before touching the
        // filesystem, matching setup.sh's ordering.
        val adapter = ServerSoftwareAdapters.find(server.pkg)

        Files.createDirectories(serverDir)

        val serverJar = serverDir.resolve("server.jar")
        if (!Files.isRegularFile(serverJar)) {
            adapter.install(server.minecraftVersion, serverJar)
        }

        val eulaFile = serverDir.resolve("eula.txt")
        if (!Files.isRegularFile(eulaFile)) {
            Files.writeString(eulaFile, "eula=true\n")
        }

        val propertiesFile = serverDir.resolve("server.properties")
        if (!Files.isRegularFile(propertiesFile)) {
            Files.writeString(
                propertiesFile,
                "server-port=${server.port}\n" +
                    "online-mode=${server.onlineMode}\n" +
                    "motd=A Jarlet Minecraft Server\n" +
                    "enable-command-block=false\n",
            )
        }

        // The instance name lives only in the directory name / CLI arg,
        // never in the template itself (server-templating.md), so the
        // per-server copy is a plain, unmodified (byte-for-byte) copy of
        // the source template -- not a re-serialize through [JarletToml],
        // which would drop comments/formatting the same way
        // `json_to_toml()` does in the bash version.
        Files.copy(configPath, serverDir.resolve(templateName))

        return Result(serverDir, toml)
    }

    /** Reads a `jarlet.toml`-shaped file at [path], wrapping parse failures the way `toml_to_json()`'s callers do. */
    fun readToml(path: Path): JarletToml =
        try {
            JarletToml.read(path)
        } catch (exception: IOException) {
            throw ServerCommandException(
                "Could not read $path: ${exception.message}"
            )
        } catch (exception: Exception) {
            throw ServerCommandException(
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
                throw ServerCommandException("$explicit does not exist")
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
            ?: throw ServerCommandException("Bundled default template resource $resourcePath is missing")

        val tempFile = Files.createTempFile("jarlet-default-template-", ".toml")
        tempFile.toFile().deleteOnExit()
        Files.write(tempFile, bytes)

        println("No template file given -- using the bundled default $templateName template")
        return tempFile
    }

    /** Mirrors `resolve_path()`: resolves to an absolute path, failing if its parent directory doesn't exist. */
    private fun resolvePath(path: String): Path {
        val requested = Paths.get(path)
        val resolved = requested.toAbsolutePath().normalize()
        val parent = resolved.parent
        if (parent == null || !Files.isDirectory(parent)) {
            throw ServerCommandException("Directory does not exist: ${requested.toAbsolutePath().parent ?: Paths.get(".")}")
        }
        return resolved
    }
}
