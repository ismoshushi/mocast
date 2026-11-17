package app.mocast.net

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object Http {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun get(url: String, accept: String = "*/*"): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", accept)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOExceptionWithUrl(url, resp.code)
            return resp.body?.string().orEmpty()
        }
    }

    fun getBytes(url: String): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOExceptionWithUrl(url, resp.code)
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }
}

class IOExceptionWithUrl(val url: String, val code: Int) :
    Exception("HTTP $code: $url")
