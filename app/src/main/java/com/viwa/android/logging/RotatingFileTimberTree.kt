package com.viwa.android.logging

import com.viwa.android.data.network.redactNetworkPayload
import java.time.Instant
import timber.log.Timber

/** Captures Timber logs into rotating local files via [AppLogFileStore]. */
class RotatingFileTimberTree(
    private val store: AppLogFileStore,
) : Timber.Tree() {
  override fun log(
      priority: Int,
      tag: String?,
      message: String,
      t: Throwable?,
  ) {
    val priorityLabel = priorityLabel(priority)
    val safeTag = tag?.take(64) ?: "App"
    val redactedMessage = redactLogLine(message)
    val timestamp = Instant.now().toString()
    val base = "$timestamp $priorityLabel/$safeTag: $redactedMessage"
    val line =
        if (t != null) {
            "$base\n${redactLogLine(t.stackTraceToString())}"
        } else {
            base
        }
    store.appendLine(line)
  }

  private fun priorityLabel(priority: Int): String =
      when (priority) {
          android.util.Log.VERBOSE -> "V"
          android.util.Log.DEBUG -> "D"
          android.util.Log.INFO -> "I"
          android.util.Log.WARN -> "W"
          android.util.Log.ERROR -> "E"
          android.util.Log.ASSERT -> "A"
          else -> "?"
      }

  private fun redactLogLine(text: String): String {
    var out = redactNetworkPayload(text)
    out =
        BEARER_PATTERN.replace(out) { match ->
            "${match.groupValues[1]}***"
        }
    return out
  }

  private companion object {
    private val BEARER_PATTERN =
        Regex("""(Bearer\s+)[A-Za-z0-9._\-+/=]{8,}""", RegexOption.IGNORE_CASE)
  }
}
