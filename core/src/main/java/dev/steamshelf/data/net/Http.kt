package dev.steamshelf.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

class HttpStatusException(val code: Int, val body: String?) : IOException("HTTP $code")

data class HttpResult(val code: Int, val body: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

/** Cancellable suspend wrapper around an OkHttp call. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }
    })
}

/** Executes [request] and returns status + body without throwing on HTTP errors. */
suspend fun OkHttpClient.fetch(request: Request): HttpResult {
    val response = newCall(request).await()
    return withContext(Dispatchers.IO) {
        response.use { HttpResult(it.code, it.body.string()) }
    }
}

/** Executes [request] and returns the body, throwing [HttpStatusException] on non-2xx. */
suspend fun OkHttpClient.fetchString(request: Request): String {
    val result = fetch(request)
    if (!result.isSuccessful) throw HttpStatusException(result.code, result.body.take(500))
    return result.body
}
