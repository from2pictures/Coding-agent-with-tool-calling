package agent

import com.google.gson.reflect.TypeToken
import domain.ExecutionMode
import entity.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import kotlin.system.exitProcess

private const val HELP = """
Coding agent — Kotlin/JVM 21 + MongoDB
  start --task TEXT [--workspace workspace] [--policy policy.json] [--mode safe|auto] [--plan]
        [--script examples/demo.json] [--limits limits.json]
  status RUN_ID
  resume RUN_ID
  plan RUN_ID approve|reject
  action RUN_ID approve|deny [--reason TEXT]
  recovery-diff RUN_ID
  recover RUN_ID retry|fail|abort [--adopt yes|no]
  diff RUN_ID
  rollback RUN_ID

MongoDB: MONGODB_URI, MONGODB_DATABASE (replica set required).
Model: OPENAI_BASE_URL, OPENAI_API_KEY, OPENAI_MODEL.
--script replaces only model responses; the real runtime, tools and MongoDB still run.
"""

private class Arguments(args: Array<String>) {
    val positional = mutableListOf<String>()
    val options = mutableMapOf<String, String>()
    init {
        var i = 0
        while (i < args.size) {
            val word = args[i++]
            if (!word.startsWith("--")) positional += word
            else {
                require(word in setOf("--task", "--workspace", "--policy", "--mode", "--plan", "--script", "--limits", "--reason", "--adopt")) { "Unknown option $word" }
                if (word == "--plan") options[word] = "true"
                else { require(i < args.size) { "Missing value for $word" }; options[word] = args[i++] }
            }
        }
    }
}

class ScriptModel(filename: Path) : Model {
    private val replies: List<Message> = json.fromJson(Files.readString(filename), object : TypeToken<List<Message>>() {}.type)
    override fun reply(messages: List<Message>, tools: List<ToolSpec>): Message =
        replies.getOrNull(messages.count { it.role == "assistant" }) ?: error("Script has no more model responses")
}

private fun summary(run: AgentRun, runtime: AgentRuntime) {
    println("run_id=${run.id} phase=${run.phase} status=${run.status}")
    println("budget=${json.toJson(run.spent)} / ${json.toJson(run.limits)}")
    println(runtime.checklist(run))
    if (run.note.isNotBlank()) println(run.note)
    when (run.status) {
        domain.RunStatus.WAITING_PLAN -> {
            println(json.toJson(run.plan))
            println("Next: plan ${run.id} approve|reject")
        }
        domain.RunStatus.WAITING_ACTION -> println("Next: action ${run.id} approve|deny")
        domain.RunStatus.UNCERTAIN -> println("Next: recovery-diff ${run.id}; recover ${run.id} retry|fail|abort --adopt yes|no")
        else -> Unit
    }
}

fun main(raw: Array<String>) {
    if (raw.isEmpty() || raw[0] in setOf("help", "--help", "-h")) { println(HELP); return }
    try {
        val args = Arguments(raw)
        val command = args.positional.first()
        require(command in setOf("start", "status", "resume", "plan", "action", "recover", "recovery-diff", "diff", "rollback")) { "Unknown command: $command" }
        val uri = System.getenv("MONGODB_URI") ?: "mongodb://localhost:27017/?replicaSet=rs0&directConnection=true"
        val database = System.getenv("MONGODB_DATABASE") ?: "coding_agent"
        MongoStore(uri, database).use { store ->
            val existing = if (command != "start") store.load(args.positional.getOrNull(1) ?: error("Missing run_id")) else null
            val script = args.options["--script"] ?: existing?.scriptPath
            val model = if (script == null) ChatModel() else ScriptModel(Path.of(script))
            val runtime = AgentRuntime(store, model, fault = { point, call ->
                if (System.getenv("AGENT_CRASH_POINT") == point && System.getenv("AGENT_CRASH_CALL") == call.id) {
                    println("Simulated crash: $point ${call.id}"); System.out.flush(); Runtime.getRuntime().halt(73)
                }
            })
            val workspace = existing?.workspaceRoot?.let(Path::of) ?: Path.of(args.options["--workspace"] ?: "workspace").toRealPath()
            val policy = existing?.policyPath?.let(Path::of) ?: Path.of(args.options["--policy"] ?: "policy.json").toRealPath()
            val lockDir = policy.parent.resolve(".agent-locks")
            require(!lockDir.startsWith(workspace)) { "Lock directory must be outside workspace" }
            Files.createDirectories(lockDir)
            FileChannel.open(lockDir.resolve(sha(workspace.toString().toByteArray()) + ".lock"), CREATE, WRITE).use { channel ->
                val lock = channel.tryLock() ?: error("Another process is working on this workspace")
                lock.use {
                    when (command) {
                        "start" -> {
                            val mode = when (args.options["--mode"] ?: "safe") {
                                "safe" -> ExecutionMode.SAFE
                                "auto" -> ExecutionMode.AUTO
                                else -> error("Mode must be safe or auto")
                            }
                            val limits = args.options["--limits"]?.let { json.fromJson(Files.readString(Path.of(it)), Limits::class.java) } ?: Limits()
                            val run = runtime.create(args.options["--task"] ?: error("Missing --task"), workspace, policy, mode, args.options.containsKey("--plan"), limits)
                            if (script != null) { run.scriptPath = Path.of(script).toRealPath().toString(); store.save(run) }
                            println("run_id=${run.id}"); System.out.flush()
                            summary(runtime.drive(run.id.toString()), runtime)
                        }
                        "status" -> summary(store.load(existing!!.id.toString()), runtime)
                        "resume" -> summary(runtime.drive(existing!!.id.toString()), runtime)
                        "plan", "action" -> {
                            val decision = args.positional.getOrNull(2) ?: error("Missing decision")
                            if (command == "plan") {
                                require(decision in setOf("approve", "reject"))
                                runtime.decidePlan(existing!!.id.toString(), decision == "approve")
                            } else {
                                require(decision in setOf("approve", "deny"))
                                runtime.decideAction(existing!!.id.toString(), decision == "approve", args.options["--reason"] ?: "User declined")
                            }
                            summary(runtime.drive(existing.id.toString()), runtime)
                        }
                        "recover" -> {
                            val adopt = args.options["--adopt"]?.let { require(it in setOf("yes", "no")); it == "yes" }
                            runtime.recover(existing!!.id.toString(), args.positional.getOrNull(2) ?: error("Missing decision"), adopt)
                            summary(runtime.drive(existing.id.toString()), runtime)
                        }
                        "recovery-diff" -> println(runtime.recoveryDiff(existing!!.id.toString()))
                        "diff" -> println(Journal.diff(existing!!.journal).ifEmpty { "No owned changes" })
                        "rollback" -> summary(runtime.rollback(existing!!.id.toString()), runtime)
                    }
                }
            }
        }
    } catch (e: Exception) {
        System.err.println("Error: ${e.message}")
        exitProcess(1)
    }
}
