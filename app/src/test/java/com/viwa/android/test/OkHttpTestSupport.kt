package com.viwa.android.test

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** Shared OkHttp factory for unit tests; always pair with [shutdownOkHttpForTests]. */
internal fun newTestOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

/** Stops OkHttp worker threads and evicts pooled connections after each test class/method. */
internal fun OkHttpClient.shutdownOkHttpForTests() {
    dispatcher.executorService.shutdown()
    dispatcher.executorService.awaitTermination(2, TimeUnit.SECONDS)
    connectionPool.evictAll()
    cache?.close()
}

/** Tracks clients created in a test class and shuts them down in [@After][org.junit.After]. */
internal class OkHttpTestClientRegistry {
    private val clients = mutableListOf<OkHttpClient>()

    fun register(client: OkHttpClient): OkHttpClient {
        clients += client
        return client
    }

    fun newClient(): OkHttpClient = register(newTestOkHttpClient())

    fun shutdownAll() {
        clients.forEach { runCatching { it.shutdownOkHttpForTests() } }
        clients.clear()
    }
}
