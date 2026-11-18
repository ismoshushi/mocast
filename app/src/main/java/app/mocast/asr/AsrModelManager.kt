package app.mocast.asr

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.mocast.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * ASR 模型管理：按需下载、断点续传、SHA-256 校验、解压、损坏重下。
 *
 * 模型不要打进 APK（base 约 140MB，打进去包体积直接翻倍，商店也不让这么干），
 * 首次生成字幕时才下载，存在 filesDir/asr-models/<key>/。
 *
 * 下载源（已核实，2026-11-18）：
 *   https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-<key>.tar.bz2
 * VAD 模型是单个文件，不用解压：
 *   https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx
 *
 * SHA-256（⚠️ 首跑前必核）：
 * 下面的 EXPECTED_SHA256 是 2026-11-18 从第三方转载的 sherpa-onnx NOTICE 文件里抄来的
 * 快照值（tiny c4611699… / base 911b2083… / small 486a46af…，见 README.md），
 * 上游 asr-models tag 是滚动更新的，值可能已经变了。集成第一步：打开
 * https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models 核对，
 * 对不上就改这里的值，不要改校验逻辑。校验失败时本类会删掉已下载文件并抛中文异常，
 * 不会拿一个损坏/被篡改的模型去识别。
 *
 * 解压方案：org.apache.commons:commons-compress（约 1MB，标准库级依赖），
 * TarArchiveInputStream + BZip2CompressorInputStream。没用系统 tar 命令（Android 上没有），
 * 也没手写 bzip2 解码（不要重复造轮子）。解压时防 Zip-Slip：entry 路径含 ".." 直接拒掉。
 */
class AsrModelManager(private val context: Context) {

    companion object {
        private const val MODELS_DIR = "asr-models"
        private const val VAD_FILE = "silero_vad.onnx"
        private const val READY_MARKER = ".ready"
        private const val BUFFER_SIZE = 256 * 1024 // 256KB 缓冲，下载/解压共用

        private const val ASR_MODELS_BASE =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
        private const val VAD_URL = "$ASR_MODELS_BASE/silero_vad.onnx"

        // ⚠️ 快照值，首跑前按类注释核对 releases/tag/asr-models 页。
        private val EXPECTED_SHA256 = mapOf(
            "tiny" to "c46116994e539aa165266d96b325252728429c12535eb9d8b6a2b10f129e66b1",
            "base" to "911b2083efd7c0dca2ac3b358b75222660dc09fb716d64fbfc417ba6c99ff3de",
            "small" to "486a46afbb7ba798507190ffe02fea2dd726049af212e774537efac6afb210a6"
        )

        fun modelUrl(model: AsrModel): String =
            "$ASR_MODELS_BASE/${model.packageName}.tar.bz2"
    }

    data class ModelFiles(
        val dir: File,
        val encoder: File,
        val decoder: File,
        val tokens: File,
        val vad: File
    )

    private val rootDir: File get() = File(context.filesDir, MODELS_DIR).apply { mkdirs() }

    /** 模型 + VAD 是否都就绪（文件齐 + .ready 标记在）。 */
    fun isReady(model: AsrModel): Boolean {
        val dir = File(rootDir, model.key)
        if (!File(dir, READY_MARKER).exists()) return false
        return resolveFiles(dir, File(rootDir, VAD_FILE)) != null
    }

    /** 取已就绪的模型文件；没就绪返回 null（不要抛，调用方据此决定要不要下载）。 */
    fun readyFilesOrNull(model: AsrModel): ModelFiles? {
        val dir = File(rootDir, model.key)
        if (!File(dir, READY_MARKER).exists()) return null
        return resolveFiles(dir, File(rootDir, VAD_FILE))
    }

    /**
     * 确保模型可用：缺失就下载并解压。
     * [onProgress] 0..1，只反映“下载”进度；解压阶段回调 -1（UI 显示“正在解压…”）。
     * @throws AsrException 仅 Wi-Fi 下被拒 / 网络失败 / 校验失败 / 解压失败，均为中文。
     */
    suspend fun ensureModel(
        model: AsrModel,
        wifiOnly: Boolean,
        onProgress: (Float) -> Unit = {}
    ): ModelFiles = withContext(Dispatchers.IO) {
        readyFilesOrNull(model)?.let { return@withContext it }
        if (wifiOnly && !isWifiConnected()) {
            throw AsrException("下载模型需要 Wi-Fi（设置里可改成允许移动数据）")
        }
        val dir = File(rootDir, model.key).apply { mkdirs() }
        val tarFile = File(rootDir, "${model.packageName}.tar.bz2")
        try {
            downloadWithResume(modelUrl(model), tarFile, onProgress)
            verifySha256(tarFile, model.key)
            onProgress(-1f)
            extractTarBz2(tarFile, dir)
            val vad = File(rootDir, VAD_FILE)
            if (!vad.exists() || vad.length() == 0L) {
                downloadPlain(VAD_URL, vad)
            }
            val files = resolveFiles(dir, vad)
                ?: throw AsrException("模型包解压后文件不全，已删除损坏文件，请重试")
            File(dir, READY_MARKER).writeText(System.currentTimeMillis().toString())
            runCatching { tarFile.delete() }
            files
        } catch (e: AsrException) {
            // 损坏的模型不要留着：删掉整个目录，下次重新下载，不要崩溃。
            runCatching { dir.deleteRecursively() }
            throw e
        } catch (e: Exception) {
            runCatching { dir.deleteRecursively() }
            throw AsrException("模型下载失败：${e.message ?: "网络错误"}，请检查网络后重试", e)
        }
    }

    /** 删掉指定模型（设置里“删除已下载模型” / 换档位前清理用）。删完下次用时重新下载。 */
    fun deleteModel(model: AsrModel) {
        File(rootDir, model.key).deleteRecursively()
        File(rootDir, "${model.packageName}.tar.bz2").delete()
    }

    fun downloadedBytes(model: AsrModel): Long =
        File(rootDir, model.key).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ---- 内部实现 ----

    /**
     * 在模型目录里找 encoder / decoder / tokens。
     * 不写死文件名：包内文件名是 "<key>-encoder.int8.onnx" 这类格式（tiny.en 例外是
     * "tiny.en-encoder.int8.onnx"），但上游可能改名；按后缀扫描最稳。
     * 找到多个时取最大的那个 onnx（int8 与 fp32 并存时优先 int8 体积小的那个——
     * 实际上按名字含 "int8" 优先）。
     */
    private fun resolveFiles(dir: File, vad: File): ModelFiles? {
        if (!vad.exists() || vad.length() == 0L) return null
        val all = dir.walkTopDown().filter { it.isFile }.toList()
        val encoder = pick(all, "-encoder", ".onnx") ?: return null
        val decoder = pick(all, "-decoder", ".onnx") ?: return null
        val tokens = all.firstOrNull { it.name.endsWith("-tokens.txt") } ?: return null
        return ModelFiles(dir, encoder, decoder, tokens, vad)
    }

    private fun pick(files: List<File>, infix: String, suffix: String): File? {
        val matched = files.filter { it.name.contains(infix) && it.name.endsWith(suffix) }
        return matched.firstOrNull { "int8" in it.name } ?: matched.maxByOrNull { it.length() }
    }

    private fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * 断点续传下载：.part 文件已有多大，就从多大开始 Range 请求；服务器不支持 Range
     * （返回 200 而不是 206）时从头重下。写完后把 .part 原子改名成目标文件。
     * 注意：OkHttp 的 response.body 必须在 use 里关掉，否则连接泄漏。
     */
    private fun downloadWithResume(url: String, target: File, onProgress: (Float) -> Unit) {
        val part = File(target.parentFile, "${target.name}.part")
        val downloaded = if (part.exists()) part.length() else 0L
        val request = Request.Builder().url(url)
            .header("User-Agent", "MoCast/0.4.0")
            .apply { if (downloaded > 0) header("Range", "bytes=$downloaded-") }
            .build()
        Http.client.newCall(request).execute().use { resp ->
            if (resp.code == 416) {
                // 已下满但之前没改名：直接当完成处理。
                part.renameTo(target)
                onProgress(1f)
                return
            }
            if (!resp.isSuccessful) throw AsrException("模型下载失败（HTTP ${resp.code}），请稍后重试")
            val body = resp.body ?: throw AsrException("模型下载失败：服务器没有返回内容")
            // 服务器无视 Range 回了 200：已下的部分作废，从头写。
            val append = resp.code == 206 && downloaded > 0
            if (!append) runCatching { part.delete() }
            val total = body.contentLength().let { len ->
                if (len <= 0) -1L else if (append) len + downloaded else len
            }
            RandomAccessFile(part, "rw").use { out ->
                if (append) out.seek(downloaded) else out.setLength(0)
                val buf = ByteArray(BUFFER_SIZE)
                var done = if (append) downloaded else 0L
                val stream = body.byteStream()
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
            if (!part.renameTo(target)) throw AsrException("模型文件保存失败（存储空间不足？）")
            onProgress(1f)
        }
    }

    /** VAD 是单个小文件，不用断点续传，直接整文件下。 */
    private fun downloadPlain(url: String, target: File) {
        val request = Request.Builder().url(url).header("User-Agent", "MoCast/0.4.0").build()
        Http.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw AsrException("VAD 模型下载失败（HTTP ${resp.code}）")
            val bytes = resp.body?.bytes() ?: throw AsrException("VAD 模型下载失败：没有返回内容")
            if (bytes.isEmpty()) throw AsrException("VAD 模型下载失败：文件为空")
            target.writeBytes(bytes)
        }
    }

    private fun verifySha256(file: File, modelKey: String) {
        val expected = EXPECTED_SHA256[modelKey] ?: return // 没登记 SHA 的档位不校验（不要静默跳过已登记的）
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            runCatching { file.delete() }
            throw AsrException("模型文件校验失败（可能下载不完整或上游更新了模型），已删除，请重试")
        }
    }

    /** 解 tar.bz2 到目标目录。Zip-Slip 防护：entry 路径逃出目标目录直接抛错。 */
    private fun extractTarBz2(tarFile: File, destDir: File) {
        destDir.mkdirs()
        val destPath = destDir.canonicalPath
        FileInputStream(tarFile).use { fis ->
            BufferedInputStream(fis).use { bis ->
                BZip2CompressorInputStream(bis).use { bz ->
                    TarArchiveInputStream(bz).use { tar ->
                        while (true) {
                            val entry = tar.nextEntry ?: break
                            val out = File(destDir, entry.name)
                            if (!out.canonicalPath.startsWith(destPath)) {
                                throw AsrException("模型包里有非法路径，已停止解压")
                            }
                            if (entry.isDirectory) {
                                out.mkdirs()
                            } else {
                                out.parentFile?.mkdirs()
                                FileOutputStream(out).use { fos -> tar.copyTo(fos) }
                            }
                        }
                    }
                }
            }
        }
    }
}
