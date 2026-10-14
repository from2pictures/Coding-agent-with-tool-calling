package entity

import domain.ExecutionMode
import domain.RunPhase
import domain.RunStatus
import java.util.UUID

data class AgentRun(
    val id: UUID = UUID.randomUUID(),
    val userTask: String = "",
    val workspaceRoot: String = "",
    val mode: ExecutionMode = ExecutionMode.SAFE,
    var phase: RunPhase = RunPhase.PLANNING,
    var status: RunStatus = RunStatus.RUNNING,
    val policyPath: String = "",
    val requirePlanApproval: Boolean = true,
    var scriptPath: String? = null,
    val limits: Limits = Limits(),
    val spent: Budget = Budget(),
    var plan: Plan? = null,
    val calls: MutableList<Call> = mutableListOf(),
    val pending: MutableList<ProposedCall> = mutableListOf(),
    var expectedTree: Map<String, FileImage> = emptyMap(),
    val journal: MutableMap<String, Change> = linkedMapOf(),
    val events: MutableList<String> = mutableListOf(),
    var note: String = "",
    var revision: Long = -1,
    @Transient var messages: MutableList<Message> = mutableListOf(),
    @Transient var savedMessages: Int = 0
)

data class Limits(val modelRequests: Int = 30, val toolCalls: Int = 100, val sideEffects: Int = 30, val resultBytes: Int = 262144)
data class Budget(var modelRequests: Int = 0, var toolCalls: Int = 0, var sideEffects: Int = 0, var resultBytes: Int = 0)
data class ProposedCall(val id: String = "", val name: String = "", val arguments: String = "{}")
data class Message(val role: String = "", val content: String = "", val calls: List<ProposedCall> = emptyList(), val callId: String? = null)
data class Attempt(var state: String = "STARTED", val reservedBytes: Int = 0, var result: ToolResult? = null)
data class Call(
    val request: ProposedCall = ProposedCall(), val taskId: String? = null,
    var state: String = "QUEUED", var approved: Boolean = false,
    val attempts: MutableList<Attempt> = mutableListOf(), var result: ToolResult? = null,
    var before: Map<String, FileImage>? = null
)
data class ToolResult(
    val ok: Boolean = true, var output: String = "", var stderr: String = "",
    val exitCode: Int? = null, var truncated: Boolean = false,
    val facts: MutableMap<String, String> = linkedMapOf(), var stdout: String? = null
)
data class Plan(
    val goal: String = "", val steps: List<Task> = emptyList(),
    val risks: List<String> = emptyList(), val assumptions: List<String> = emptyList(),
    val acceptance: String = ""
)
data class Task(
    val id: String = "", val description: String = "", val dependencies: List<String> = emptyList(),
    val tools: List<String> = emptyList(), val check: Check = Check(),
    var done: Boolean = false, var evidence: String? = null
)
data class Check(val kind: String = "", val path: String = "")
data class FileImage(val content: String = "", val hash: String = "")
data class Change(val before: FileImage? = null, var after: FileImage? = null)
