package agent

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.ReadConcern
import com.mongodb.TransactionOptions
import com.mongodb.WriteConcern
import com.mongodb.client.MongoClients
import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes.ascending
import com.mongodb.client.model.Sorts
import entity.AgentRun
import entity.Message
import org.bson.Document
import java.util.concurrent.TimeUnit

interface Store : AutoCloseable {
    fun load(id: String): AgentRun
    /** Atomically save the state and newly appended messages. Reject stale revisions. */
    fun save(run: AgentRun)
    override fun close() {}
}

class MongoStore(uri: String, database: String) : Store {
    private val client = MongoClients.create(MongoClientSettings.builder().applyConnectionString(ConnectionString(uri))
        .applyToClusterSettings { it.serverSelectionTimeout(5, TimeUnit.SECONDS) }.build())
    private val db = client.getDatabase(database)
    private val runs = db.getCollection("runs")
    private val messages = db.getCollection("messages")
    private val transaction = TransactionOptions.builder().readConcern(ReadConcern.SNAPSHOT).writeConcern(WriteConcern.MAJORITY).build()

    init {
        val hello = db.runCommand(Document("hello", 1))
        require(hello.containsKey("setName") || hello.getString("msg") == "isdbgrid") { "MongoDB requires a replica set for transactions; run docker compose up -d --wait" }
        messages.createIndex(ascending("runId", "sequence"), IndexOptions().unique(true))
    }

    override fun load(id: String): AgentRun = client.startSession().use { session ->
        session.withTransaction({
            val document = runs.find(session, eq("_id", id)).first() ?: error("Unknown run_id: $id")
            val run = json.fromJson(document.toJson(), AgentRun::class.java)
            run.messages = messages.find(session, eq("runId", id)).sort(Sorts.ascending("sequence"))
                .map { json.fromJson(it.toJson(), Message::class.java) }.toMutableList()
            run.savedMessages = run.messages.size
            run
        }, transaction)
    }

    override fun save(run: AgentRun) {
        val nextRevision = run.revision + 1
        val payload = json.toJsonTree(run).asJsonObject.apply { addProperty("revision", nextRevision) }.toString()
        require(payload.toByteArray().size <= 12 * 1024 * 1024) { "Run state exceeds 12 MiB; stop before MongoDB document limit" }
        val document = Document.parse(payload).append("_id", run.id.toString())
        val append = run.messages.drop(run.savedMessages).mapIndexed { index, message ->
            Document.parse(json.toJson(message)).append("runId", run.id.toString()).append("sequence", run.savedMessages + index)
        }
        client.startSession().use { session ->
            session.withTransaction({
                if (run.revision < 0) runs.insertOne(session, document)
                else check(runs.replaceOne(session, and(eq("_id", run.id.toString()), eq("revision", run.revision)), document).matchedCount == 1L) {
                    "Concurrent update: reload run before continuing"
                }
                if (append.isNotEmpty()) messages.insertMany(session, append)
                true
            }, transaction)
        }
        run.revision = nextRevision
        run.savedMessages = run.messages.size
    }

    override fun close() = client.close()
}
