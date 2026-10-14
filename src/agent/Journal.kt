package agent

import entity.AgentRun
import entity.Change
import entity.FileImage
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Base64

class Journal(private val policy: Policy) {
    fun snapshot(): Map<String, FileImage> {
        val result = sortedMapOf<String, FileImage>()
        var size = 0L
        Files.walkFileTree(policy.root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!policy.mayTraverse(dir) || (dir != policy.root && dir.fileName.toString() == "__pycache__"))
                    return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!policy.visible(file)) return FileVisitResult.CONTINUE
                require(!attrs.isSymbolicLink && attrs.isRegularFile) { "Unsupported file in workspace: $file" }
                size += attrs.size()
                require(size <= policy.config.maxWorkspaceBytes && result.size < 1000) { "Workspace snapshot limit exceeded" }
                val bytes = Files.readAllBytes(file)
                require(bytes.size.toLong() == attrs.size()) { "File changed while taking snapshot: $file" }
                result[policy.root.relativize(file).toString()] = FileImage(Base64.getEncoder().encodeToString(bytes), sha(bytes))
                return FileVisitResult.CONTINUE
            }
        })
        return result
    }

    fun record(run: AgentRun, after: Map<String, FileImage>, adopt: Boolean = true) {
        if (adopt) changed(run.expectedTree, after).forEach { path ->
            val existing = run.journal[path]
            if (existing == null) run.journal[path] = Change(run.expectedTree[path], after[path])
            else existing.after = after[path]
        }
        run.expectedTree = after
    }

    fun restore(run: AgentRun) {
        val changes = run.journal.filterValues { it.before != it.after }
        changes.forEach { (name, change) ->
            val path = policy.path(name, true)
            val actual = if (Files.exists(path)) sha(Files.readAllBytes(path)) else null
            val expected = change.after?.hash
            val alreadyRestored = run.status == domain.RunStatus.ROLLING_BACK && actual == change.before?.hash
            require(actual == expected || alreadyRestored) { "Rollback conflict: $name" }
            require(Files.isDirectory(path.parent)) { "Missing parent directory: $name" }
        }
        changes.forEach { (name, change) ->
            val path = policy.path(name, true)
            if (change.before == null) Files.deleteIfExists(path)
            else Files.write(path, Base64.getDecoder().decode(change.before.content))
        }
    }

    fun preflightRollback(run: AgentRun) {
        run.journal.filterValues { it.before != it.after }.forEach { (name, change) ->
            val path = policy.path(name, true)
            require(Files.isDirectory(path.parent)) { "Missing parent: $name" }
            val actual = if (Files.exists(path)) sha(Files.readAllBytes(path)) else null
            require(actual == change.after?.hash || (run.status == domain.RunStatus.ROLLING_BACK && actual == change.before?.hash)) {
                "Rollback conflict: $name; nothing overwritten"
            }
        }
    }

    companion object {
        fun changed(before: Map<String, FileImage>, after: Map<String, FileImage>) = (before.keys + after.keys).filter { before[it] != after[it] }.sorted()
        fun fingerprint(tree: Map<String, FileImage>) = sha(tree.toSortedMap().entries.joinToString("\n") { "${it.key}:${it.value.hash}" }.toByteArray())
        fun diff(changes: Map<String, Change>): String = changes.filterValues { it.before != it.after }.toSortedMap().entries.joinToString("\n") { (name, change) ->
            fun lines(image: FileImage?, prefix: String): String = image?.let { String(Base64.getDecoder().decode(it.content), Charsets.UTF_8).lineSequence().joinToString("\n") { line -> prefix + line } } ?: ""
            "--- ${if (change.before == null) "/dev/null" else "a/$name"}\n+++ ${if (change.after == null) "/dev/null" else "b/$name"}\n" +
                "# ${change.before?.hash ?: "absent"} -> ${change.after?.hash ?: "absent"}\n" + lines(change.before, "-") + "\n" + lines(change.after, "+")
        }
    }
}
