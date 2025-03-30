package me.gulya.gradle.mcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.gradle.tooling.events.OperationDescriptor

// Note: This was defined but unused in the original Execute Task tool response.
// Keeping it here if needed later, but hierarchical response is primary.
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
data class GradleTestResponse(
    @SerialName("tasks_executed")
    val tasksExecuted: List<String>,
    @SerialName("arguments")
    val arguments: List<String>, // Original input arguments
    @SerialName("environment_variables")
    val environmentVariables: Map<String, String>,
    @SerialName("test_hierarchy")
    val testHierarchy: List<TestResultNode>, // List of root nodes
    @SerialName("success")
    val success: Boolean, // Overall build success
    @SerialName("notes")
    val notes: String? = null
)

@Serializable
data class TestResultNode(
    @SerialName("display_name")
    val displayName: String,
    @SerialName("type")
    val type: String, // e.g., "SUITE", "CLASS", "TEST"
    @SerialName("outcome")
    var outcome: String = "unknown", // Updated on finish: passed, failed, skipped
    @SerialName("failure_message")
    var failureMessage: String? = null, // Set on failure
    @SerialName("output_lines")
    var outputLines: List<String> = emptyList(), // Populated from TestOutputEvent for atomic tests
    @SerialName("children")
    val children: MutableList<TestResultNode> = mutableListOf()
) {
    @kotlinx.serialization.Transient // Exclude from serialization
    var descriptor: OperationDescriptor? = null // Transient field used during processing
}

// --- Helper function for log truncation (moved here for proximity) ---
object LogUtils {
    fun truncateLogLines(lines: List<String>, maxLines: Int): List<String> {
        if (maxLines <= 0 || lines.size <= maxLines) {
            return lines // No truncation needed or unlimited
        }
        if (maxLines == 1) {
            return listOf("... (output truncated, only 1 line allowed)") // Edge case
        }

        val headCount = maxLines / 2
        val tailCount = maxLines - headCount // Ensures total is maxLines even for odd numbers

        // Need at least 1 line for head, 1 for tail, 1 for marker if truncating
        if (headCount == 0 || tailCount == 0) {
             // Handle cases where maxLines is very small (e.g., 2 or 3)
             val marker = if (lines.size > maxLines) "... (${lines.size - maxLines} lines truncated) ..." else null
             return lines.take(maxLines) + listOfNotNull(marker)
        }

        val head = lines.take(headCount)
        val tail = lines.takeLast(tailCount)
        val marker = if (lines.size > maxLines) "... (${lines.size - headCount - tailCount} lines truncated) ..." else null

        return head + listOfNotNull(marker) + tail
    }
}
