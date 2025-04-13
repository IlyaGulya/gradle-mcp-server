# Gradle MCP Server

A Model Context Protocol (MCP) server that enables AI tools to interact with Gradle projects programmatically. It uses the [Gradle Tooling API](https://docs.gradle.org/current/userguide/tooling_api.html) to query project information and execute tasks.

## Features

Provides tools for:

-   **Inspecting Projects**: Retrieve detailed, structured information about a Gradle project, including:
    -   Build structure (root project, subprojects)
    -   Available tasks (in the root project)
    -   Build environment details (Gradle version, Java version, JVM args)
    -   Root project details (name, path, description, build script path)
    -   Allows selective querying of information categories.
-   **Executing Tasks**: Run specific Gradle tasks (e.g., `clean`, `build`, `assemble`) with custom arguments, JVM arguments, and environment variables. Returns formatted text output including stdout/stderr and status.
-   **Running Tests Hierarchically**: Execute Gradle test tasks (e.g., `test`) and receive detailed, structured results in a hierarchical JSON format (Suite -> Class -> Method). Includes:
    -   Outcome (passed, failed, skipped) for each node.
    -   Failure messages and filtered/truncated output lines (stdout/stderr) primarily for failed tests (configurable).
    -   Support for test filtering via patterns (`--tests`).
    -   Options to control output inclusion and log line limits.

## Requirements

-   JDK 17 or higher
-   For command-line installation:
    -   Linux/macOS: `curl`
    -   Windows: PowerShell 5+

## Installation

### Recommended Method: Command-Line Download

This method downloads the server JAR to a standard location in your home directory.

**Linux / macOS (requires `curl`):**

```bash
# Downloads gradle-mcp-server-all.jar to ~/mcp-servers/gradle-mcp-server/
TARGET_DIR="$HOME/mcp-servers/gradle-mcp-server" && mkdir -p "$TARGET_DIR" && curl -fSL -o "$TARGET_DIR/gradle-mcp-server-all.jar" "https://github.com/IlyaGulya/gradle-mcp-server/releases/latest/download/gradle-mcp-server-all.jar" && echo "Downloaded to '$TARGET_DIR'." || echo "Download failed."
```

**Windows (PowerShell 5+):**

```powershell
# Downloads gradle-mcp-server-all.jar to %USERPROFILE%\mcp-servers\gradle-mcp-server\
$targetDir = Join-Path $env:USERPROFILE "mcp-servers\gradle-mcp-server"; if (-not (Test-Path $targetDir)) { New-Item -ItemType Directory -Path $targetDir -Force | Out-Null }; $outFile = Join-Path $targetDir "gradle-mcp-server-all.jar"; Write-Host "Downloading..."; Invoke-WebRequest -Uri "https://github.com/IlyaGulya/gradle-mcp-server/releases/latest/download/gradle-mcp-server-all.jar" -OutFile $outFile -ErrorAction Stop; Write-Host "Downloaded to '$targetDir'."
```

### Alternative Method: Manual Download

1.  Go to the [GitHub Releases page](https://github.com/IlyaGulya/gradle-mcp-server/releases).
2.  Download the `gradle-mcp-server-all.jar` asset from the latest release.
3.  Save the downloaded JAR file to a stable location. We recommend:
    *   macOS / Linux: `~/mcp-servers/gradle-mcp-server/`
    *   Windows: `%USERPROFILE%\mcp-servers\gradle-mcp-server\`
    (Create the directory if it doesn't exist).

## MCP Client Configuration

To use this server with an MCP client (like the VSCode extension or Claude Desktop app), you need to add its configuration to the client's settings file.

1.  **Locate the settings file:**
    *   VSCode Extension (Example for macOS): `/Users/<YourUsername>/Library/Application Support/VSCodium/User/globalStorage/saoudrizwan.claude-dev/settings/cline_mcp_settings.json` (Adjust path for standard VSCode or other OS).
    *   Claude Desktop App (Example for macOS): `~/Library/Application Support/Claude/claude_desktop_config.json` (Adjust path for other OS).

2.  **Add the server configuration:** Edit the JSON file and add the following entry inside the `mcpServers` object. **Replace `<absolute_path_to_home>` with the actual absolute path to your home directory.**

    ```json
    {
      "mcpServers": {
        "gradle-mcp-server": {
          "command": "java",
          "args": [
            "-jar",
            "<absolute_path_to_home>/mcp-servers/gradle-mcp-server/gradle-mcp-server-all.jar"
          ],
          "env": {},
          "disabled": false,
          "autoApprove": []
        }
      }
    }
    ```

## Usage

1.  After adding the configuration, **restart your MCP client** (e.g., reload the VSCode window or restart the Claude app).
2.  The "Gradle MCP Server" and its tools (listed below) should now be available for use within the client. The server runs automatically via stdio when needed by the client.

## Available Tools

The server exposes the following tools via the Model Context Protocol:

1.  **`Get Gradle Project Info`**
    -   **Description**: Retrieves specific details about a Gradle project, returning structured JSON. Allows requesting only necessary information categories (`buildStructure`, `tasks`, `environment`, `projectDetails`). If `requestedInfo` is omitted, all categories are fetched.
    -   **Key Inputs**:
        -   `projectPath` (string, required): Absolute path to the Gradle project root.
        -   `requestedInfo` (array of strings, optional): List of categories to retrieve (e.g., `["tasks", "environment"]`).
    -   **Output**: JSON object (`GradleProjectInfoResponse`) containing the requested data fields and potential errors.

2.  **`Execute Gradle Task`**
    -   **Description**: Executes general Gradle tasks (like `build`, `clean`). **Not recommended for running tests if detailed results are needed** (use the test tool instead). Returns formatted text output summarizing execution and including captured stdout/stderr.
    -   **Key Inputs**:
        -   `projectPath` (string, required): Absolute path to the Gradle project root.
        -   `tasks` (array of strings, required): List of task names to execute (e.g., `["clean", "assemble"]`).
        -   `arguments` (array of strings, optional): Gradle command-line arguments (e.g., `["--info", "-PmyProp=value"]`).
        -   `jvmArguments` (array of strings, optional): JVM arguments for Gradle (e.g., `["-Xmx4g"]`).
        -   `environmentVariables` (object, optional): Environment variables for the build (e.g., `{"CI": "true"}`).
    -   **Output**: Formatted text response with execution summary, final status (`Success`/`Failure`), and combined stdout/stderr.

3.  **`Run Gradle Tests`**
    -   **Description**: Executes Gradle test tasks and returns results as a structured JSON hierarchy (Suite > Class > Test). Filters/truncates output lines by default, focusing on failures. Provides options to include output for passed tests and control log limits.
    -   **Key Inputs**:
        -   `projectPath` (string, required): Absolute path to the Gradle project root.
        -   `gradleTasks` (array of strings, optional): Test tasks to run (defaults to `["test"]`).
        -   `arguments` (array of strings, optional): Additional Gradle arguments (verbose flags like `--info`/`--debug` are filtered out).
        -   `environmentVariables` (object, optional): Environment variables for the test execution.
        -   `testPatterns` (array of strings, optional): Test filter patterns passed via `--tests` (e.g., `["*.MyTestClass"]`).
        -   `includeOutputForPassed` (boolean, optional): Set to `true` to include output for passed tests (default `false`).
        -   `maxLogLines` (integer, optional): Override the default limit on output lines per test (0 for unlimited).
        -   `defaultMaxLogLines` (integer, optional): Set the default output line limit (defaults internally to 100).
    -   **Output**: JSON object (`GradleHierarchicalTestResponse`) containing execution details, overall build success status, informative notes, and the `test_hierarchy` tree. Each node includes display name, type, outcome, failure message (if any), filtered/truncated output lines, and children.

## Development

### Building from Source

If you want to build the server yourself:

1.  Clone the repository.
2.  Ensure you have JDK 17 or higher installed.
3.  Run the build command:
    ```bash
    ./gradlew shadowJar
    ```
4.  The self-contained JAR (`gradle-mcp-server-<version>-all.jar`) will be created in the `build/libs/` directory. You can then configure your MCP client to use this JAR (remember to use the correct absolute path and version in the configuration).

### Running Locally (for Testing)

You can run the built JAR directly from the command line for testing purposes. The server communicates over stdio by default.

```bash
# Run the packaged JAR in stdio mode
java -jar build/libs/gradle-mcp-server-<version>-all.jar

# Run with specific arguments (see Configuration section)
java -jar build/libs/gradle-mcp-server-<version>-all.jar --sse 8080 --debug
```

## Configuration (Command-Line Arguments)

When running the server JAR directly (primarily for testing/development), its behavior can be controlled via command-line arguments:

-   `--stdio`: (Default) Use standard input/output for MCP communication.
-   `--sse [port]`: Run as an SSE server on the specified `port` (defaults to 3001 if port is omitted). Connect MCP clients (like the Anthropic Console Inspector) to `http://localhost:<port>/sse`.
-   `--debug`: Enable verbose logging on the server console.

## Dependencies

-   [Gradle Tooling API](https://docs.gradle.org/current/userguide/tooling_api.html) (Version specified in `build.gradle.kts`)
-   [Anthropic MCP Kotlin SDK](https://github.com/wiremock-inc/anthropic-mcp-kotlin-sdk)
-   [Ktor](https://ktor.io/) (for SSE server mode)
-   [Logback](https://logback.qos.ch/) (for logging)
