package tw.deskctl

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 足球比分 (ESPN 公開介面, 免金鑰; 欄位為盡力解析, 缺欄位時該頁顯示「等待數據」)。
 * 追蹤項目的 id 格式: 聯賽代碼 + ":" + 賽事編號, 例如 eng.1:704512 (查 summary 時需要聯賽代碼)。
 */
object ScoreSoccer {
    /** 可選聯賽 (ESPN 代碼, 中文名) */
    val LEAGUES = listOf(
        "eng.1" to "英超", "esp.1" to "西甲", "ger.1" to "德甲", "ita.1" to "義甲", "fra.1" to "法甲",
        "uefa.champions" to "歐冠", "uefa.europa" to "歐霸", "usa.1" to "美職", "fifa.world" to "世界盃",
        "uefa.euro" to "歐國盃", "fifa.friendly" to "國際友誼賽", "jpn.1" to "日職", "ned.1" to "荷甲",
        "por.1" to "葡超", "eng.2" to "英冠", "uefa.nations" to "歐國聯"
    )
    const val DEFAULT = "eng.1,uefa.champions"

    private val TPE: ZoneId = ZoneId.of("Asia/Taipei")
    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports/soccer/"

    fun leagueName(slug: String): String = LEAGUES.firstOrNull { it.first == slug }?.second ?: slug

    private fun ms(s: String): Long {
        val t = if (s.length == 17 && s.endsWith("Z")) s.dropLast(1) + ":00Z" else s
        return try {
            Instant.parse(t).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    private fun fmtTime(ms: Long): String =
        if (ms <= 0) "--:--" else Instant.ofEpochMilli(ms).atZone(TPE).format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))

    private fun state(type: JSONObject?) = when (type?.optString("state")) {
        "in" -> "L"
        "post" -> "F"
        else -> "P"
    }

    private fun side(comps: JSONArray?, home: Boolean): JSONObject? {
        if (comps == null) return null
        for (i in 0 until comps.length()) {
            val c = comps.optJSONObject(i) ?: continue
            if ((c.optString("homeAway") == "home") == home) return c
        }
        return null
    }

    private fun code(c: JSONObject?): String =
        c?.optJSONObject("team")?.optString("abbreviation")?.ifEmpty { null }
            ?: c?.optJSONObject("team")?.optString("shortDisplayName")?.take(4) ?: "?"

    /** 賽程: 台灣日期 date 的所有比賽 (ESPN 的日期是各地當天, 查前一天與當天再用台灣時間篩) */
    fun list(date: LocalDate, slugs: List<String>): List<GameInfo> {
        val out = ArrayList<GameInfo>()
        val seen = HashSet<String>()
        for (slug in slugs) {
            for (d in listOf(date.minusDays(1), date)) {
                val txt = ScoreApi.http(BASE + slug + "/scoreboard?dates=" + d.format(DateTimeFormatter.BASIC_ISO_DATE)) ?: continue
                try {
                    val ev = JSONObject(txt).optJSONArray("events") ?: continue
                    for (i in 0 until ev.length()) {
                        val e = ev.getJSONObject(i)
                        val id = slug + ":" + e.optString("id")
                        if (!seen.add(id)) continue
                        val start = ms(e.optString("date"))
                        if (Instant.ofEpochMilli(start).atZone(TPE).toLocalDate() != date) continue
                        val comps = e.optJSONArray("competitions")?.optJSONObject(0)?.optJSONArray("competitors")
                        val a = side(comps, false)
                        val h = side(comps, true)
                        val st = state(e.optJSONObject("status")?.optJSONObject("type"))
                        out.add(
                            GameInfo(
                                "SOC", "espn", id, code(a), code(h), start, st,
                                if (st != "P") a?.optString("score")?.toIntOrNull() else null,
                                if (st != "P") h?.optString("score")?.toIntOrNull() else null
                            )
                        )
                    }
                } catch (e: Exception) {
                    AppLog.w("SCORE", "足球賽程解析失敗: $e")
                }
            }
        }
        return out.sortedBy { it.startMs }
    }

    private fun lastName(s: String): String {
        val p = s.trim().split(" ")
        return (if (p.size > 1) p.last() else s.trim()).take(12)
    }

    private fun num(s: String): Int = Math.round(s.replace("%", "").trim().toDoubleOrNull() ?: 0.0).toInt()

    /** 單場資料 → 控制器用的 JSON; 失敗回傳 null */
    fun fetch(t: Tracked): JSONObject? {
        val slug = t.id.substringBefore(':')
        val eid = t.id.substringAfter(':')
        val txt = ScoreApi.http(BASE + slug + "/summary?event=" + eid) ?: return null
        try {
            val sum = JSONObject(txt)
            val comp = sum.optJSONObject("header")?.optJSONArray("competitions")?.optJSONObject(0) ?: return null
            val comps = comp.optJSONArray("competitors")
            val a = side(comps, false)
            val h = side(comps, true)
            val stObj = comp.optJSONObject("status")
            val type = stObj?.optJSONObject("type")
            val st = state(type)
            val o = JSONObject().put("lg", "SOC").put("st", st).put("a", t.a).put("h", t.h)
                .put("tn", Teams.cn("SOC", t.a) + " v " + Teams.cn("SOC", t.h))
                .put("tm", fmtTime(t.startMs)).put("ln", leagueName(slug))
            if (st != "P") {
                o.put("as", a?.optString("score")?.toIntOrNull() ?: 0).put("hs", h?.optString("score")?.toIntOrNull() ?: 0)
            }
            val name = type?.optString("name").orEmpty()
            o.put("q", stObj?.optInt("period") ?: 1)
            if (st == "L") {
                val c = when {
                    name.contains("HALFTIME") -> "HT"
                    name.contains("SHOOTOUT") -> "PK"
                    else -> stObj?.optString("displayClock").orEmpty().ifEmpty { type?.optString("shortDetail").orEmpty() }
                }
                o.put("c", c.take(8))
            }
            val pa = a?.opt("shootoutScore")
            val ph = h?.opt("shootoutScore")
            if (pa != null && ph != null) o.put("ps", JSONArray().put(num(pa.toString())).put(num(ph.toString())))
            if (st == "P") return o
            val awayId = a?.optJSONObject("team")?.optString("id").orEmpty()
            // 事件: 進球 / 黃牌 / 紅牌
            val src = comp.optJSONArray("details") ?: sum.optJSONArray("keyEvents")
            val ev = JSONArray()
            if (src != null) for (i in 0 until src.length()) {
                val x = src.optJSONObject(i) ?: continue
                val tx = x.optJSONObject("type")?.optString("text").orEmpty().lowercase()
                val kind = when {
                    x.optBoolean("ownGoal") || tx.contains("own goal") -> "o"
                    x.optBoolean("penaltyKick") && (x.optBoolean("scoringPlay") || tx.contains("goal")) -> "p"
                    x.optBoolean("scoringPlay") || tx.startsWith("goal") -> "g"
                    x.optBoolean("redCard") || tx.contains("red card") -> "r"
                    x.optBoolean("yellowCard") || tx.contains("yellow card") -> "y"
                    else -> continue
                }
                var who = x.optJSONArray("athletesInvolved")?.optJSONObject(0)?.optString("displayName").orEmpty()
                if (who.isEmpty()) who = x.optJSONArray("participants")?.optJSONObject(0)?.optJSONObject("athlete")?.optString("displayName").orEmpty()
                val tid = x.optJSONObject("team")?.optString("id").orEmpty()
                val min = x.optJSONObject("clock")?.optString("displayValue").orEmpty().ifEmpty { x.optJSONObject("clock")?.optString("value").orEmpty() }
                ev.put(JSONArray().put(min.replace("'", "").take(7)).put(if (tid == awayId) 0 else 1).put(kind).put(lastName(who)))
            }
            val keep = JSONArray()
            for (i in maxOf(0, ev.length() - 12) until ev.length()) keep.put(ev.get(i))
            if (keep.length() > 0) o.put("ev", keep)
            // 球隊統計: 控球% 射門 射正 角球 犯規 越位 黃牌 紅牌
            val teams = sum.optJSONObject("boxscore")?.optJSONArray("teams")
            if (teams != null) {
                fun arr(tm: JSONObject?): JSONArray {
                    val m = HashMap<String, String>()
                    val s = tm?.optJSONArray("statistics")
                    if (s != null) for (i in 0 until s.length()) {
                        val e = s.optJSONObject(i) ?: continue
                        m[e.optString("name")] = e.optString("displayValue")
                    }
                    fun g(vararg k: String): Int {
                        for (x in k) m[x]?.let { return num(it) }
                        return 0
                    }
                    return JSONArray().put(g("possessionPct")).put(g("totalShots", "shots")).put(g("shotsOnTarget"))
                        .put(g("wonCorners", "cornerKicks")).put(g("foulsCommitted", "fouls")).put(g("offsides"))
                        .put(g("yellowCards")).put(g("redCards"))
                }
                var ta: JSONObject? = null
                var th: JSONObject? = null
                for (i in 0 until teams.length()) {
                    val x = teams.optJSONObject(i) ?: continue
                    if (x.optJSONObject("team")?.optString("id") == awayId) ta = x else th = x
                }
                if (ta != null && th != null) o.put("sa", arr(ta)).put("sh", arr(th))
            }
            return o
        } catch (e: Exception) {
            AppLog.w("SCORE", "足球資料解析失敗: $e")
            return null
        }
    }
}
