package au.orbit.frontend

import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ScraperHttpTest {
    @Test fun consecutiveFetchesReuseOneConnection() {
        val server=ServerSocket(0,2,InetAddress.getByName("127.0.0.1"))
        val accepted=AtomicReference<Socket>()
        val completed=AtomicInteger()
        val failure=AtomicReference<Throwable>()
        val thread=Thread {
            try {
                server.accept().use { socket ->
                    accepted.set(socket);socket.soTimeout=3000
                    val reader=socket.getInputStream().bufferedReader()
                    repeat(2) {
                        while(true) { val line=reader.readLine() ?: error("Connection closed");if(line.isEmpty())break }
                        socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: 7\r\nConnection: keep-alive\r\n\r\nartwork".toByteArray());flush() }
                        completed.incrementAndGet()
                    }
                }
            } catch(error:Throwable) { failure.set(error) }
        }.apply { isDaemon=true;start() }
        val client=ScraperHttp.client.newBuilder().readTimeout(2,TimeUnit.SECONDS).build()
        try {
            val request=Request.Builder().url("http://127.0.0.1:${server.localPort}/media").build()
            repeat(2) { client.newCall(request).execute().use { assertEquals("artwork",it.body.string()) } }
            thread.join(2000)
            assertEquals(2,completed.get());assertNull(failure.get())
        } finally { accepted.get()?.close();server.close();thread.join(2000);client.connectionPool.evictAll() }
    }
}
