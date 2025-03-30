
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.events.FailureResult
import org.gradle.tooling.events.OperationDescriptor
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.ProgressListener
import org.gradle.tooling.events.SkippedResult
import org.gradle.tooling.events.SuccessResult
import org.gradle.tooling.events.test.JvmTestKind
import org.gradle.tooling.events.test.JvmTestOperationDescriptor
import org.gradle.tooling.events.test.TestFinishEvent
import org.gradle.tooling.events.test.TestOutputEvent
import org.gradle.tooling.events.test.TestStartEvent
import org.gradle.tooling.model.GradleProject
import org.gradle.tooling.model.build.BuildEnvironment
import org.gradle.tooling.model.gradle.GradleBuild
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Start sse-server mcp on port 3001.
 *
 * @param args
 * - "--stdio": Runs an MCP server using standard input/output. (default if no argument is provided)
 * - "--sse-server-ktor <port>": Runs an SSE MCP server using Ktor plugin.
 * - "--sse-server <port>": Runs an SSE MCP server with a plain configuration.
 * - "--debug": enable debug logs
 */
fun main(args: Array<String>) {
    // Parse the command and detect if debug mode is enabled.
    val debug = args.contains("--debug")
    // Remove debug flag from the arguments if needed.
    val filteredArgs = args.filter { it != "--debug" }
    val command = filteredArgs.firstOrNull() ?: "--stdio"
    val port = filteredArgs.getOrNull(1)?.toIntOrNull() ?: 3001
    when (command) {
        "--stdio" -> runMcpServerUsingStdio(debug)
        "--sse" -> runSseMcpServerUsingKtorPlugin(port, debug)
        else -> {
            System.err.println("Unknown command: $command")
        }
    }
}

private val logger = LoggerFactory.getLogger("GradleMcpServer")

// region Data Classes for Get Project Info Tool

@Serializable
enum class InfoCategory {
    @SerialName("buildStructure") BUILD_STRUCTURE,
    @SerialName("tasks") TASKS,
    @SerialName("environment") ENVIRONMENT,
    @SerialName("projectDetails") PROJECT_DETAILS
}

@Serializable
data class SimpleGradleProject(
    val name: String,
    val path: String,
    @SerialName("is_root") val isRoot: Boolean = false, // Indicate if it's the root
)

@Serializable
data class BuildStructureInfo(
    @SerialName("root_project_name") val rootProjectName: String,
    @SerialName("root_project_path_gradle") val rootProjectPathGradle: String, // Gradle's logical path (usually ':')
    @SerialName("build_identifier_path") val buildIdentifierPath: String,
    val subprojects: List<SimpleGradleProject>
)

@Serializable
data class TaskInfo(
    val name: String,
    val path: String, // Full Gradle path like :subproject:taskName
    val description: String? = null
)

@Serializable
data class EnvironmentInfo(
    @SerialName("gradle_version") val gradleVersion: String,
    @SerialName("java_home") val javaHome: String,
    @SerialName("jvm_arguments") val jvmArguments: List<String>
)

@Serializable
data class ProjectDetailsInfo(
    val name: String,
    val path: String, // Gradle's logical path
    val description: String? = null,
    @SerialName("build_script_path") val buildScriptPath: String? = null
)

@Serializable
data class GradleProjectInfoResponse(
    @SerialName("requested_path") val requestedPath: String,
    @SerialName("build_structure") val buildStructure: BuildStructureInfo? = null,
    val tasks: List<TaskInfo>? = null, // Tasks specifically from the root project
    val environment: EnvironmentInfo? = null,
    @SerialName("root_project_details") val rootProjectDetails: ProjectDetailsInfo? = null, // Renamed for clarity
    val errors: List<String>? = null // To report issues fetching specific parts
)

// endregion

fun configureServer(debug: Boolean = false): Server {
    val server = Server(
        Implementation(
            name = "Gradle MCP Server",
            version = "0.1.0"
        ),
        ServerOptions(
            capabilities = ServerCapabilities(
                prompts = ServerCapabilities.Prompts(listChanged = false),
                tools = ServerCapabilities.Tools(listChanged = false)
            )
        )
    )

    // --- Refactored Get Gradle Project Info Tool ---
    server.addTool(
        name = "Get Gradle Project Info",
        description = """
        Retrieves specific details about a Gradle project, returning structured JSON.
        Allows requesting only the necessary information for better efficiency.

        Available information categories to request via `requestedInfo`:
        - "buildStructure": Root project name, path, build identifier, and list of subprojects (name and path). Requires fetching `GradleBuild`.
        - "tasks": List of tasks available in the root project (name, path, description). Requires fetching `GradleProject`.
        - "environment": Gradle version, Java home, JVM arguments. Requires fetching `BuildEnvironment`.
        - "projectDetails": Root project's name, Gradle path, description, and build script path. Requires fetching `GradleProject`.

        If `requestedInfo` is not provided, ALL categories will be fetched and returned.
        Provide an empty array `[]` for `requestedInfo` to request no specific data (useful for just validating the path).

        Output: A JSON object containing keys corresponding to the requested categories.
        Includes an `errors` field if fetching specific parts failed.
        """.trimIndent(),
        inputSchema = inputSchema {
            requiredProperty("projectPath") {
                type("string")
                description("The absolute path to the root directory of the Gradle project.")
            }
            optionalProperty("requestedInfo") {
                arraySchema {
                    type("string")
                    // Use enum description if your schema generator supports it, otherwise list valid strings
                    description("List of information categories to retrieve. Valid values: 'buildStructure', 'tasks', 'environment', 'projectDetails'. If omitted, all are returned.")
                    // Example of explicit enum if needed by schema generator:
                    // attribute("enum", JsonArray(InfoCategory.entries.map { JsonPrimitive(it.serialName) }))
                }
                description("Specifies which categories of information to fetch. If omitted or null, all categories are fetched.")
            }
        },
    ) { request ->
        val projectPath = request.arguments.getValue("projectPath").jsonPrimitive.content
        val requestedCategoriesStrings = request.arguments["requestedInfo"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content }
            ?.toSet()

        // Determine effective categories: all if null/absent, specific if provided
        val effectiveCategories = requestedCategoriesStrings?.mapNotNull {
            try {
                Json.decodeFromString<InfoCategory>("\"$it\"") // Need quotes for enum decoding
            } catch (e: Exception) {
                logger.warn("Ignoring invalid requestedInfo category: '$it'")
                null
            }
        }?.toSet() ?: InfoCategory.entries.toSet() // Default to all if not specified

        if (debug) logger.debug("Requesting project info for '$projectPath', categories: $effectiveCategories")

        val connection = GradleConnector.newConnector()
            .forProjectDirectory(File(projectPath))
            .connect()

        val response = GradleProjectInfoResponse(requestedPath = projectPath)
        val errors = CopyOnWriteArrayList<String>() // Thread-safe list for errors

        try {
            // --- Conditionally Fetch Models ---
            val modelRequests = mutableMapOf<Class<*>, InfoCategory>()
            if (effectiveCategories.contains(InfoCategory.BUILD_STRUCTURE)) modelRequests[GradleBuild::class.java] = InfoCategory.BUILD_STRUCTURE
            if (effectiveCategories.contains(InfoCategory.ENVIRONMENT)) modelRequests[BuildEnvironment::class.java] = InfoCategory.ENVIRONMENT
            if (effectiveCategories.contains(InfoCategory.TASKS) || effectiveCategories.contains(InfoCategory.PROJECT_DETAILS)) {
                // Both need GradleProject, only request it once
                 modelRequests[GradleProject::class.java] = if (effectiveCategories.contains(InfoCategory.TASKS)) InfoCategory.TASKS else InfoCategory.PROJECT_DETAILS
            }

            val fetchedResults = mutableMapOf<InfoCategory, Any?>()
            var rootProjectModel: GradleProject? = null // Hold potentially shared model
            var buildModel: GradleBuild? = null // Hold potentially shared model

            if (modelRequests.containsKey(GradleProject::class.java)) {
                 try {
                     rootProjectModel = connection.model(GradleProject::class.java).get()
                     if (effectiveCategories.contains(InfoCategory.TASKS)) fetchedResults[InfoCategory.TASKS] = rootProjectModel
                     if (effectiveCategories.contains(InfoCategory.PROJECT_DETAILS)) fetchedResults[InfoCategory.PROJECT_DETAILS] = rootProjectModel
                 } catch (e: Exception) {
                     val msg = "Failed to fetch GradleProject model: ${e.message}"
                     logger.error(msg, e)
                     if (effectiveCategories.contains(InfoCategory.TASKS)) errors.add("Failed to fetch tasks: ${e.message}")
                     if (effectiveCategories.contains(InfoCategory.PROJECT_DETAILS)) errors.add("Failed to fetch project details: ${e.message}")
                 }
            }
             if (modelRequests.containsKey(GradleBuild::class.java)) {
                 try {
                     buildModel = connection.model(GradleBuild::class.java).get()
                     fetchedResults[InfoCategory.BUILD_STRUCTURE] = buildModel
                 } catch (e: Exception) {
                     val msg = "Failed to fetch GradleBuild model: ${e.message}"
                     logger.error(msg, e)
                     errors.add("Failed to fetch build structure: ${e.message}")
                 }
             }
            if (modelRequests.containsKey(BuildEnvironment::class.java)) {
                 try {
                     fetchedResults[InfoCategory.ENVIRONMENT] = connection.model(BuildEnvironment::class.java).get()
                 } catch (e: Exception) {
                     val msg = "Failed to fetch BuildEnvironment model: ${e.message}"
                     logger.error(msg, e)
                     errors.add("Failed to fetch environment info: ${e.message}")
                 }
            }

            // --- Populate Response DTO ---
            val finalResponse = response.copy(
                buildStructure = fetchedResults[InfoCategory.BUILD_STRUCTURE]?.let { model ->
                    val build = model as GradleBuild
                    BuildStructureInfo(
                        rootProjectName = build.rootProject.name,
                        rootProjectPathGradle = build.rootProject.path, // Usually ":"
                        buildIdentifierPath = build.buildIdentifier.rootDir.absolutePath,
                        subprojects = build.projects.map { SimpleGradleProject(it.name, it.path, it.path == ":") } // Mark root explicitly
                    )
                },
                tasks = fetchedResults[InfoCategory.TASKS]?.let { model ->
                    val project = model as GradleProject
                    project.tasks.map { TaskInfo(it.name, it.path, it.description) }
                },
                environment = fetchedResults[InfoCategory.ENVIRONMENT]?.let { model ->
                    val env = model as BuildEnvironment
                    EnvironmentInfo(
                        gradleVersion = env.gradle.gradleVersion,
                        javaHome = env.java.javaHome.absolutePath,
                        jvmArguments = env.java.jvmArguments ?: emptyList()
                    )
                },
                rootProjectDetails = fetchedResults[InfoCategory.PROJECT_DETAILS]?.let { model ->
                    val project = model as GradleProject
                    ProjectDetailsInfo(
                        name = project.name,
                        path = project.path,
                        description = project.description,
                        buildScriptPath = project.buildScript.sourceFile?.absolutePath
                    )
                },
                errors = errors.toList().ifEmpty { null } // Add errors if any occurred
            )

            // --- Serialize and Return ---
            val json = Json { prettyPrint = true; encodeDefaults = true; classDiscriminator = "#class" } // Allow default values like empty lists if needed
            val jsonResponse = json.encodeToString(finalResponse)
            if (debug) logger.debug("Gradle Project Info Response:\n$jsonResponse")
            CallToolResult(content = listOf(TextContent(jsonResponse)))

        } catch (e: GradleConnectionException) {
            logger.error("Gradle connection error for path: $projectPath", e)
            val errorResponse = response.copy(errors = listOf("Gradle connection failed for '$projectPath': ${e.message}"))
            CallToolResult(content = listOf(TextContent(Json.encodeToString(errorResponse))))
        } catch (e: IllegalStateException) {
            // Often happens if the project path is invalid or not a Gradle project
             logger.error("Error getting project info (likely invalid project path): $projectPath", e)
             val errorResponse = response.copy(errors = listOf("Failed to get info for '$projectPath' (is it a valid Gradle project?): ${e.message}"))
             CallToolResult(content = listOf(TextContent(Json.encodeToString(errorResponse))))
        } catch (e: Exception) {
            // Catch-all for other unexpected errors during processing
            logger.error("Unexpected error getting Gradle project info for path: $projectPath", e)
            val errorResponse = response.copy(errors = listOf("Unexpected error retrieving info for '$projectPath': ${e.message}"))
            CallToolResult(content = listOf(TextContent(Json.encodeToString(errorResponse))))
        } finally {
            connection.close()
        }
    }

    // --- Keep Existing Tools ---
    addHierarchicalTestTool(server, debug)
    addExecuteTaskTool(server, debug) // Assuming you split the original execute task tool into its own function

    return server
}

// Helper function to add the Execute Gradle Task tool (extracted for clarity)
private fun addExecuteTaskTool(server: Server, debug: Boolean) {
     server.addTool(
        name = "Execute Gradle Task",
        description = """
        Executes one or more specified Gradle tasks in a project.
        **Use this for general build lifecycle tasks** (like 'clean', 'build', 'assemble', 'publish') or **custom tasks** defined in the build scripts.
        **DO NOT use this tool to run tests if you need detailed, per-test results and output.** Use the 'Run Gradle Tests with Structured Results' tool for testing instead.

        Allows customization:
        - `tasks`: List of task names to execute (e.g., ['clean', 'build']). Order matters.
        - `arguments`: Optional list of Gradle command-line arguments (e.g., ['--info', '--stacktrace', '-PmyProperty=value', '--rerun-tasks']).
        - `jvmArguments`: Optional list of JVM arguments for the Gradle process itself (e.g., ['-Xmx4g']).
        - `environmentVariables`: Optional map of environment variables for the build (e.g., {"CI": "true"}).

        **Output:** Returns a formatted text response containing:
        - A summary of the request parameters (tasks, arguments, etc.).
        - A final `Status:` line indicating overall `Success` or `Failure`.
        - The combined standard output (stdout) and standard error (stderr) streams captured from the Gradle execution.
        - If the status is `Failure`, additional error details might be included.

        **Check the 'Status' line and carefully review the 'Build Output' section for build logs, warnings, and specific error messages.**
        Note: Executing tasks like 'build' can modify files in the project directory.
        """.trimIndent(), // <-- Updated description
        inputSchema = inputSchema {
            requiredProperty("projectPath") {
                type("string")
                description("The absolute path to the root directory of the Gradle project.")
            }
            requiredProperty("tasks") {
                arraySchema { type("string") }
                description("List of Gradle task names to execute in the specified order (e.g., ['clean', 'build']). Use 'Get Gradle Project Info' to find available tasks.") // <-- Added hint
            }
            optionalProperty("arguments") {
                arraySchema { type("string") }
                description("List of command-line arguments to pass directly to Gradle (e.g., ['--info', '--stacktrace', '-PmyProp=value']).") // <-- Example
            }
            optionalProperty("jvmArguments") {
                arraySchema { type("string") }
                description("List of JVM arguments specifically for the Gradle daemon/process (e.g., ['-Xmx4g', '-Dfile.encoding=UTF-8']).") // <-- Example
            }
            optionalProperty("environmentVariables") {
                type("object")
                attribute("additionalProperties", JsonObject(mapOf("type" to JsonPrimitive("string"))))
                description("Map of environment variables to set for the Gradle build process (e.g., {\"CI\":\"true\", \"MY_API_KEY\":\"secret\"}).") // <-- Example
            }
        }
    ) { request ->
        // Keep the implementation refined previously, which produces a clear text output.
        val projectPath = request.arguments.getValue("projectPath").jsonPrimitive.content
        val tasks = request.arguments.getValue("tasks").jsonArray.map { it.jsonPrimitive.content }
        val arguments = request.arguments["arguments"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val jvmArguments = request.arguments["jvmArguments"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val environmentVariables = request.arguments["environmentVariables"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()

        val connection = GradleConnector.newConnector()
            .forProjectDirectory(File(projectPath))
            .connect()

        try {
            val buildLauncher = connection.newBuild()
                .forTasks(*tasks.toTypedArray())
                .withArguments(*arguments.toTypedArray())
                .setJvmArguments(*jvmArguments.toTypedArray())
                .setEnvironmentVariables(environmentVariables)
            val output = ByteArrayOutputStream()
            val errorOutput = ByteArrayOutputStream() // Capture stderr separately
            buildLauncher.setStandardOutput(output)
            buildLauncher.setStandardError(errorOutput) // Capture stderr

            val result = runCatching { buildLauncher.run() }

            val outputString = output.toString(Charsets.UTF_8)
            val errorString = errorOutput.toString(Charsets.UTF_8)
            // Combine non-empty streams, clearly marking stderr if present
            val combinedOutput = buildString {
                if (outputString.isNotBlank()) {
                    appendLine("--- Standard Output ---")
                    appendLine(outputString.trimEnd()) // Trim trailing newline for cleaner look
                }
                if (errorString.isNotBlank()) {
                    appendLine("--- Standard Error ---")
                    appendLine(errorString.trimEnd())
                }
                if (outputString.isBlank() && errorString.isBlank()){
                    append("[No output captured from stdout or stderr]")
                }
            }.trim()


            val responseText = buildString {
                appendLine("=== Gradle Task Execution Summary ===")
                appendLine("Project Path: $projectPath")
                appendLine("Executed Tasks: ${tasks.joinToString(", ")}")
                appendLine("Arguments: ${arguments.joinToString(" ")}")
                appendLine("JVM Arguments: ${jvmArguments.joinToString(" ")}")
                appendLine("Environment Variables: ${environmentVariables.map { "${it.key}=${it.value}" }.joinToString(", ")}")
                appendLine("Status: ${if (result.isSuccess) "Success" else "Failure"}") // Crucial status line
                appendLine()
                appendLine("=== Build Output ===")
                appendLine(combinedOutput) // Include combined stdout/stderr

                if (result.isFailure) {
                    appendLine()
                    appendLine("=== Failure Details ===")
                    val exception = result.exceptionOrNull()
                    appendLine("Error: ${exception?.message ?: "Unknown execution error."}")
                    // Optionally include stack trace if debug is enabled (or based on --stacktrace arg)
                    if (debug || arguments.contains("--stacktrace")) {
                        appendLine("\nStack Trace:\n${exception?.stackTraceToString()}")
                    } else {
                        appendLine("(Use Gradle argument '--stacktrace' or run server with '--debug' for full stack trace)")
                    }
                }
            }

            CallToolResult(content = listOf(TextContent(responseText.trimIndent())))
        } catch (e: Exception) {
            logger.error("Error preparing or executing Gradle task for path: $projectPath", e)
            // Provide a structured error message for setup failures
            val setupErrorText = """
            === Gradle Task Execution Failed ===
            Project Path: $projectPath
            Error: Failed during setup or connection before task execution could start.
            Message: ${e.message}
            ${if (debug) "\nStack Trace:\n${e.stackTraceToString()}" else ""}
        """.trimIndent()
            CallToolResult(content = listOf(TextContent(setupErrorText)))
        }
        finally {
            connection.close()
        }
    }
}

@Serializable
data class TestEventData(
    @SerialName("display_name")
    val displayName: String,
    @SerialName("outcome")
    val outcome: String,
    @SerialName("failure_message")
    val failureMessage: String? = null,
    @SerialName("output_lines")
    val outputLines: List<String> = emptyList()
)

@Serializable
data class GradleTestResultResponse(
    @SerialName("tasks_executed")
    val tasksExecuted: List<String>,
    @SerialName("arguments")
    val arguments: List<String>,
    @SerialName("environment_variables")
    val environmentVariables: Map<String, String>,
    @SerialName("test_results")
    val testResults: List<TestEventData>,
    @SerialName("success")
    val success: Boolean,
    @SerialName("notes")
    val notes: String? = null
)

@Serializable
data class GradleHierarchicalTestResponse(
    @SerialName("tasks_executed")
    val tasksExecuted: List<String>,
    @SerialName("arguments")
    val arguments: List<String>,
    @SerialName("environment_variables")
    val environmentVariables: Map<String, String>,
    @SerialName("test_hierarchy")
    val testHierarchy: List<HierarchicalTestResultNode>, // List of root nodes
    @SerialName("success")
    val success: Boolean, // Overall build success
    @SerialName("notes")
    val notes: String? = null
)

@Serializable
data class HierarchicalTestResultNode(
    @SerialName("display_name")
    val displayName: String, // From descriptor.displayName
    @SerialName("type") // e.g., "SUITE", "CLASS", "TEST"
    val type: String,
    @SerialName("outcome")
    var outcome: String = "unknown", // Updated on finish: passed, failed, skipped
    @SerialName("failure_message")
    var failureMessage: String? = null, // Set on failure
    @SerialName("output_lines")
    var outputLines: List<String> = emptyList(), // Populated from TestOutputEvent for atomic tests
    @SerialName("children")
    val children: MutableList<HierarchicalTestResultNode> = mutableListOf()
) {
    @kotlinx.serialization.Transient
    var descriptor: OperationDescriptor? = null
}

private fun truncateLogLines(lines: List<String>, maxLines: Int): List<String> {
    if (maxLines <= 0 || lines.size <= maxLines) {
        return lines // No truncation needed or unlimited
    }
    if (maxLines == 1) {
        return listOf("... (output truncated, only 1 line allowed)") // Edge case
    }

    val headCount = maxLines / 2
    val tailCount = maxLines - headCount // Ensures total is maxLines even for odd numbers

    // Need at least 1 line for head, 1 for tail, 1 for marker
    if (headCount == 0 || tailCount == 0) {
        return lines.take(maxLines) + listOf("... (output truncated)") // Modified marker
    }


    val head = lines.take(headCount)
    val tail = lines.takeLast(tailCount)
    // Check if truncation actually happened before adding marker
    val marker = if (lines.size > maxLines) "... (${lines.size - headCount - tailCount} lines truncated) ..." else null

    return if (marker != null) head + marker + tail else head + tail // Only add marker if needed
}


// --- addHierarchicalTestTool function (full implementation as provided in the prompt) ---
// Make sure this function is present and complete in your final code.
// It's omitted here for brevity but needs to be included.
fun addHierarchicalTestTool(server: Server, debug: Boolean) {
    val defaultMaxLines = 100

    server.addTool(
        name = "Run Gradle Tests with Hierarchy",
        description = """
            Executes specified Gradle test tasks and returns a hierarchical structure of the test execution results.
            Provides details for each node (suite, class, test method) including outcome and failure message.
            Output lines (stdout/stderr) are included ONLY for FAILED tests by default.
            Output is filtered for common noise (like KSP rounds, classpath inheritance) and truncated if it exceeds the limit, keeping the first and last lines.

            Use 'gradleTasks' to specify tasks (defaults to ['test']).
            Use 'testPatterns' to filter specific tests.
            Use 'arguments' for general Gradle flags (Note: --info/--debug are filtered out).
            Use 'environmentVariables' to set environment variables.

            Output Control Options:
            - `includeOutputForPassed`: (Optional, boolean) Set to true to include output lines for passed tests. Defaults to false.
            - `maxLogLines`: (Optional, integer) Limit the total number of output lines stored per test (keeps first/last lines). Overrides the default limit. 0 or negative means unlimited.
            - `defaultMaxLogLines`: (Optional, integer) Sets the default maximum lines per test if `maxLogLines` is not specified. Defaults to $defaultMaxLines. Set to 0 or negative for unlimited by default.

            The output is a JSON object containing:
            - `tasks_executed`, `arguments`, `environment_variables`: Details of the request.
            - `success`: Boolean indicating if the overall Gradle build succeeded.
            - `notes`: Optional informational messages about execution.
            - `test_hierarchy`: A list of root test nodes. Each node object has:
                - `display_name`, `id`, `type`, `outcome`.
                - `failure_message`: Error details if the test node failed.
                - `output_lines`: (ONLY for failed tests by default) A list of strings (stdout/stderr prefixed, filtered, and truncated head/tail) captured during that specific test's execution.
                - `children`: A list of child nodes.

            **Note:** The full, raw Gradle build output (stdout/stderr) is logged to the MCP server's console if the server is run with the --debug flag, regardless of the tool's response content.
            """.trimIndent(), // Updated description
        inputSchema = inputSchema { // Input schema likely remains identical
            requiredProperty("projectPath") {
                type("string")
                description("Absolute path to the root directory of the Gradle project.")
            }
            optionalProperty("gradleTasks") {
                arraySchema { type("string") }
                description("Which Gradle test tasks to run. Defaults to ['test'].")
            }
            optionalProperty("arguments") {
                arraySchema { type("string") }
                description("Additional Gradle command-line arguments. --info/--debug are filtered.")
            }
            optionalProperty("environmentVariables") {
                type("object")
                attribute("additionalProperties", JsonObject(mapOf("type" to JsonPrimitive("string"))))
                description("Environment variables for the Gradle build.")
            }
            optionalProperty("testPatterns") {
                arraySchema { type("string") }
                description("Test filter patterns passed via '--tests'.")
            }

            optionalProperty("includeOutputForPassed") {
                type("boolean")
                description("If true, includes output lines for passed tests. Defaults to false.")
            }
            optionalProperty("maxLogLines") {
                type("integer")
                description("Explicitly limit the total number of output lines stored per test (keeps first N/2 and last N/2). 0 or negative means unlimited.")
            }
             optionalProperty("defaultMaxLogLines") {
                type("integer")
                description("Default limit for output lines per test if 'maxLogLines' isn't set. Defaults internally to $defaultMaxLines. Set to 0 or negative to disable the default limit.")
                attribute("default", JsonPrimitive(defaultMaxLines))
            }
        }
    ) { request ->
        // --- Argument Parsing ---
        val projectPath = request.arguments.getValue("projectPath").jsonPrimitive.content
        val gradleTasks = request.arguments["gradleTasks"]?.jsonArray?.map { it.jsonPrimitive.content }
            ?.takeIf { it.isNotEmpty() } ?: listOf("test")
        val inputArguments = request.arguments["arguments"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val environmentVariables = request.arguments["environmentVariables"]?.jsonObject
            ?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        val testPatterns = request.arguments["testPatterns"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val includeOutputForPassed = request.arguments["includeOutputForPassed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        val requestedMaxLogLines = request.arguments["maxLogLines"]?.jsonPrimitive?.intOrNull
        val requestedDefaultMaxLogLines = request.arguments["defaultMaxLogLines"]?.jsonPrimitive?.intOrNull

        // Determine the effective maxLogLines limit per test (positive value means limit, <= 0 means unlimited)
        val effectiveMaxLogLines = requestedMaxLogLines
            ?: requestedDefaultMaxLogLines
            ?: defaultMaxLines


        // --- Argument Filtering (Same as before) ---
        val problematicArgs = setOf("--info", "--debug")
        val filteredArguments = inputArguments.filter { arg -> !problematicArgs.contains(arg.split("=").first()) }
        val filteringOccurred = inputArguments.size != filteredArguments.size
        val finalArguments = filteredArguments.toMutableList()
        if (testPatterns.isNotEmpty()) {
            finalArguments.removeAll { it.startsWith("--tests=") }
        }
        testPatterns.forEach { pattern ->
            finalArguments += "--tests=$pattern"
        }

        // --- Data Structures (Same as before) ---
        val nodesMap = ConcurrentHashMap<OperationDescriptor, HierarchicalTestResultNode>()
        val rootNodes = ConcurrentHashMap.newKeySet<HierarchicalTestResultNode>()
        // Store all filtered lines temporarily
        val testOutputMap = ConcurrentHashMap<OperationDescriptor, MutableList<String>>()

        // --- Connect to Gradle ---
        val connection = GradleConnector.newConnector().forProjectDirectory(File(projectPath)).connect()

        // --- Prepare Streams for Overall Gradle Output ---
        val gradleStdOutStream = ByteArrayOutputStream()
        val gradleStdErrStream = ByteArrayOutputStream()

        try {
            val buildLauncher = connection.newBuild()
                .forTasks(*gradleTasks.toTypedArray())
                .withArguments(finalArguments)
                .setEnvironmentVariables(environmentVariables)
                // Capture overall build output
                .setStandardOutput(gradleStdOutStream)
                .setStandardError(gradleStdErrStream)


            // --- Noise Patterns for Filtering (Added classpath inheritance) ---
            val noisePatterns = listOf(
                Regex("""^\[(StdOut|StdErr)]\s*i: \[ksp] \[Anvil] \[.*?]( Starting round \d+| Round \d+ took \d+ms| Computing triggers took \d+ms| Loading previous contributions took \d+ms| Compute contributions took \d+ms| Compute pending events took \d+ms| Total processing time after \d+ round\(s\) took \d+ms)$"""),
                Regex("""^\[(StdOut|StdErr)]\s*i: \[ksp] \[Anvil] \[ClassScannerKsp] Generated Property Cache$"""),
                Regex("""^\[(StdOut|StdErr)]\s+(Size:|Hits:|Misses:|Fidelity:)\s+\d+%?$"""),
                Regex("""^\[(StdOut|StdErr)]\s*v: Loading modules:.*"""),
                Regex("""^\[(StdOut|StdErr)]\s*$"""), // Empty lines
                Regex("""^\[(StdOut|StdErr)]\s*logging: Inheriting classpaths:\s.*$""") // Skip classpath inheritance line
            )

            // --- Hierarchical Listener ---
            val hierarchicalListener = ProgressListener { event ->
                when (event) {
                    // --- Handle Test Start ---
                    is TestStartEvent -> {
                        val desc = event.descriptor
                        val parentDesc = desc.parent // Can be null

                        // Determine node type (basic inference)
                        val nodeType = when {
                            desc is JvmTestOperationDescriptor && desc.jvmTestKind == JvmTestKind.ATOMIC -> "TEST"
                            desc is JvmTestOperationDescriptor && desc.methodName == null -> "CLASS" // Assumption
                            else -> "SUITE" // Default/Fallback
                        }

                        // Create the node
                        val newNode = HierarchicalTestResultNode(
                            displayName = desc.displayName,
                            type = nodeType,
                        )
                        newNode.descriptor = desc
                        nodesMap[desc] = newNode

                        // Link to parent if parent exists and is tracked
                        val parentNode = parentDesc?.let { nodesMap[it] }
                        if (parentNode != null) {
                            parentNode.children.add(newNode)
                        } else {
                            // If no tracked parent, it's a root node (for this listener's scope)
                            rootNodes.add(newNode)
                        }
                        if(debug) logger.debug("Start: ${newNode.displayName} (Type: ${newNode.type}), Parent: ${parentDesc?.displayName}")
                    }

                    // --- Handle Test Finish ---
                    is TestFinishEvent -> {
                        val desc = event.descriptor
                        val node = nodesMap[desc]
                        if (node != null) {
                            val result = event.result
                            node.outcome = when (result) {
                                is SuccessResult -> "passed"
                                is FailureResult -> "failed"
                                is SkippedResult -> "skipped"
                                else -> "unknown" // Should ideally not happen for TestFinishEvent
                            }

                            // --- Failure Message Extraction ---
                            if (result is FailureResult) {
                                // Try to find the most relevant failure based on common patterns
                                val primaryFailure = result.failures.firstOrNull { f ->
                                    val msg = f.message?.lowercase() ?: ""
                                    val desc = f.description?.lowercase() ?: ""
                                    msg.contains("assertionfailed") || // JUnit 4/5 assertions
                                            msg.contains("comparisonfailure") || // JUnit comparison
                                            msg.contains("asserterror") || // TestNG assertions?
                                            msg.contains("exception") || // General exceptions
                                            desc.contains("failed") // Broader check in description
                                    // Add more specific keywords if needed for other test frameworks
                                } ?: result.failures.firstOrNull() // Fallback to the first failure if no specific one matched

                                // Format the failure message
                                node.failureMessage = primaryFailure?.let { failure ->
                                     val message = failure.message ?: "No specific error message."
                                     val descriptionSnippet = failure.description?.lines()?.mapNotNull { it.trim().ifEmpty { null } }?.take(5)?.joinToString("\n  ") ?: "" // Indent snippet
                                     buildString {
                                        append(message)
                                        if (descriptionSnippet.isNotEmpty() && !message.contains(descriptionSnippet.substringBefore('\n').take(50))) {
                                            append("\n  ...\n  ").append(descriptionSnippet) // Add marker and snippet
                                        }
                                    }
                                } ?: "Unknown test failure reason"
                            }
                            // --- End Failure Message Extraction ---

                            // *** Attach collected output (filtered & truncated) ***
                            if (node.outcome == "failed" || includeOutputForPassed) {
                                val collectedLines = testOutputMap[desc]?.toList() ?: emptyList()
                                // Apply first/last truncation using the effective limit
                                node.outputLines = truncateLogLines(collectedLines, effectiveMaxLogLines)
                            } else {
                                node.outputLines = emptyList() // Clear output if not needed
                            }

                            // Clean up temporary descriptor link
                            node.descriptor = null
                            if (debug) logger.debug("Finish: ${node.displayName}, Outcome: ${node.outcome}, Output lines: ${node.outputLines.size}")
                        } else {
                             if (debug) logger.warn("Finish event for untracked descriptor: ${desc.displayName}")
                        }
                    }

                    // --- Handle Test Output ---
                    is TestOutputEvent -> {
                        val outputDesc = event.descriptor
                        if (outputDesc != null) {
                            // *** Use the PARENT descriptor (the test) as the key for output map ***
                            val testDesc = outputDesc.parent
                            if (testDesc != null && (nodesMap[testDesc]?.type == "TEST")) { // Only collect for atomic tests
                                val key = testDesc
                                val outputLinesList = testOutputMap.computeIfAbsent(key) { CopyOnWriteArrayList() } // Use thread-safe list

                                val destination = outputDesc.destination
                                val rawMessage = outputDesc.message

                                val prefixedLines = rawMessage.lines().map { "[${destination.name}] $it" }

                                val filteredLines = prefixedLines.filter { line ->
                                    noisePatterns.none { pattern -> pattern.matches(line) }
                                }

                                // Add all filtered lines (truncation happens on TestFinishEvent)
                                if (filteredLines.isNotEmpty()) {
                                    outputLinesList.addAll(filteredLines)
                                }
                            } else {
                                if(debug) logger.debug("Ignoring output event without parent test descriptor: ${outputDesc.displayName}")
                            }
                        }
                    }
                }
            }

            // Add listener (Same as before)
            buildLauncher.addProgressListener(hierarchicalListener, OperationType.TEST, OperationType.TEST_OUTPUT)

            // --- Execute the Build (Same as before) ---
            val runResult = runCatching { buildLauncher.run() }

            // --- Log Full Gradle Output to Server Console (if debug enabled) ---
            if (debug) {
                val gradleStdOut = gradleStdOutStream.toString(StandardCharsets.UTF_8).trim()
                val gradleStdErr = gradleStdErrStream.toString(StandardCharsets.UTF_8).trim()
                if (gradleStdOut.isNotEmpty() || gradleStdErr.isNotEmpty()) {
                    logger.debug("--- Full Gradle Build Output (for debugging) ---")
                    if (gradleStdOut.isNotEmpty()) {
                        logger.debug("[Gradle StdOut]:\n$gradleStdOut")
                    }
                    if (gradleStdErr.isNotEmpty()) {
                        logger.debug("[Gradle StdErr]:\n$gradleStdErr")
                    }
                    logger.debug("--- End Full Gradle Build Output ---")
                } else {
                     logger.debug("--- No overall Gradle build output captured ---")
                }
            }

            // --- Prepare Response ---
            nodesMap.values.forEach { it.descriptor = null } // Cleanup transient field
            val finalHierarchy = rootNodes.toList().sortedBy { it.displayName } // Sort root nodes for consistent order

            // Sort children recursively for consistent order (optional but recommended)
            fun sortChildrenRecursively(node: HierarchicalTestResultNode) {
                node.children.sortBy { it.displayName }
                node.children.forEach { sortChildrenRecursively(it) }
            }
            finalHierarchy.forEach { sortChildrenRecursively(it) }


            val structuredResponse = GradleHierarchicalTestResponse(
                tasksExecuted = gradleTasks,
                arguments = inputArguments,
                environmentVariables = environmentVariables,
                testHierarchy = finalHierarchy, // The list of root nodes containing the full tree
                success = runResult.isSuccess,
                notes = buildString { // Construct informative notes
                    if (filteringOccurred) append("Note: Verbose Gradle arguments (--info/--debug) were filtered out from execution. ")
                    if (testPatterns.isNotEmpty()) append("Applied test filters: ${testPatterns.joinToString()}. ")
                    if (!includeOutputForPassed) append("Output lines included only for failed tests. ")
                    if (effectiveMaxLogLines > 0) append("Output lines per test limited to ~${effectiveMaxLogLines} (keeps first/last lines).")
                    else append("Output lines per test are unlimited.")
                    // Add note about overall build failure vs. test failure
                    if (!runResult.isSuccess && finalHierarchy.any { it.outcome == "passed" || it.outcome == "skipped" }) {
                         append(" The overall Gradle build failed, possibly due to issues outside of the reported test execution (e.g., compilation failure, task dependency issues).")
                    } else if (runResult.isSuccess && finalHierarchy.all { it.outcome == "failed"}) {
                         append(" The overall Gradle build succeeded, but all reported tests failed.") // Less common scenario
                    }
                }.trim().ifEmpty { null }
            )

            // --- JSON Encoding and Return (Same as before) ---
            val json = Json { prettyPrint = true; encodeDefaults = false }
            val jsonResponse = json.encodeToString(structuredResponse)
            if (debug) logger.debug("Hierarchical Test Result Payload:\n$jsonResponse")
            CallToolResult(content = listOf(TextContent(jsonResponse)))

        } catch (e: Exception) {
            logger.error("Error running Gradle hierarchical test tool", e)
            // Return structured error (same as before, maybe adapt slightly)
            val errorJson = Json.encodeToString(mapOf(
                "error" to "Failed to execute Gradle test task.",
                "message" to e.message,
                "success" to false, // Explicitly false on error
                "tasks_executed" to gradleTasks, // Include context if possible
                "arguments" to inputArguments,
                "environment_variables" to environmentVariables,
                "test_hierarchy" to emptyList<HierarchicalTestResultNode>(), // Empty hierarchy on error
                "notes" to "An error occurred during test execution setup or processing.",
                "details" to if (debug) e.stackTraceToString() else "Gradle connection/setup or listener processing failed."
            ))
            CallToolResult(content = listOf(TextContent(errorJson)))
        } finally {
            // Log captured Gradle output even if an exception occurred during setup/connection *after* build started
             if (debug && (gradleStdOutStream.size() > 0 || gradleStdErrStream.size() > 0)) {
                 logger.debug("--- Gradle Build Output (captured before exception) ---")
                 val gradleStdOut = gradleStdOutStream.toString(StandardCharsets.UTF_8).trim()
                 val gradleStdErr = gradleStdErrStream.toString(StandardCharsets.UTF_8).trim()
                 if (gradleStdOut.isNotEmpty()) logger.debug("[Gradle StdOut]:\n$gradleStdOut")
                 if (gradleStdErr.isNotEmpty()) logger.debug("[Gradle StdErr]:\n$gradleStdErr")
                 logger.debug("--- End Gradle Build Output ---")
             }
            connection.close()
        }
    }
}

fun runMcpServerUsingStdio(debug: Boolean = false) {
    val server = configureServer(debug)
    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = System.out.asSink().buffered()
    )

    runBlocking {
        server.connect(transport)
        val done = Job()
        server.onClose(done::complete)
        done.join()
        println("Server closed")
    }
}

fun runSseMcpServerUsingKtorPlugin(port: Int, debug: Boolean = false): Unit = runBlocking {
    println("Starting sse server on port $port")
    println("Use inspector to connect to the http://localhost:$port/sse")
    embeddedServer(CIO, host = "0.0.0.0", port = port) {
        mcp { configureServer(debug) }
    }.start(wait = true)
}
