package agent

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import entity.ToolResult
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

val json = GsonBuilder().disableHtmlEscaping().create()
fun obj(text: String): JsonObject = JsonReader(StringReader(text)).use { reader ->
    reader.strictness = Strictness.STRICT
    val value = JsonParser.parseReader(reader)
    require(reader.peek() == JsonToken.END_DOCUMENT && value.isJsonObject) { "Expected a single JSON object" }
    value.asJsonObject
}
fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun canonical(value: JsonElement): String = when {
    value.isJsonObject -> value.asJsonObject.entrySet().sortedBy { it.key }.joinToString(",", "{", "}") { json.toJson(it.key) + ":" + canonical(it.value) }
    value.isJsonArray -> value.asJsonArray.joinToString(",", "[", "]") { canonical(it) }
    else -> value.toString()
}
fun sameArguments(a: String, b: String): Boolean = try { canonical(obj(a)) == canonical(obj(b)) } catch (_: Exception) { a == b }
fun utf8Prefix(text: String, limit: Int): String {
    val bytes = text.toByteArray(UTF_8)
    if (bytes.size <= limit) return text
    var n = limit.coerceAtLeast(0)
    while (n > 0 && (bytes[n].toInt() and 0xc0) == 0x80) n--
    return String(bytes, 0, n, UTF_8)
}
fun fit(result: ToolResult, capacity: Int): ToolResult {
    require(capacity >= 1024)
    while (json.toJson(result).toByteArray(UTF_8).size > capacity) {
        result.truncated = true
        if (!result.stdout.isNullOrEmpty() && result.stdout!!.length >= maxOf(result.output.length, result.stderr.length))
            result.stdout = utf8Prefix(result.stdout!!, result.stdout!!.toByteArray(UTF_8).size / 2)
        else if (result.output.length >= result.stderr.length && result.output.isNotEmpty())
            result.output = utf8Prefix(result.output, result.output.toByteArray(UTF_8).size / 2)
        else if (result.stderr.isNotEmpty()) result.stderr = utf8Prefix(result.stderr, result.stderr.toByteArray(UTF_8).size / 2)
        else { result.facts.clear(); break }
    }
    return result
}
