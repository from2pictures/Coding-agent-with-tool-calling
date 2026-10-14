package agent

import com.mongodb.client.MongoClients
import com.mongodb.client.model.Filters.eq
import domain.ExecutionMode
import domain.RunStatus
import entity.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")
class MongoIntegrationTest {
    @TempDir lateinit var temp: Path
    private val uri = System.getenv("MONGODB_TEST_URI")
    private val database = "coding_agent_test_" + UUID.randomUUID().toString().replace("-", "")
    @AfterEach fun cleanup() { MongoClients.create(uri).use { it.getDatabase(database).drop() } }

    private fun create(store: Store): AgentRun {
        val workspace = Files.createDirectories(temp.resolve("workspace"))
        Files.copy(Path.of("workspace/order.py"), workspace.resolve("order.py"))
        Files.copy(Path.of("workspace/test_order.py"), workspace.resolve("test_order.py"))
        val policy = temp.resolve("policy.json")
        Files.copy(Path.of("policy.json"), policy)
        return AgentRuntime(store, ScriptModel(Path.of("examples/demo.json")), output = {}).create("Fix order", workspace, policy, ExecutionMode.AUTO, false)
    }

    @Test fun `real MongoDB stores messages separately and supports resume diff rollback`() {
        val id: String
        val original = Files.readString(Path.of("workspace/order.py"))
        MongoStore(uri, database).use { store ->
            id = create(store).id.toString()
            val run = AgentRuntime(store, ScriptModel(Path.of("examples/demo.json")), output = {}).drive(id)
            assertEquals(RunStatus.COMPLETED, run.status)
        }
        MongoClients.create(uri).use { client ->
            val db = client.getDatabase(database)
            assertTrue(db.getCollection("messages").countDocuments(eq("runId", id)) > 10)
            val document = db.getCollection("runs").find(eq("_id", id)).first()!!
            assertFalse(document.containsKey("messages"))
            assertEquals("COMPLETED", document.getString("status"))
            assertTrue(document.get("spent", org.bson.Document::class.java).getInteger("toolCalls") > 0)
        }
        MongoStore(uri, database).use { store ->
            val rt = AgentRuntime(store, Model { _, _ -> error("Completed runs must not query model") }, output = {})
            val loaded = store.load(id)
            assertTrue(Journal.diff(loaded.journal).contains("order.py"))
            val budget = json.toJson(loaded.spent)
            assertEquals(budget, json.toJson(rt.drive(id).spent))
            rt.rollback(id)
            assertEquals(original, Files.readString(temp.resolve("workspace/order.py")))
        }
    }

    @Test fun `real transaction rolls back state when inserting messages fails`() {
        MongoStore(uri, database).use { store ->
            val run = create(store)
            val oldRevision = run.revision
            run.spent.toolCalls = 77
            run.savedMessages = 0 // Deliberate duplicate sequence: message insertion fails after state replacement.
            assertFails { store.save(run) }
            val restored = store.load(run.id.toString())
            assertEquals(oldRevision, restored.revision)
            assertEquals(0, restored.spent.toolCalls)
            assertEquals(2, restored.messages.size)
            val stale = store.load(run.id.toString())
            store.save(restored)
            assertFails { store.save(stale) }
        }
    }

    private class Crash : Error()
    @Test fun `Mongo survives uncertain write and reopening the store`() {
        val id: String
        var spent = ""
        MongoStore(uri, database).use { store ->
            id = create(store).id.toString()
            val rt = AgentRuntime(store, ScriptModel(Path.of("examples/demo.json")), { point, call ->
                if (point == "after_execute" && call.id == "write-fix") throw Crash()
            }, {})
            assertFailsWith<Crash> { rt.drive(id) }
            spent = json.toJson(store.load(id).spent)
        }
        MongoStore(uri, database).use { store ->
            val rt = AgentRuntime(store, ScriptModel(Path.of("examples/demo.json")), output = {})
            assertEquals(RunStatus.UNCERTAIN, rt.drive(id).status)
            assertEquals(spent, json.toJson(store.load(id).spent))
            rt.recover(id, "retry", true)
            assertEquals(RunStatus.COMPLETED, rt.drive(id).status)
            rt.rollback(id)
            assertEquals(Files.readString(Path.of("workspace/order.py")), Files.readString(temp.resolve("workspace/order.py")))
        }
    }
}
