package agent

import com.mongodb.MongoCommandException
import com.mongodb.client.MongoClients
import org.bson.Document
import java.nio.file.Path

fun main(args: Array<String>) {
    require(args.size == 1) { "Expected project-local MongoDB data directory" }
    MongoClients.create("mongodb://127.0.0.1:27017/?directConnection=true&serverSelectionTimeoutMS=5000").use { client ->
        val admin = client.getDatabase("admin")
        val options = admin.runCommand(Document("getCmdLineOpts", 1)).get("parsed", Document::class.java)
        val actualPath = options.get("storage", Document::class.java).getString("dbPath")
        check(Path.of(actualPath).toRealPath() == Path.of(args[0]).toRealPath()) { "Port 27017 belongs to another MongoDB" }
        try {
            val status = admin.runCommand(Document("replSetGetStatus", 1))
            check(status.getString("set") == "rs0") { "Unexpected replica set" }
        } catch (e: MongoCommandException) {
            if (e.errorCode != 94) throw e
            admin.runCommand(Document("replSetInitiate", Document("_id", "rs0")
                .append("members", listOf(Document("_id", 0).append("host", "localhost:27017")))))
        }
        repeat(60) {
            if (admin.runCommand(Document("hello", 1)).getBoolean("isWritablePrimary", false)) {
                println("MongoDB ready: mongodb://localhost:27017/?replicaSet=rs0&directConnection=true")
                return
            }
            Thread.sleep(500)
        }
        error("MongoDB did not elect a primary; see .local/mongo/mongod.log")
    }
}
