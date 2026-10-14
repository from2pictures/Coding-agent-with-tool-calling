package agent

import domain.ExecutionMode
import domain.RunStatus
import entity.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class MemoryStore : Store {
    private val states = mutableMapOf<String, String>()
    private val history = mutableMapOf<String, List<String>>()
    override fun load(id: String): AgentRun {
        val run = json.fromJson(states.getValue(id), AgentRun::class.java)
        run.messages = history.getValue(id).map { json.fromJson(it, Message::class.java) }.toMutableList()
        run.savedMessages = run.messages.size
        return run
    }
    override fun save(run: AgentRun) {
        val old = states[run.id.toString()]?.let { json.fromJson(it, AgentRun::class.java) }
        check((old?.revision ?: -1) == run.revision) { "Stale revision" }
        run.revision++
        states[run.id.toString()] = json.toJson(run)
        history[run.id.toString()] = run.messages.map(json::toJson)
        run.savedMessages = run.messages.size
    }
}

class RuntimeTest {
    @TempDir lateinit var temp: Path
    private val store = MemoryStore()
    private val broken = "def calculate_total(prices: list[float], discount_percent: float) -> float:\n    return sum(prices) - discount_percent\n"
    private val fixed = "def calculate_total(prices: list[float], discount_percent: float) -> float:\n    if not 0 <= discount_percent <= 100:\n        raise ValueError()\n    return sum(prices) * (1 - discount_percent / 100)\n"
    private fun workspace(): Path {
        val root = temp.resolve("workspace")
        if (!Files.exists(root)) {
            Files.createDirectories(root.resolve("private"))
            Files.writeString(root.resolve("order.py"), broken)
            Files.copy(Path.of("workspace/test_order.py"), root.resolve("test_order.py"))
            Files.writeString(root.resolve("private/secret.txt"), "not for agent")
        }
        return root.toRealPath()
    }
    private fun config(timeout: Long = 3, output: Int = 16384): Path {
        val p = temp.resolve("policy.json")
        Files.writeString(p, json.toJson(PolicyConfig(Rules(listOf("python3 -m unittest -v", "pwd", "sleep 2"), listOf("rm -rf .")),
            Rules(listOf("."), listOf("private")), timeoutSeconds = timeout, maxOutputBytes = output)))
        return p
    }
    private fun call(id: String, name: String, vararg args: Pair<String, String>) = ProposedCall(id, name, json.toJson(mapOf(*args)))
    private fun reply(vararg calls: ProposedCall) = Message("assistant", calls = calls.toList())
    private fun finish(id: String, evidence: String) = reply(call(id, "finish_task", "evidence_call_id" to evidence))
    private fun task(id: String = "fix", kind: String = "file_written", path: String = "order.py", deps: List<String> = emptyList(), tools: List<String> = listOf("write_file", "read_file", "Bash", "delete_file")) =
        Task(id, "Task $id", deps, tools, Check(kind, path))
    private fun plan(vararg tasks: Task): Message {
        val p = json.toJsonTree(Plan("Fix order", tasks.toList(), listOf("Test gaps"), listOf("Percent discount"), "Tests pass")).asJsonObject
        p.getAsJsonArray("steps").forEach { it.asJsonObject.remove("done"); it.asJsonObject.remove("evidence") }
        return reply(ProposedCall("plan", "submit_plan", p.toString()))
    }
    private fun runtime(replies: List<Message>, fault: (String, ProposedCall) -> Unit = { _, _ -> }): AgentRuntime = AgentRuntime(store, Model { messages, _ ->
        replies.getOrNull(messages.count { it.role == "assistant" }) ?: error("End of test script")
    }, fault, {})
    private fun create(rt: AgentRuntime, mode: ExecutionMode = ExecutionMode.AUTO, planMode: Boolean = false, limits: Limits = Limits()): String =
        rt.create("Fix total, preserve tests", workspace(), config(), mode, planMode, limits).id.toString()
    private fun result(run: AgentRun, id: String) = run.calls.single { it.request.id == id }.result!!
    private fun happyReplies() = listOf(
        plan(task("inspect", "file_read"), task(deps = listOf("inspect")), task("verify", "tests_passed", ".", listOf("fix"))),
        reply(call("read", "read_file", "path" to "order.py"), call("tests", "read_file", "path" to "test_order.py")),
        finish("done-read", "read"), reply(call("write", "write_file", "path" to "order.py", "content" to fixed)),
        finish("done-write", "write"), reply(call("bash", "Bash", "command" to "python3 -m unittest -v", "cwd" to ".")),
        finish("done-bash", "bash"), Message("assistant", "Fixed and tested")
    )

    @Test fun `end to end uses Bash leaves tests unchanged and rolls back after reload`() {
        val rt = runtime(happyReplies()); val id = create(rt)
        val tests = Files.readString(workspace().resolve("test_order.py"))
        val run = rt.drive(id)
        assertEquals(RunStatus.COMPLETED, run.status)
        assertEquals(0, result(run, "bash").exitCode)
        assertTrue(run.plan!!.steps.all { it.done && it.evidence != null })
        assertTrue(rt.checklist(run).lines().all { it.startsWith("[x]") })
        assertTrue(run.calls.filter { it.taskId != null }.all { it.taskId in setOf("inspect", "fix", "verify") })
        assertEquals(tests, Files.readString(workspace().resolve("test_order.py")))
        assertTrue(Journal.diff(store.load(id).journal).contains("+    if not"))
        val beforeResume = json.toJson(run.spent)
        assertEquals(beforeResume, json.toJson(runtime(happyReplies()).drive(id).spent))
        runtime(happyReplies()).rollback(id)
        assertEquals(broken, Files.readString(workspace().resolve("order.py")))
        assertEquals(tests, Files.readString(workspace().resolve("test_order.py")))
    }

    @Test fun `model can discover evidence id and recover from using a task number`() {
        val output = mutableListOf<String>()
        val readId = "call-" + java.util.UUID.randomUUID()
        val rt = AgentRuntime(store, Model { messages, _ ->
            when (messages.count { it.role == "assistant" }) {
                0 -> plan(task("1", "file_read", tools = listOf("read_file")))
                1 -> reply(call(readId, "read_file", "path" to "order.py"))
                2 -> {
                    val body = obj(messages.last { it.role == "tool" }.content)
                    assertEquals(readId, body.getAsJsonObject("facts")["call_id"].asString)
                    finish("wrong-id", "1")
                }
                3 -> {
                    val feedback = obj(messages.last { it.role == "tool" }.content)
                    assertFalse(feedback["ok"].asBoolean)
                    assertTrue(feedback["output"].asString.contains(readId))
                    assertTrue(messages.last().content.contains(readId))
                    val evidence = obj(messages.first { it.callId == readId }.content).getAsJsonObject("facts")["call_id"].asString
                    finish("correct-id", evidence)
                }
                else -> Message("assistant", "Read verified")
            }
        }, output = { output += it })
        val run = rt.drive(create(rt))
        assertEquals(RunStatus.COMPLETED, run.status)
        assertEquals(readId, run.plan!!.steps.single().evidence)
        assertTrue(output.any { it.startsWith("finish_task: ошибка:") })
        assertEquals(1, output.count { it.startsWith("[ ]") })
    }

    @Test fun `plan mode forbids all side effects including Bash until approval`() {
        val replies = listOf(reply(call("bad-write", "write_file", "path" to "order.py", "content" to fixed),
            call("bad-bash", "Bash", "command" to "pwd", "cwd" to ".")), *happyReplies().toTypedArray())
        val rt = runtime(replies); val id = create(rt, planMode = true)
        val waiting = rt.drive(id)
        assertEquals(RunStatus.WAITING_PLAN, waiting.status)
        assertFalse(result(waiting, "bad-write").ok); assertFalse(result(waiting, "bad-bash").ok)
        assertTrue(waiting.calls.filter { Tools.sideEffect(it.request.name) }.all { it.attempts.isEmpty() })
        assertEquals(broken, Files.readString(workspace().resolve("order.py")))
        val requests = waiting.spent.modelRequests
        rt.decidePlan(id, true)
        val done = rt.drive(id)
        assertEquals(RunStatus.COMPLETED, done.status)
        assertTrue(done.spent.modelRequests > requests)
    }

    @Test fun `rejected plan stays in planning and preserves history`() {
        val rt = runtime(listOf(plan(task()))); val id = create(rt, planMode = true)
        rt.drive(id); val run = rt.decidePlan(id, false)
        assertEquals(domain.RunPhase.PLANNING, run.phase); assertNull(run.plan)
        assertTrue(store.load(id).messages.last().content.contains("Plan rejected"))
    }

    @Test fun `safe approves writing and Bash separately and refusal becomes tool result`() {
        val rt = runtime(happyReplies()); val id = create(rt, ExecutionMode.SAFE, true)
        rt.drive(id); rt.decidePlan(id, true)
        val first = rt.drive(id)
        assertEquals(RunStatus.WAITING_ACTION, first.status)
        assertEquals("write", first.pending.first().id)
        assertEquals(broken, Files.readString(workspace().resolve("order.py")))
        rt.decideAction(id, true)
        val second = rt.drive(id)
        assertEquals(RunStatus.WAITING_ACTION, second.status)
        assertEquals("bash", second.pending.first().id)
        val denied = rt.decideAction(id, false, "Do not run tests yet")
        assertFalse(result(denied, "bash").ok)
        assertTrue(denied.messages.last().content.contains("Do not run tests yet"))
        assertEquals("bash", denied.messages.last().callId)
    }

    @Test fun `policy and schema are rechecked after approval`() {
        val rt = runtime(happyReplies()); val id = create(rt, ExecutionMode.SAFE)
        rt.drive(id); rt.decideAction(id, true)
        Files.writeString(temp.resolve("policy.json"), json.toJson(PolicyConfig(Rules(listOf("pwd")), Rules(listOf("."), listOf("private")), protectedFiles = listOf("test_order.py", "order.py"))))
        val run = rt.drive(id)
        assertFalse(result(run, "write").ok)
        assertEquals(broken, Files.readString(workspace().resolve("order.py")))
    }

    @Test fun `policy denies command directory traversal symlink and protected tests`() {
        val root = workspace(); val p = Policy.load(root.toString(), config().toString())
        p.command("python3 -m unittest -v")
        assertFails { p.command("rm -rf .") }
        assertFails { p.command("pwd; rm -rf .") }
        assertFails { p.command("pwd | cat") }
        assertFails { p.command("pwd > order.py") }
        assertFails { p.command("echo $(pwd)") }
        assertFails { p.path("private/secret.txt") }
        assertFails { p.path("../policy.json") }
        assertFails { p.path(root.resolve("order.py").toString()) }
        assertFails { p.path("test_order.py", true) }
        Files.createSymbolicLink(root.resolve("link"), temp)
        assertFails { p.path("link/policy.json") }
        val run = AgentRun(workspaceRoot = root.toString(), phase = domain.RunPhase.EXECUTION)
        assertFails { p.validate(run, call("c", "Bash", "command" to "pwd", "cwd" to "private")) }
        val both = Policy(root, PolicyConfig(Rules(listOf("pwd"), listOf("pwd")), Rules(listOf("."))))
        assertFails { both.command("pwd") }
        assertFails { Tools.validate(ProposedCall("c", "read_file", "{\"path\":3}")) }
        assertFails { Tools.validate(ProposedCall("c", "read_file", "{\"path\":\"order.py\",\"extra\":true}")) }
        assertFails { Tools.validate(ProposedCall("c", "unknown", "{}")) }
        assertFails { Tools.validate(ProposedCall("c", "read_file", "{path:'order.py'}")) }
    }

    @Test fun `tool errors are returned to model which can continue`() {
        val rt = runtime(listOf(plan(task("inspect", "file_read")),
            reply(call("missing", "read_file", "path" to "missing.py")),
            reply(call("read", "read_file", "path" to "order.py")), finish("done", "read"), Message("assistant", "Read")))
        val run = rt.drive(create(rt))
        assertEquals(RunStatus.COMPLETED, run.status)
        assertFalse(result(run, "missing").ok)
    }

    @Test fun `duplicate tool ids never execute twice and changed arguments are protocol error`() {
        val write = call("write", "write_file", "path" to "order.py", "content" to fixed)
        val rt = runtime(listOf(plan(task()), reply(write, write,
            call("write", "delete_file", "path" to "order.py")), finish("done", "write"), Message("assistant", "Done")))
        val run = rt.drive(create(rt))
        assertEquals(RunStatus.COMPLETED, run.status)
        assertEquals(1, run.calls.single { it.request.id == "write" }.attempts.size)
        assertEquals(1, run.spent.sideEffects)
        assertTrue(run.messages.any { it.role == "tool" && it.content.contains("Protocol error") })
        assertEquals(fixed, Files.readString(workspace().resolve("order.py")))
    }

    @Test fun `every budget can exhaust and batch is all or nothing`() {
        for (limits in listOf(Limits(modelRequests = 0), Limits(toolCalls = 0), Limits(sideEffects = 0), Limits(resultBytes = 0))) {
            val rt = runtime(listOf(plan(task()), reply(call("read", "read_file", "path" to "order.py"),
                call("write", "write_file", "path" to "order.py", "content" to fixed))))
            val id = create(rt, limits = limits); val run = rt.drive(id)
            assertEquals(RunStatus.EXHAUSTED, run.status)
            assertEquals(broken, Files.readString(workspace().resolve("order.py")))
            assertTrue(run.calls.none { it.request.id == "read" || it.request.id == "write" })
            assertEquals(json.toJson(run.spent), json.toJson(rt.drive(id).spent))
        }
        val rt = runtime(listOf(plan(task()), reply(call("a", "read_file", "path" to "order.py"), call("b", "read_file", "path" to "test_order.py"))))
        val run = rt.drive(create(rt, limits = Limits(toolCalls = 2)))
        assertEquals(RunStatus.EXHAUSTED, run.status)
        assertEquals(1, run.spent.toolCalls)
    }

    @Test fun `unknown output is bounded marked truncated and charged by UTF8 bytes`() {
        val root = workspace(); Files.writeString(root.resolve("large.txt"), "Привет 🌍\n".repeat(1000))
        val rt = runtime(listOf(plan(task("inspect", "file_read", "large.txt")),
            reply(call("large", "read_file", "path" to "large.txt")), reply(call("next", "read_file", "path" to "large.txt"))))
        val run = rt.drive(create(rt, limits = Limits(resultBytes = 2200)))
        assertTrue(result(run, "large").truncated)
        assertFalse(result(run, "large").output.contains('\uFFFD'))
        val bytes = run.messages.filter { it.role == "tool" }.sumOf { it.content.toByteArray().size }
        assertEquals(bytes, run.spent.resultBytes)
        assertTrue(bytes <= 2200)
        assertEquals(RunStatus.EXHAUSTED, run.status)
    }

    private class Crash : Error("simulated crash")
    @Test fun `crash after effect is uncertain and retry retains budget and completed tasks`() {
        val rt = runtime(happyReplies()) { point, call -> if (point == "after_execute" && call.id == "write") throw Crash() }
        val id = create(rt)
        assertFailsWith<Crash> { rt.drive(id) }
        assertEquals(fixed, Files.readString(workspace().resolve("order.py")))
        val saved = store.load(id)
        val bytes = saved.spent.resultBytes
        val fresh = runtime(happyReplies())
        assertEquals(RunStatus.UNCERTAIN, fresh.drive(id).status)
        assertTrue(fresh.recoveryDiff(id).contains("+    if not"))
        assertFails { fresh.rollback(id) }
        assertFails { fresh.recover(id, "retry", null) }
        fresh.recover(id, "retry", true)
        val done = fresh.drive(id)
        assertEquals(RunStatus.COMPLETED, done.status)
        assertEquals(2, done.calls.single { it.request.id == "write" }.attempts.size)
        assertEquals(1, done.calls.single { it.request.id == "read" }.attempts.size)
        assertTrue(done.spent.resultBytes >= bytes)
        fresh.rollback(id)
        assertEquals(broken, Files.readString(workspace().resolve("order.py")))
    }

    @Test fun `crash after budget debit before action spends budget permanently`() {
        val rt = runtime(happyReplies()) { point, call -> if (point == "before_execute" && call.id == "write") throw Crash() }
        val id = create(rt); assertFailsWith<Crash> { rt.drive(id) }
        assertEquals(broken, Files.readString(workspace().resolve("order.py")))
        val spent = json.toJson(store.load(id).spent)
        val fresh = runtime(happyReplies())
        fresh.drive(id)
        assertEquals(spent, json.toJson(store.load(id).spent))
        fresh.recover(id, "fail", null)
        assertEquals(spent, json.toJson(store.load(id).spent))
        assertFalse(result(store.load(id), "write").ok)
    }

    @Test fun `uncertain Bash cannot be retried and can be aborted`() {
        val rt = runtime(happyReplies()) { point, call -> if (point == "after_execute" && call.id == "bash") throw Crash() }
        val id = create(rt); assertFailsWith<Crash> { rt.drive(id) }
        val fresh = runtime(happyReplies())
        assertFails { fresh.recover(id, "retry", null) }
        assertEquals(RunStatus.FAILED, fresh.recover(id, "abort", null).status)
    }

    @Test fun `external changes block next write and rollback never partially overwrites on conflict`() {
        val rt = runtime(happyReplies()); val id = create(rt)
        Files.writeString(workspace().resolve("external.txt"), "external")
        assertEquals(RunStatus.CONFLICT, rt.drive(id).status)
        assertEquals(broken, Files.readString(workspace().resolve("order.py")))
        Files.delete(workspace().resolve("external.txt"))
        assertEquals(RunStatus.COMPLETED, rt.drive(id).status)
        Files.writeString(workspace().resolve("order.py"), "external edit")
        assertFails { runtime(happyReplies()).rollback(id) }
        assertEquals("external edit", Files.readString(workspace().resolve("order.py")))
    }

    @Test fun `creation deletion journal survives approval and rollback preserves unrelated files`() {
        Files.writeString(workspace().resolve("old.txt"), "original")
        val rt = runtime(listOf(plan(task("create", "file_written", "new.txt"), task("delete", "file_deleted", "old.txt", listOf("create"))),
            reply(call("create", "write_file", "path" to "new.txt", "content" to "new")), finish("done-create", "create"),
            reply(call("delete", "delete_file", "path" to "old.txt")), finish("done-delete", "delete"), Message("assistant", "Done")))
        val id = create(rt)
        assertEquals(RunStatus.WAITING_ACTION, rt.drive(id).status)
        assertTrue(Files.exists(workspace().resolve("old.txt")))
        rt.decideAction(id, true)
        assertEquals(RunStatus.COMPLETED, rt.drive(id).status)
        assertFalse(Files.exists(workspace().resolve("old.txt")))
        Files.writeString(workspace().resolve("old.txt"), "external")
        assertFails { rt.rollback(id) }
        assertEquals("new", Files.readString(workspace().resolve("new.txt")))
        assertEquals("external", Files.readString(workspace().resolve("old.txt")))
        Files.delete(workspace().resolve("old.txt"))
        Files.writeString(workspace().resolve("unrelated.txt"), "user")
        rt.rollback(id)
        assertFalse(Files.exists(workspace().resolve("new.txt")))
        assertEquals("original", Files.readString(workspace().resolve("old.txt")))
        assertEquals("user", Files.readString(workspace().resolve("unrelated.txt")))
    }

    @Test fun `dependencies and evidence cannot be satisfied by model declarations`() {
        val bad = runtime(listOf(plan(task("a", deps = listOf("missing")))))
        val rejected = bad.drive(create(bad))
        assertNull(rejected.plan)
        assertFalse(result(rejected, "plan").ok)
        val rt = runtime(listOf(plan(task()), finish("fake", "not-a-call"), Message("assistant", "Everything is finished")))
        val run = rt.drive(create(rt))
        assertFalse(run.plan!!.steps.single().done)
        assertFalse(result(run, "fake").ok)
        assertNotEquals(RunStatus.COMPLETED, run.status)
    }

    @Test fun `user may disown uncertain changes and rollback leaves them alone`() {
        val rt = runtime(happyReplies()) { point, call -> if (point == "after_execute" && call.id == "write") throw Crash() }
        val id = create(rt)
        assertFailsWith<Crash> { rt.drive(id) }
        val fresh = runtime(happyReplies())
        fresh.recover(id, "abort", false)
        assertTrue(store.load(id).journal.isEmpty())
        fresh.rollback(id)
        assertEquals(fixed, Files.readString(workspace().resolve("order.py")))
    }

    @Test fun `Bash timeout and combined stdout stderr limits are enforced`() {
        val p = Policy.load(workspace().toString(), config(timeout = 1, output = 1024).toString())
        val timeout = Tools.execute(call("sleep", "Bash", "command" to "sleep 2", "cwd" to "."), p, 1024)
        assertEquals(124, timeout.exitCode)
        Files.writeString(workspace().resolve("test_noise.py"), "import unittest\nclass T(unittest.TestCase):\n    def test_noise(self):\n        print('x' * 100000)\n")
        val noisy = Tools.execute(call("noise", "Bash", "command" to "python3 -m unittest -v", "cwd" to "."), p, 1024)
        assertTrue(noisy.truncated)
        assertTrue(noisy.stdout.orEmpty().toByteArray().size + noisy.stderr.toByteArray().size <= 1024)
    }
}
