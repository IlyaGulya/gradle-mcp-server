import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Builds the overall input schema
class InputSchemaBuilder {
    private val properties = mutableMapOf<String, JsonObject>()
    private val required = mutableListOf<String>()

    // Defines a property with a name and its attributes
    fun property(name: String, block: PropertyBuilder.() -> Unit) {
        val propertyBuilder = PropertyBuilder()
        propertyBuilder.block()
        properties[name] = propertyBuilder.build()
    }

    fun requiredProperty(name: String, block: PropertyBuilder.() -> Unit) {
        property(name, block)
        required(name)
    }

    // Specifies required properties
    fun required(vararg names: String) {
        required.addAll(names)
    }

    // Constructs the final Tool.Input object
    fun build(): Tool.Input {
        return Tool.Input(
            properties = JsonObject(properties),
            required = required.toList()
        )
    }
}

// Builds individual property attributes
class PropertyBuilder {
    private val attributes = mutableMapOf<String, JsonElement>()

    fun type(value: String) {
        attributes["type"] = JsonPrimitive(value)
    }

    fun description(value: String) {
        attributes["description"] = JsonPrimitive(value)
    }

    // Constructs the JsonObject for a single property
    fun build(): JsonObject {
        return JsonObject(attributes)
    }
}

// Top-level DSL function
fun inputSchema(block: InputSchemaBuilder.() -> Unit): Tool.Input {
    val builder = InputSchemaBuilder()
    builder.block()
    return builder.build()
}
