package agent

import domain.ExecutionMode
import domain.RunPhase
import entity.AgentRun
import entity.ProposedCall
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

data class Rules(val allow: List<String> = emptyList(), val deny: List<String> = emptyList())
data class PolicyConfig(
    val commands: Rules = Rules(), val directories: Rules = Rules(),
    val protectedFiles: List<String> = listOf("test_order.py"),
    val timeoutSeconds: Long = 15, val maxOutputBytes: Int = 16384,
    val maxFileBytes: Int = 131072, val maxWorkspaceBytes: Int = 524288
)

/** A deliberately small command language. Allowlisting an interpreter alone is unsafe. */
class Policy(val root: Path, val config: PolicyConfig) {
    private val allow = config.directories.allow.map(::directory)
    private val deny = config.directories.deny.map(::directory)
    private val protected = config.protectedFiles.map { relative(it) }

    init {
        require(allow.isNotEmpty()) { "Policy must explicitly allow at least one directory" }
        require(config.timeoutSeconds in 1..300 && config.maxOutputBytes in 1024..1048576)
        require(config.maxFileBytes in 1..524288 && config.maxWorkspaceBytes in 1..1048576)
    }

    private fun directory(raw: String): Path {
        val p = Path.of(raw)
        val target = (if (p.isAbsolute) p else root.resolve(p)).normalize()
        require(target.startsWith(root)) { "Policy directory outside workspace: $raw" }
        noLinks(target)
        return target
    }

    private fun relative(raw: String): Path {
        val p = Path.of(raw)
        require(raw.isNotBlank() && raw.length <= 512 && !p.isAbsolute && '\\' !in raw && p.none { it.toString() == ".." }) {
            "Only relative workspace paths without '..' are accepted"
        }
        return root.resolve(p).normalize().also { require(it.startsWith(root)) }
    }

    private fun noLinks(target: Path) {
        var cursor = root
        require(root.toRealPath() == root) { "Workspace root changed" }
        for (part in root.relativize(target)) {
            cursor = cursor.resolve(part)
            require(!Files.isSymbolicLink(cursor)) { "Symlinks are forbidden: $cursor" }
        }
        if (Files.exists(target, NOFOLLOW_LINKS)) require(target.toRealPath().startsWith(root))
    }

    fun visible(target: Path): Boolean = target.startsWith(root) && allow.any(target::startsWith) && deny.none(target::startsWith)
    fun mayTraverse(target: Path): Boolean = deny.none(target::startsWith) && allow.any { target.startsWith(it) || it.startsWith(target) }
    fun path(raw: String, writing: Boolean = false): Path {
        val target = relative(raw)
        noLinks(target)
        require(visible(target)) { "Directory denied by policy: $raw" }
        if (writing) {
            require(target != root && protected.none { target == it || target.startsWith(it) }) { "Protected path: $raw" }
            require(!Files.isDirectory(target, NOFOLLOW_LINKS)) { "Only individual files can be changed" }
        }
        return target
    }

    fun command(command: String) {
        require(command !in config.commands.deny && command in config.commands.allow) { "Command denied by policy: $command" }
        // No shell operators, arbitrary flags, paths, code strings, or nested interpreters.
        require(command == "pwd" || command.matches(Regex("sleep [0-9]{1,3}")) ||
            command.matches(Regex("python3? (?:-B )?-m unittest(?: -v)?"))) { "Unsupported Bash syntax or arguments" }
    }

    fun validate(run: AgentRun, call: ProposedCall) {
        val args = Tools.validate(call)
        if (run.phase == RunPhase.PLANNING) require(call.name in setOf("list_files", "read_file", "submit_plan")) {
            "Plan Mode permits reading and submitting a plan only; Bash is forbidden"
        }
        when (call.name) {
            "list_files", "read_file" -> path(args["path"].asString)
            "write_file", "delete_file" -> {
                path(args["path"].asString, true)
                if (call.name == "write_file") require(args["content"].asString.toByteArray().size <= config.maxFileBytes) { "File too large" }
            }
            "Bash" -> {
                command(args["command"].asString)
                require(Files.isDirectory(path(args["cwd"].asString))) { "cwd is not a directory" }
            }
            "submit_plan" -> require(run.phase == RunPhase.PLANNING) { "Plan already approved" }
            "finish_task" -> require(run.phase == RunPhase.EXECUTION) { "No execution task in Plan Mode" }
        }
    }

    fun approval(run: AgentRun, name: String): Boolean = name == "delete_file" ||
        (run.mode == ExecutionMode.SAFE && Tools.sideEffect(name))

    companion object {
        fun load(workspace: String, filename: String): Policy {
            val root = Path.of(workspace).toAbsolutePath().normalize()
            require(Files.isDirectory(root) && root.toRealPath() == root) { "Use the canonical workspace path" }
            val policy = Path.of(filename).toRealPath()
            require(!policy.startsWith(root)) { "Policy must be outside workspace" }
            require(Files.size(policy) <= 65536)
            return Policy(root, json.fromJson(Files.readString(policy), PolicyConfig::class.java))
        }
    }
}
