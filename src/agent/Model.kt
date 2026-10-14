package agent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import entity.Message
import entity.ProposedCall
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

fun interface Model { fun reply(messages: List<Message>, tools: List<ToolSpec>): Message }

class ChatModel(
    private val baseUrl: String = System.getenv("OPENAI_BASE_URL") ?: "http://localhost:11434/v1",
    private val apiKey: String = System.getenv("OPENAI_API_KEY") ?: "ollama",
    private val model: String = System.getenv("OPENAI_MODEL") ?: "qwen3:4b"
) : Model {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    override fun reply(messages: List<Message>, tools: List<ToolSpec>): Message {
        val body = JsonObject().apply {
            addProperty("model", model)
            add("messages", JsonArray().apply {
                messages.forEach { message -> add(JsonObject().apply {
                    addProperty("role", message.role)
                    addProperty("content", message.content)
                    message.callId?.let { addProperty("tool_call_id", it) }
                    if (message.calls.isNotEmpty()) add("tool_calls", JsonArray().apply {
                        message.calls.forEach { call -> add(JsonObject().apply {
                            addProperty("id", call.id); addProperty("type", "function")
                            add("function", JsonObject().apply { addProperty("name", call.name); addProperty("arguments", call.arguments) })
                        }) }
                    })
                }) }
            })
            add("tools", JsonArray().apply {
                tools.forEach { spec -> add(JsonObject().apply {
                    addProperty("type", "function")
                    add("function", JsonObject().apply {
                        addProperty("name", spec.name); addProperty("description", spec.description); add("parameters", spec.schema)
                    })
                }) }
            })
            addProperty("tool_choice", "auto")
        }
        val request = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/chat/completions"))
            .timeout(Duration.ofSeconds(120)).header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val bytes = response.body().use { it.readNBytes(1048577) }
        require(bytes.size <= 1048576) { "Model response exceeds 1 MiB" }
        require(response.statusCode() in 200..299) { "Model HTTP ${response.statusCode()}; check URL, model and credentials" }
        val choice = obj(String(bytes)).getAsJsonArray("choices").first().asJsonObject
        require(choice["finish_reason"]?.asString in setOf("stop", "tool_calls")) { "Incomplete or refused model response" }
        val answer = choice.getAsJsonObject("message")
        val calls = answer.getAsJsonArray("tool_calls")?.map {
            val call = it.asJsonObject
            require(call["type"].asString == "function")
            ProposedCall(call["id"].asString, call.getAsJsonObject("function")["name"].asString, call.getAsJsonObject("function")["arguments"].asString)
        } ?: emptyList()
        return Message("assistant", answer["content"]?.takeUnless { it.isJsonNull }?.asString ?: "", calls)
    }
}
