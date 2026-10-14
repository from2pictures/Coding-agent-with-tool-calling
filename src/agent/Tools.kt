package agent

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import entity.ProposedCall
import entity.ToolResult
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ToolSpec(val name: String, val description: String, val schema: JsonObject)

object Tools {
    private fun fields(vararg names: String): JsonObject = obj(json.toJson(mapOf(
        "type" to "object", "properties" to names.associateWith { mapOf("type" to "string") },
        "required" to names.toList(), "additionalProperties" to false
    )))
    val specs = listOf(
        ToolSpec("list_files", "List permitted files recursively. Use path '.' for workspace.", fields("path")),
        ToolSpec("read_file", "Read a UTF-8 file inside workspace.", fields("path")),
        ToolSpec("write_file", "Create or replace one UTF-8 file. Never modify protected tests.", fields("path", "content")),
        ToolSpec("delete_file", "Delete one file; always requires user approval.", fields("path")),
        ToolSpec("Bash", "Execute an exact allowlisted command through Bash. Returns stdout, stderr and exitCode.", fields("command", "cwd")),
        ToolSpec("submit_plan", "Submit a plan with ordered tasks and runtime-verifiable checks. Call alone in a response.", obj("""
          {"type":"object","additionalProperties":false,"required":["goal","steps","risks","assumptions","acceptance"],"properties":{
            "goal":{"type":"string","minLength":1},"acceptance":{"type":"string","minLength":1},
            "risks":{"type":"array","items":{"type":"string"}},"assumptions":{"type":"array","items":{"type":"string"}},
            "steps":{"type":"array","minItems":1,"maxItems":20,"items":{"type":"object","additionalProperties":false,
              "required":["id","description","dependencies","tools","check"],"properties":{
                "id":{"type":"string","minLength":1},"description":{"type":"string","minLength":1},
                "dependencies":{"type":"array","items":{"type":"string"}},
                "tools":{"type":"array","minItems":1,"items":{"type":"string","enum":["list_files","read_file","write_file","delete_file","Bash"]}},
                "check":{"type":"object","additionalProperties":false,"required":["kind","path"],"properties":{
                  "kind":{"type":"string","enum":["file_read","file_written","file_deleted","tests_passed"]},"path":{"type":"string"}
                }}
              }
            }}
          }}
        """)),
        ToolSpec("finish_task", "Complete the current task. Copy evidence_call_id exactly from facts.call_id of its successful tool result, never a task or message number. Call alone.", fields("evidence_call_id"))
    )
    fun sideEffect(name: String) = name in setOf("write_file", "delete_file", "Bash")
    fun repeatable(name: String) = name in setOf("list_files", "read_file", "write_file", "delete_file", "submit_plan", "finish_task")
    fun validate(call: ProposedCall): JsonObject {
        require(call.id.isNotBlank() && call.id.length <= 256) { "Invalid tool_call_id" }
        val spec = specs.find { it.name == call.name } ?: error("Unknown tool: ${call.name}")
        require(call.arguments.toByteArray().size <= 524288) { "Arguments too large" }
        return obj(call.arguments).also { validateSchema(it, spec.schema, "arguments") }
    }

    private fun validateSchema(value: JsonElement, schema: JsonObject, at: String) {
        val type = schema["type"].asString
        when (type) {
            "object" -> {
                require(value.isJsonObject) { "$at must be object" }
                val properties = schema.getAsJsonObject("properties")
                schema.getAsJsonArray("required")?.forEach { require(value.asJsonObject.has(it.asString)) { "$at.${it.asString} is required" } }
                value.asJsonObject.entrySet().forEach { (key, child) ->
                    require(properties.has(key)) { "Unknown field $at.$key" }
                    validateSchema(child, properties.getAsJsonObject(key), "$at.$key")
                }
            }
            "array" -> {
                require(value.isJsonArray) { "$at must be array" }
                require(value.asJsonArray.size() in (schema["minItems"]?.asInt ?: 0)..(schema["maxItems"]?.asInt ?: 100)) { "$at has invalid size" }
                value.asJsonArray.forEach { validateSchema(it, schema.getAsJsonObject("items"), at) }
            }
            "string" -> {
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$at must be string" }
                require(value.asString.length >= (schema["minLength"]?.asInt ?: 0)) { "$at must not be empty" }
                schema.getAsJsonArray("enum")?.let { require(it.any { choice -> choice.asString == value.asString }) { "$at has invalid value" } }
            }
            else -> error("Unsupported schema type: $type")
        }
    }

    fun execute(call: ProposedCall, policy: Policy, capacity: Int): ToolResult {
        val args = validate(call)
        fun path(write: Boolean = false): Path = policy.path(args["path"].asString, write)
        return when (call.name) {
            "list_files" -> {
                val start = path()
                require(Files.isDirectory(start)) { "Not a directory" }
                val files = Journal(policy).snapshot().keys.filter { policy.root.resolve(it).startsWith(start) }
                ToolResult(output = utf8Prefix(files.joinToString("\n"), capacity), truncated = files.joinToString("\n").toByteArray().size > capacity)
            }
            "read_file" -> {
                val p = path()
                require(Files.isRegularFile(p) && Files.size(p) <= policy.config.maxFileBytes) { "Not a file or file too large" }
                val bytes = Files.readAllBytes(p)
                ToolResult(output = utf8Prefix(String(bytes, Charsets.UTF_8), capacity), truncated = bytes.size > capacity,
                    facts = linkedMapOf("path" to policy.root.relativize(p).toString(), "hash" to sha(bytes)))
            }
            "write_file" -> {
                val p = path(true)
                require(Files.isDirectory(p.parent)) { "Parent directory must exist" }
                val bytes = args["content"].asString.toByteArray()
                require(bytes.size <= policy.config.maxFileBytes)
                Files.write(p, bytes)
                ToolResult(output = "File written", facts = linkedMapOf("path" to policy.root.relativize(p).toString(), "hash" to sha(bytes)))
            }
            "delete_file" -> {
                val p = path(true)
                Files.deleteIfExists(p)
                ToolResult(output = "File absent", facts = linkedMapOf("path" to policy.root.relativize(p).toString()))
            }
            "Bash" -> bash(args["command"].asString, policy.path(args["cwd"].asString), policy, capacity)
            else -> error("Control tool must be handled by runtime")
        }
    }

    private fun bash(command: String, cwd: Path, policy: Policy, capacity: Int): ToolResult {
        policy.command(command)
        val builder = ProcessBuilder("/bin/bash", "--noprofile", "--norc", "-c", "exec $command").directory(cwd.toFile())
        builder.environment().clear()
        builder.environment().putAll(mapOf("PATH" to "/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin", "PYTHONDONTWRITEBYTECODE" to "1", "PYTHONIOENCODING" to "utf-8", "LC_ALL" to "C"))
        val process = builder.start()
        process.outputStream.close()
        val pool = Executors.newFixedThreadPool(2) { Thread(it, "bash-output").apply { isDaemon = true } }
        val lock = Any()
        var remaining = capacity
        var truncated = false
        fun drain(input: java.io.InputStream) = pool.submit<String> {
            val output = ByteArrayOutputStream()
            input.use {
                val buffer = ByteArray(4096)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    synchronized(lock) {
                        val keep = minOf(count, remaining)
                        output.write(buffer, 0, keep)
                        remaining -= keep
                        if (keep < count) truncated = true
                    }
                }
            }
            output.toString(Charsets.UTF_8)
        }
        val stdout = drain(process.inputStream)
        val stderr = drain(process.errorStream)
        try {
            val finished = process.waitFor(policy.config.timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
            val out = stdout.get(5, TimeUnit.SECONDS)
            val err = stderr.get(5, TimeUnit.SECONDS)
            val exit = if (finished) process.exitValue() else 124
            return ToolResult(exit == 0, "", if (finished) err else "$err\nCommand timed out", exit, truncated,
                linkedMapOf("command" to command, "cwd" to policy.root.relativize(cwd).toString().ifEmpty { "." }), stdout = out)
        } finally {
            process.descendants().forEach { it.destroyForcibly() }
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close(); process.errorStream.close()
            pool.shutdownNow()
        }
    }
}
