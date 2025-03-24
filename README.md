# MCP Gradle Tools

A Model Context Protocol (MCP) server to enable AI tools to interact with Gradle projects programmatically.
Uses [Gradle Tooling API](https://docs.gradle.org/current/userguide/tooling_api.html) under the hood

## Features

- **Project Information**: Retrieve metadata about Gradle projects
- **Task Execution**: Run Gradle tasks remotely
- **Test Runner**: Execute tests in Gradle projects

## Requirements

- JDK 21 or higher

## Getting Started

### Build

```bash
./gradlew build
```

### Run

```bash
# Run in stdio mode (default)
./gradlew run

# Run as SSE server
./gradlew run --args="--mode sse"
```

### Package

```bash
./gradlew shadowJar
```

## Configuration

The server can be run in different modes:
- `stdio` - Standard input/output mode (default)
- `sse` - Server-Sent Events mode
