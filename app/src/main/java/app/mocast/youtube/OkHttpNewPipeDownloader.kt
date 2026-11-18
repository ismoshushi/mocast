package app.mocast.youtube

import app.mocast.net.Http
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response

/**
 * 把 NewPipe Extractor 的网络请求桥接到 MoCast 统一的 [Http.client]。
 *
 * 不要在这里 new OkHttpClient：代理任务会改 Http.client，改了就全量生效，
 * 这里只复用它（连接池、超时、TLS 配置都跟着走）。
 *
 * 注意：上游 PR #1181 把 [Downloader.execute] 从 public 收紧为 protected，
 * Kotlin 里 `override fun execute`（public）对两种可见性都能编译，
 * 如果编译报可见性错误再改成 `protected override fun execute`。
 */
class OkHttpNewPipeDownloader : Downloader() {

    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody(null)
        val httpRequest = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .apply {
                request.headers()?.forEach { (name, values) ->
                    removeHeader(name)
                    values.forEach { addHeader(name, it) }
                }
            }
            .build()
        Http.client.newCall(httpRequest).execute().use { resp ->
            return Response(
                resp.code,
                resp.message,
                resp.headers.toMultimap(),
                resp.body?.string(),
                resp.request.url.toString()
            )
        }
    }
}
