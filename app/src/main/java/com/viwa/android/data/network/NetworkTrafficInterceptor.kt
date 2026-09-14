package com.viwa.android.data.network

import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import timber.log.Timber

@Singleton
class NetworkTrafficInterceptor
@Inject
constructor(
    private val networkTrafficLogger: NetworkTrafficLogger,
) : Interceptor {
    private val maxBodyChars = 4000

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val reqSummary = "${req.method} ${req.url}"
        val reqHeaders =
            req.headers.joinToString(separator = "\n") { h ->
                "${h.first}: ${redactHeaderValue(h.first, h.second)}"
            }
        val reqBody = readRequestBody(req)
        val reqPayload =
            buildString {
                appendLine(reqSummary)
                if (reqHeaders.isNotBlank()) appendLine("headers:\n$reqHeaders")
                if (reqBody.isNotBlank()) appendLine("body:\n$reqBody")
            }.trim()
        networkTrafficLogger.log(
            channel = NetworkTrafficChannel.HTTP,
            direction = NetworkTrafficDirection.OUT,
            summary = reqSummary,
            payload = reqPayload,
        )

        val startedAt = System.currentTimeMillis()
        return try {
            val response = chain.proceed(req)
            val tookMs = System.currentTimeMillis() - startedAt
            val responseBodyText = readResponseBody(response)
            val responseSummary = "HTTP ${response.code} ${req.method} ${req.url} (${tookMs}ms)"
            val responseHeaders =
                response.headers.joinToString(separator = "\n") { h ->
                    "${h.first}: ${redactHeaderValue(h.first, h.second)}"
                }
            val responsePayload =
                buildString {
                    appendLine(responseSummary)
                    if (responseHeaders.isNotBlank()) appendLine("headers:\n$responseHeaders")
                    if (responseBodyText.isNotBlank()) appendLine("body:\n$responseBodyText")
                }.trim()
            networkTrafficLogger.log(
                channel = NetworkTrafficChannel.HTTP,
                direction = NetworkTrafficDirection.IN,
                summary = responseSummary,
                payload = responsePayload,
            )
            response
        } catch (e: Exception) {
            val tookMs = System.currentTimeMillis() - startedAt
            val message = "HTTP ERROR ${req.method} ${req.url} (${tookMs}ms): ${e.message ?: e.javaClass.simpleName}"
            Timber.w("%s", message)
            networkTrafficLogger.log(
                channel = NetworkTrafficChannel.HTTP,
                direction = NetworkTrafficDirection.SYSTEM,
                summary = message,
                payload = message,
            )
            throw e
        }
    }

    private fun readRequestBody(request: okhttp3.Request): String {
        val body = request.body ?: return ""
        return runCatching {
            val buffer = Buffer()
            body.writeTo(buffer)
            limitAndRedact(buffer.readString(StandardCharsets.UTF_8))
        }.getOrElse {
            Timber.w(it, "NetworkTrafficInterceptor: failed to read request body")
            ""
        }
    }

    private fun readResponseBody(response: Response): String {
        return runCatching {
            val txt = response.peekBody(maxBodyChars.toLong() * 2).string()
            limitAndRedact(txt)
        }.getOrElse {
            Timber.w(it, "NetworkTrafficInterceptor: failed to read response body")
            ""
        }
    }

    private fun limitAndRedact(raw: String): String {
        val limited = if (raw.length > maxBodyChars) raw.take(maxBodyChars) + "…(truncated)" else raw
        return redactNetworkPayload(limited)
    }
}
