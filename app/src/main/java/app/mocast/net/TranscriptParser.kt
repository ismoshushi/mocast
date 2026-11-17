package app.mocast.net

import app.mocast.data.Cue
import org.json.JSONArray
import org.json.JSONObject

/**
 * 官方字幕解析：优先解析 podcast:transcript 指向的 VTT / SRT / JSON，
 * 解析失败或没有字幕时返回空列表，由 UI 提示“本集没有官方字幕”。
 */
object TranscriptParser {

    fun parse(raw: String): List<Cue> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        return when {
            text.startsWith("{") || text.startsWith("[") -> runCatching { parseJson(text) }.getOrDefault(emptyList())
            text.contains("-->") -> parseVttSrt(text)
            else -> emptyList()
        }
    }

    private fun parseJson(raw: String): List<Cue> {
        val root = if (raw.startsWith("[")) JSONObject().put("segments", JSONArray(raw)) else JSONObject(raw)
        val segments = root.optJSONArray("segments")
            ?: root.optJSONArray("transcript")
            ?: return emptyList()
        val out = ArrayList<Cue>(segments.length())
        for (i in 0 until segments.length()) {
            val s = segments.optJSONObject(i) ?: continue
            val start = s.optDouble("startTime", s.optDouble("start", -1.0))
            if (start < 0) continue
            val end = s.optDouble("endTime", s.optDouble("end", start))
            val body = s.optString("body").ifEmpty { s.optString("text") }
            if (body.isBlank()) continue
            out.add(Cue((start * 1000).toLong(), (end * 1000).toLong(), body.trim()))
        }
        return out.sortedBy { it.startMs }
    }

    private val timing = Regex("(\\d{1,2}:)?\\d{1,2}:\\d{2}[.,]\\d{3}\\s*-->\\s*(\\d{1,2}:)?\\d{1,2}:\\d{2}[.,]\\d{3}")

    private fun parseVttSrt(raw: String): List<Cue> {
        val lines = raw.replace("\r\n", "\n").split("\n")
        val out = ArrayList<Cue>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (!timing.containsMatchIn(line)) {
                i++
                continue
            }
            val start = line.substringBefore("-->").trim()
            val end = line.substringAfter("-->").trim().split(" ").first()
            val textLines = ArrayList<String>()
            i++
            while (i < lines.size && lines[i].isNotBlank() && !timing.containsMatchIn(lines[i])) {
                textLines.add(lines[i].replace(Regex("<[^>]+>"), "").trim())
                i++
            }
            val content = textLines.joinToString(" ").trim()
            if (content.isNotEmpty()) {
                out.add(Cue(parseTs(start), parseTs(end), content))
            }
        }
        return out
    }

    private fun parseTs(value: String): Long {
        val cleaned = value.replace(',', '.')
        val parts = cleaned.split(":")
        val (h, m, s) = when (parts.size) {
            3 -> Triple(parts[0].toIntOrNull() ?: 0, parts[1].toIntOrNull() ?: 0, parts[2].toDoubleOrNull() ?: 0.0)
            2 -> Triple(0, parts[0].toIntOrNull() ?: 0, parts[1].toDoubleOrNull() ?: 0.0)
            else -> Triple(0, 0, 0.0)
        }
        return ((h * 3600 + m * 60) * 1000 + (s * 1000).toLong())
    }
}
