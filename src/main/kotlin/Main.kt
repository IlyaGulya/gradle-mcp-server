
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
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.model.idea.IdeaProject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Start sse-server mcp on port 3001.
 *
 * @param args
 * - "--stdio": Runs an MCP server using standard input/output. (default if no argument is provided)
 * - "--sse-server-ktor <port>": Runs an SSE MCP server using Ktor plugin.
 * - "--sse-server <port>": Runs an SSE MCP server with a plain configuration.
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: "--stdio"
    val port = args.getOrNull(1)?.toIntOrNull() ?: 3001
    when (command) {
        "--stdio" -> runMcpServerUsingStdio()
        "--sse" -> runSseMcpServerUsingKtorPlugin(port)
        else -> {
            System.err.println("Unknown command: $command")
        }
    }
}

fun configureServer(): Server {
    // Initialize the MCP server with basic metadata and capabilities
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

    // Add a prompt to retrieve Gradle project information
    server.addTool(
        name = "Get Gradle Project Info",
        description = "Retrieve information about a Gradle project",
        inputSchema = inputSchema {
            requiredProperty("projectPath") {
                type("string")
                description("Absolute path to the Gradle project")
            }
        },
    ) { request ->
        val projectPath = request.arguments.getValue("projectPath").jsonPrimitive.content

        // Connect to the Gradle project
        val connection = GradleConnector.newConnector()
            .forProjectDirectory(File(projectPath))
            .connect()

        try {
            val ideaProject = connection.model(IdeaProject::class.java).get()
            val projectInfo = """
                Project Name: ${ideaProject.name}
                Modules: ${ideaProject.modules.joinToString { it.name }}
            """.trimIndent()

            CallToolResult(
                content = listOf(TextContent(projectInfo))
            )
        } finally {
            connection.close()
        }
    }

    // Add a tool to execute a Gradle task
    server.addTool(
        name = "Execute Gradle Task",
        description = "Execute a Gradle task in a project",
        inputSchema = inputSchema {
            requiredProperty("projectPath") {
                type("string")
                description("Absolute path to the Gradle project")
            }
            requiredProperty("tasks") {
                type("string")
                description("Name of the tasks to execute")
            }
        }
    ) { request ->
        val projectPath = request.arguments.getValue("projectPath").jsonPrimitive.content
        val tasks = request.arguments.getValue("tasks").jsonPrimitive.content

        // Connect to the Gradle project
        val connection = GradleConnector.newConnector()
            .forProjectDirectory(File(projectPath))
            .connect()

        try {
            val buildLauncher = connection.newBuild()
            buildLauncher.forTasks(*tasks.split(" ").toTypedArray())
            val output = ByteArrayOutputStream()
            buildLauncher.setStandardOutput(output)
            val result = runCatching {
                buildLauncher.run()
            }

            CallToolResult(
                content = listOf(TextContent("""
                    Task(s) executed: $tasks
                    Output:
                    $output
                    
                    ${result.exceptionOrNull()?.message ?: ""}
                """.trimIndent()))
            )
        } finally {
            connection.close()
        }
    }

//    server.addTool(
//        name = "Run tests using Gradle",
//        description = "Execute tests in gradle project",
//        inputSchema = inputSchema {
//            requiredProperty("projectPath") {
//                type("string")
//                description("Absolute path to the Gradle project")
//            }
//
//        }
//    ) { request ->
//        val projectPath = request.arguments.getValue("projectPath").jsonPrimitive.content
//        val connection = GradleConnector.newConnector()
//            .forProjectDirectory(File(projectPath))
//            .connect()
//
//        val testLauncher = connection.newTestLauncher()
//
//    }

    return server
}

fun runMcpServerUsingStdio() {
    // Note: The server will handle listing prompts, tools, and resources automatically.
    // The handleListResourceTemplates will return empty as defined in the Server code.
    val server = configureServer()
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

/**
 * Starts an SSE (Server Sent Events) MCP server using the Ktor framework and the specified port.
 *
 * The url can be accessed in the MCP inspector at [http://localhost:$port]
 *
 * @param port The port number on which the SSE MCP server will listen for client connections.
 * @return Unit This method does not return a value.
 */
fun runSseMcpServerUsingKtorPlugin(port: Int): Unit = runBlocking {
    println("Starting sse server on port $port")
    println("Use inspector to connect to the http://localhost:$port/sse")

    embeddedServer(CIO, host = "0.0.0.0", port = port) {
        mcp { configureServer() }
    }.start(wait = true)
}
