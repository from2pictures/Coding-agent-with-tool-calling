package agent

import com.sun.net.httpserver.HttpServer
import entity.Message
import entity.ProposedCall
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.*

class ChatModelTest {
    @Test fun `HTTP adapter sends schemas and matching tool ids and accepts multiple calls`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var request = ""
        server.createContext("/v1/chat/completions") { exchange ->
            request = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val response = """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"a","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"order.py\"}"}},{"id":"b","type":"function","function":{"name":"list_files","arguments":"{\"path\":\".\"}"}}]}}]}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val history = listOf(Message("user", "Read"), Message("assistant", calls = listOf(ProposedCall("previous", "read_file", "{}"))), Message("tool", "{}", callId = "previous"))
            val response = ChatModel("http://127.0.0.1:${server.address.port}/v1", "test-only", "fake").reply(history, Tools.specs)
            assertEquals(listOf("a", "b"), response.calls.map { it.id })
            val sent = obj(request)
            assertEquals("previous", sent.getAsJsonArray("messages")[2].asJsonObject["tool_call_id"].asString)
            assertTrue(sent.getAsJsonArray("tools").all { it.asJsonObject.getAsJsonObject("function").has("parameters") })
            assertEquals("auto", sent["tool_choice"].asString)
        } finally { server.stop(0) }
    }
}
