package me.gulya.gradle.mcp.server.tool

import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.gulya.gradle.mcp.config.DEFAULT_TEST_LOG_LINES
import me.gulya.gradle.mcp.config.logger
import me.gulya.gradle.mcp.gradle.GradleService
import me.gulya.gradle.mcp.inputSchema
import me.gulya.gradle.mcp.model.GradleTestResponse
import me.gulya.gradle.mcp.model.LogUtils
import me.gulya.gradle.mcp.model.TestResultNode
import org.gradle.tooling.events.FailureResult
import org.gradle.tooling.events.OperationDescriptor
import org.gradle.tooling.events.OperationResult
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.ProgressListener
import org.gradle.tooling.events.SkippedResult
import org.gradle.tooling.events.SuccessResult
import org.gradle.tooling.events.test.JvmTestKind
import org.gradle.tooling.events.test.JvmTestOperationDescriptor
import org.gradle.tooling.events.test.TestFinishEvent
import org.gradle.tooling.events.test.TestOutputEvent
import org.gradle.tooling.events.test.TestStartEvent
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class RunTestsTool : GradleTool {
    private val log = logger<RunTestsTool>()

    override val name = "Run Gradle Tests"
    override val description = """
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
        - `defaultMaxLogLines`: (Optional, integer) Sets the default maximum lines per test if `maxLogLines` is not specified. Defaults to ${DEFAULT_TEST_LOG_LINES}. Set to 0 or negative for unlimited by default.

        The output is a JSON object containing:
        - `tasks_executed`, `arguments`, `environment_variables`: Details of the request.
        - `success`: Boolean indicating if the overall Gradle build succeeded.
        - `notes`: Optional informational messages about execution.
        - `test_hierarchy`: A list of root test nodes. Each node object has:
            - `display_name`, `type`, `outcome`.
            - `failure_message`: Error details if the test node failed.
            - `output_lines`: (ONLY for failed tests by default) A list of strings (stdout/stderr prefixed, filtered, and truncated head/tail) captured during that specific test's execution.
            - `children`: A list of child nodes.

        **Note:** The full, raw Gradle build output (stdout/stderr) is logged to the MCP server's console if the server is run with the --debug flag, regardless of the tool's response content.
        """.trimIndent() // Keep original description

     override val inputSchema = inputSchema {
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
            description("Default limit for output lines per test if 'maxLogLines' isn't set. Defaults internally to ${DEFAULT_TEST_LOG_LINES}. Set to 0 or negative to disable the default limit.")
            attribute("default", JsonPrimitive(DEFAULT_TEST_LOG_LINES))
         }
     }

    // Noise patterns for filtering output
    private val noisePatterns = listOf(
        Regex("""^\[(StdOut|StdErr)]\s*i: \[ksp] \[Anvil] \[.*?]( Starting round \d+| Round \d+ took \d+ms| Computing triggers took \d+ms| Loading previous contributions took \d+ms| Compute contributions took \d+ms| Compute pending events took \d+ms| Total processing time after \d+ round\(s\) took \d+ms)$"""),
        Regex("""^\[(StdOut|StdErr)]\s*i: \[ksp] \[Anvil] \[ClassScannerKsp] Generated Property Cache$"""),
        Regex("""^\[(StdOut|StdErr)]\s+(Size:|Hits:|Misses:|Fidelity:)\s+\d+%?$"""),
        Regex("""^\[(StdOut|StdErr)]\s*v: Loading modules:.*"""),
        Regex("""^\[(StdOut|StdErr)]\s*$"""), // Empty lines
        Regex("""^\[(StdOut|StdErr)]\s*logging: Inheriting classpaths:\s.*$""") // Skip classpath inheritance line
    )

    override suspend fun execute(
        request: CallToolRequest,
        gradleService: GradleService,
        debug: Boolean
    ): CallToolResult {
        // --- Argument Parsing ---
        val projectPath = request.arguments.getValue("projectPath").jsonPrimitive.content
        val gradleTasks = request.arguments["gradleTasks"]?.jsonArray?.map { it.jsonPrimitive.content }
            ?.takeIf { it.isNotEmpty() } ?: listOf("test")
        val inputArguments = request.arguments["arguments"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val environmentVariables = request.arguments["environmentVariables"]?.jsonObject
            ?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        val testPatterns = request.arguments["testPatterns"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val includeOutputForPassed = request.arguments["includeOutputForPassed"]?.jsonPrimitive?.booleanOrNull ?: false
        val requestedMaxLogLines = request.arguments["maxLogLines"]?.jsonPrimitive?.intOrNull
        val requestedDefaultMaxLogLines = request.arguments["defaultMaxLogLines"]?.jsonPrimitive?.intOrNull

        val effectiveMaxLogLines = requestedMaxLogLines
            ?: requestedDefaultMaxLogLines
            ?: DEFAULT_TEST_LOG_LINES

        // --- Argument Filtering ---
        val problematicArgs = setOf("--info", "--debug")
        val filteredArguments = inputArguments.filter { arg -> !problematicArgs.contains(arg.split("=").first()) }
        val filteringOccurred = inputArguments.size != filteredArguments.size
        val finalArguments = filteredArguments.toMutableList()
        // Remove existing --tests args and add new ones if specified
        finalArguments.removeAll { it.startsWith("--tests=") }
        testPatterns.forEach { pattern -> finalArguments += "--tests=$pattern" }

        // --- Data Structures for Listener ---
        val nodesMap = ConcurrentHashMap<OperationDescriptor, TestResultNode>()
        val rootNodes = ConcurrentHashMap.newKeySet<TestResultNode>()
        val testOutputMap = ConcurrentHashMap<OperationDescriptor, MutableList<String>>() // Keyed by TEST descriptor

        // --- Overall Build Output Capture (if debug) ---
        // Using ByteArrayOutputStream directly inside the lambda for simplicity here,
        // but could be abstracted further if needed.
        val gradleStdOutStream = if (debug) ByteArrayOutputStream() else null
        val gradleStdErrStream = if (debug) ByteArrayOutputStream() else null

        // --- Hierarchical Listener Definition ---
        val hierarchicalListener = createHierarchicalListener(nodesMap, rootNodes, testOutputMap, includeOutputForPassed, effectiveMaxLogLines, debug)

        val executionConfig = GradleService.BuildExecutionConfig(
            tasks = gradleTasks,
            arguments = finalArguments,
            environmentVariables = environmentVariables,
            progressListeners = listOf(hierarchicalListener to setOf(OperationType.TEST, OperationType.TEST_OUTPUT))
        )

        try {
            val result = gradleService.withConnection(projectPath) { connection ->
                 // Need to wrap executeBuild to handle the specific streams if debug is enabled
                 val buildLauncher = connection.newBuild()
                    .forTasks(*executionConfig.tasks.toTypedArray())
                    .withArguments(*executionConfig.arguments.toTypedArray())
                    .setJvmArguments(*executionConfig.jvmArguments.toTypedArray())
                    .setEnvironmentVariables(executionConfig.environmentVariables)

                 // Attach listener
                 executionConfig.progressListeners.forEach { (listener, types) ->
                     if (types.isEmpty()) buildLauncher.addProgressListener(listener)
                     else buildLauncher.addProgressListener(listener, types)
                 }

                 // Attach streams ONLY if debug is enabled
                 gradleStdOutStream?.let { buildLauncher.setStandardOutput(it) }
                 gradleStdErrStream?.let { buildLauncher.setStandardError(it) }

                 log.info("Executing Gradle test tasks: {} with args: {}", executionConfig.tasks, executionConfig.arguments)

                 var buildException: Throwable? = null
                 val success = try {
                     buildLauncher.run()
                     log.info("Gradle test tasks executed successfully: {}", executionConfig.tasks)
                     true
                 } catch (e: Exception) {
                      // Capture exception details as before
                      log.warn("Gradle test execution failed for tasks: {}. Message: {}", executionConfig.tasks, e.message, e)
                      buildException = e
                      false
                 }

                 // Log full output if debug enabled
                 if (debug) {
                     logFullGradleOutput(gradleStdOutStream, gradleStdErrStream)
                 }

                 // Return a combined result including success status and exception
                  GradleService.BuildResult(success, "", "", buildException) // Output streams handled separately for debug
            }

            // --- Prepare and Return Response ---
            nodesMap.values.forEach { it.descriptor = null } // Cleanup transient field
            val finalHierarchy = rootNodes.toList().sortedBy { it.displayName }
            sortChildrenRecursively(finalHierarchy) // Ensure consistent child order

            val notes = generateNotes(filteringOccurred, testPatterns, includeOutputForPassed, effectiveMaxLogLines, result.success, finalHierarchy)

            val structuredResponse = GradleTestResponse(
                tasksExecuted = gradleTasks,
                arguments = inputArguments, // Report original arguments
                environmentVariables = environmentVariables,
                testHierarchy = finalHierarchy,
                success = result.success, // Overall build success
                notes = notes
            )

            val jsonResponse = json.encodeToString(structuredResponse)
            if (debug) log.debug("Hierarchical Test Result Payload:\n{}", jsonResponse)
            return CallToolResult(content = listOf(TextContent(jsonResponse)))

        } catch (e: Exception) {
            log.error("Error running Gradle hierarchical test tool for path {}", projectPath, e)
            // Log debug output if captured before the exception
            if (debug) logFullGradleOutput(gradleStdOutStream, gradleStdErrStream)

            val errorJson = createErrorResponse(gradleTasks, inputArguments, environmentVariables, e, debug)
            return CallToolResult(content = listOf(TextContent(errorJson)))
        }
    }

     private fun createHierarchicalListener(
         nodesMap: ConcurrentHashMap<OperationDescriptor, TestResultNode>,
         rootNodes: MutableSet<TestResultNode>, // Use MutableSet from ConcurrentHashMap.newKeySet()
         testOutputMap: ConcurrentHashMap<OperationDescriptor, MutableList<String>>,
         includeOutputForPassed: Boolean,
         effectiveMaxLogLines: Int,
         debug: Boolean
    ): ProgressListener {
        return ProgressListener { event ->
            when (event) {
                is TestStartEvent -> handleTestStart(event, nodesMap, rootNodes, debug)
                is TestFinishEvent -> handleTestFinish(event, nodesMap, testOutputMap, includeOutputForPassed, effectiveMaxLogLines, debug)
                is TestOutputEvent -> handleTestOutput(event, nodesMap, testOutputMap, debug)
            }
        }
    }

     private fun handleTestStart(
         event: TestStartEvent,
         nodesMap: ConcurrentHashMap<OperationDescriptor, TestResultNode>,
         rootNodes: MutableSet<TestResultNode>,
         debug: Boolean
     ) {
        val desc = event.descriptor
        val parentDesc = desc.parent

        val nodeType = determineNodeType(desc)
        val newNode = TestResultNode(
            displayName = desc.displayName,
            type = nodeType
        ).apply { descriptor = desc } // Keep descriptor temporarily

        nodesMap[desc] = newNode

        val parentNode = parentDesc?.let { nodesMap[it] }
        if (parentNode != null) {
            parentNode.children.add(newNode)
        } else {
            rootNodes.add(newNode) // Add to root if no tracked parent
        }
        if (debug) log.debug("Start: {} (Type: {}), Parent: {}", newNode.displayName, newNode.type, parentDesc?.displayName ?: "null")
    }

     private fun handleTestFinish(
         event: TestFinishEvent,
         nodesMap: ConcurrentHashMap<OperationDescriptor, TestResultNode>,
         testOutputMap: ConcurrentHashMap<OperationDescriptor, MutableList<String>>,
         includeOutputForPassed: Boolean,
         effectiveMaxLogLines: Int,
         debug: Boolean
     ) {
        val desc = event.descriptor
        val node = nodesMap[desc]
        if (node != null) {
            val result = event.result
            node.outcome = determineOutcome(result)
            node.failureMessage = extractFailureMessage(result)

            // Attach filtered and truncated output if needed
            if (node.outcome == "failed" || includeOutputForPassed) {
                val collectedLines = testOutputMap[desc]?.toList() ?: emptyList()
                node.outputLines = LogUtils.truncateLogLines(collectedLines, effectiveMaxLogLines)
            } else {
                node.outputLines = emptyList() // Ensure empty if not needed
            }

             // Clean up transient descriptor and output map entry
            node.descriptor = null
            testOutputMap.remove(desc) // Clean up memory

            if (debug) log.debug("Finish: {}, Outcome: {}, Output lines attached: {}", node.displayName, node.outcome, node.outputLines.size)
        } else {
            if (debug) log.warn("Finish event for untracked descriptor: {}", desc.displayName)
        }
    }

    private fun handleTestOutput(
        event: TestOutputEvent,
        nodesMap: ConcurrentHashMap<OperationDescriptor, TestResultNode>,
        testOutputMap: ConcurrentHashMap<OperationDescriptor, MutableList<String>>,
        debug: Boolean
    ) {
         val outputDesc = event.descriptor

         val testDesc = outputDesc.parent
         if (testDesc != null && nodesMap[testDesc]?.type == "TEST") {
             val outputLinesList = testOutputMap.computeIfAbsent(testDesc) { CopyOnWriteArrayList() }

             val destination = outputDesc.destination
             val rawMessage = outputDesc.message

             val prefixedLines = rawMessage.lines().map { "[${destination.name}] $it" } // Prefix with StdOut/StdErr
             val filteredLines = prefixedLines.filter { line ->
                 noisePatterns.none { pattern -> pattern.matches(line) }
             }

             if (filteredLines.isNotEmpty()) {
                 outputLinesList.addAll(filteredLines)
             }
         } else {
              if(debug && testDesc != null) log.trace("Ignoring output event for non-TEST descriptor: {} (Parent: {})", outputDesc.displayName, testDesc.displayName)
              else if (debug) log.trace("Ignoring output event without parent test descriptor: {}", outputDesc.displayName)
         }
    }

     private fun determineNodeType(desc: OperationDescriptor): String = when {
        desc is JvmTestOperationDescriptor && desc.jvmTestKind == JvmTestKind.ATOMIC -> "TEST"
        desc is JvmTestOperationDescriptor && desc.methodName == null -> "CLASS" // Heuristic
        else -> "SUITE"
    }

     private fun determineOutcome(result: OperationResult): String = when (result) {
        is SuccessResult -> "passed"
        is FailureResult -> "failed"
        is SkippedResult -> "skipped"
        else -> "unknown"
    }

    private fun extractFailureMessage(result: OperationResult): String? {
        if (result !is FailureResult) return null

         // Try to find the most relevant failure
         val primaryFailure = result.failures.firstOrNull { f ->
             val msg = f.message?.lowercase() ?: ""
             val desc = f.description?.lowercase() ?: ""
             msg.contains("assertionfailed") || msg.contains("comparisonfailure") ||
             msg.contains("asserterror") || msg.contains("exception") || desc.contains("failed")
         } ?: result.failures.firstOrNull() // Fallback

         return primaryFailure?.let { failure ->
              val message = failure.message ?: "No specific error message."
              // Limit description snippet to avoid huge messages
              val descriptionSnippet = failure.description?.lines()
                  ?.mapNotNull { it.trim().ifEmpty { null } }
                  ?.take(5)?.joinToString("\n  ") ?: ""
              buildString {
                 append(message)
                 if (descriptionSnippet.isNotEmpty() && !message.contains(descriptionSnippet.lineSequence().first().take(50))) {
                     append("\n  ...\n  ").append(descriptionSnippet)
                 }
             }.take(2048) // Hard limit on total failure message length
         } ?: "Unknown test failure reason"
    }

    private fun sortChildrenRecursively(nodes: List<TestResultNode>) {
        nodes.forEach { node ->
            node.children.sortBy { it.displayName }
            sortChildrenRecursively(node.children)
        }
    }

     private fun logFullGradleOutput(stdOutStream: ByteArrayOutputStream?, stdErrStream: ByteArrayOutputStream?) {
        val gradleStdOut = stdOutStream?.toString(StandardCharsets.UTF_8)?.trim() ?: ""
        val gradleStdErr = stdErrStream?.toString(StandardCharsets.UTF_8)?.trim() ?: ""

        if (gradleStdOut.isNotEmpty() || gradleStdErr.isNotEmpty()) {
            log.debug("--- Full Gradle Build Output (for debugging) ---")
            if (gradleStdOut.isNotEmpty()) log.debug("[Gradle StdOut]:\n{}", gradleStdOut)
            if (gradleStdErr.isNotEmpty()) log.debug("[Gradle StdErr]:\n{}", gradleStdErr)
            log.debug("--- End Full Gradle Build Output ---")
        } else {
             log.debug("--- No overall Gradle build output captured ---")
        }
    }

     private fun generateNotes(
         filteringOccurred: Boolean,
         testPatterns: List<String>,
         includeOutputForPassed: Boolean,
         effectiveMaxLogLines: Int,
         buildSuccess: Boolean,
         hierarchy: List<TestResultNode>
     ): String? {
        return buildString {
            if (filteringOccurred) append("Note: Verbose Gradle arguments (--info/--debug) were filtered out. ")
            if (testPatterns.isNotEmpty()) append("Applied test filters: ${testPatterns.joinToString()}. ")
            if (!includeOutputForPassed) append("Output lines included only for failed tests. ")
            if (effectiveMaxLogLines > 0) append("Output lines per test limited to ~$effectiveMaxLogLines (keeps first/last lines). ")
            else append("Output lines per test are unlimited. ")
            if (!buildSuccess && hierarchy.any { it.outcome == "passed" || it.outcome == "skipped" }) {
                 append(" The overall Gradle build failed, possibly due to issues outside reported test execution (e.g., compilation errors, other task failures).")
            } else if (buildSuccess && hierarchy.isNotEmpty() && hierarchy.all { it.outcome == "failed" }) {
                 append(" The overall Gradle build succeeded, but all reported tests failed.")
            }
        }.trim().ifEmpty { null }
    }

    private fun createErrorResponse(
         tasks: List<String>,
         args: List<String>,
         envVars: Map<String, String>,
         error: Exception,
         debug: Boolean
     ): String {
         // Create a simple map for the error JSON structure
         val errorMap = mapOf(
             "error" to "Failed to execute Gradle test task.",
             "message" to error.message,
             "success" to false,
             "tasks_executed" to tasks,
             "arguments" to args,
             "environment_variables" to envVars,
             "test_hierarchy" to emptyList<TestResultNode>(), // Ensure empty list on error
             "notes" to "An error occurred during test execution setup or processing.",
             "details" to if (debug) error.stackTraceToString() else "Gradle connection/setup or listener processing failed."
         )
         return json.encodeToString(errorMap)
     }
}
