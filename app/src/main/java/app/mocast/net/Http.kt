package app.mocast.net

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

object Http {
    /** 与原来完全相同的 UA；下载请求也复用它。 */
    internal const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private val lock = Any()

    @Volatile
    private var clientRef: OkHttpClient = buildClient(null)

    /** 全局共用的 OkHttpClient（订阅抓取、翻译、下载都走它，代理对三者同时生效）。 */
    val client: OkHttpClient get() = clientRef

    /**
     * 按代理配置重建 client。无代理/代理不可用时行为与原来完全一致
     * （同一超时、同一 UA、followRedirects=true）。
     * 正在进行的请求不受影响（它们持有旧 client 引用），新请求用新 client。
     */
    fun configure(proxy: ProxyConfig?) {
        val usable = proxy?.takeIf { it.isUsable }
        synchronized(lock) { clientRef = buildClient(usable) }
    }

    private fun buildClient(proxy: ProxyConfig?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
        if (proxy != null) {
            builder.proxy(Proxy(proxy.proxyType, InetSocketAddress(proxy.host, proxy.port)))
            if (proxy.proxyType == Proxy.Type.HTTP &&
                proxy.username.isNotBlank() && proxy.password.isNotBlank()
            ) {
                // 只对 HTTP 代理做 407 认证。OkHttp 4.12 不支持 SOCKS5 用户名/密码
                // 认证，SOCKS 代理填了账号密码也不会生效（设置页会注明）。
                builder.proxyAuthenticator(proxyAuthenticator(proxy.username, proxy.password))
            }
        }
        return builder.build()
    }

    /**
     * 代理 407 认证：只在请求还没带过 Proxy-Authorization 时回账密；
     * 账密不对、代理再次 407 时返回 null，避免 OkHttp 循环重试。
     */
    private fun proxyAuthenticator(username: String, password: String): Authenticator =
        Authenticator { _, response ->
            if (response.code != 407) return@Authenticator null
            if (response.request.header("Proxy-Authorization") != null) return@Authenticator null
            response.request.newBuilder()
                .header("Proxy-Authorization", Credentials.basic(username, password))
                .build()
        }

    fun get(url: String, accept: String = "*/*"): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", accept)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOExceptionWithUrl(url, resp.code)
            return resp.body?.string().orEmpty()
        }
    }

    fun getBytes(url: String): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOExceptionWithUrl(url, resp.code)
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }
}

class IOExceptionWithUrl(val url: String, val code: Int) :
    Exception("HTTP $code: $url")
