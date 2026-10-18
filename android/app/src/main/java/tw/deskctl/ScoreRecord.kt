package tw.deskctl

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 賽後戰績 (控制器比分板的「賽後戰績」頁): 兩隊賽季成績 + 系列賽 / 對戰狀況。
 * 輸出 o["sr"] = {"t": 一行文字(系列賽或本季交手, 可空), "r": [[標籤, 客隊值, 主隊值], ...]}。
 * 資料來源: MLB statsapi (standings / schedule 的 seriesStatus) 與 ESPN (NBA、足球的 standings 與 summary)。
 * 欄位都是盡力解析, 缺的列就省略; 完賽後資料不會再變, 結果快取起來, 積分榜 10 分鐘更新一次;
 * 抓不到時 60 秒內不重試, 免得控制器每次輪詢都打一堆請求。
 */
object ScoreRecord {
    private val cache = HashMap<String, JSONObject>()
    private val failAt = HashMap<String, Long>()
    private class Std(val at: Long, val v: Map<String, Map<String, String>>)

    private val stds = HashMap<String, Std>()

    /** 積分榜快取: key → (球隊編號 → (欄位名 → 顯示值)) */
    private fun std(key: String, load: () -> Map<String, Map<String, String>>?): Map<String, Map<String, String>>? {
        val now = SystemClock.elapsedRealtime()
        val c = stds[key]
        if (c != null && now - c.at < 10 * 60_000L) return c.v
        val v = try {
            load()
        } catch (e: Exception) {
            AppLog.w("SCORE", "積分榜解析失敗 $key: $e")
            null
        }
        if (v != null && v.isNotEmpty()) {
            stds[key] = Std(now, v)
            return v
        }
        return c?.v
    }

    private fun gated(key: String): Boolean {
        val f = failAt[key] ?: return false
        return SystemClock.elapsedRealtime() - f < 60_000L
    }

    private fun finish(key: String, o: JSONObject, sr: JSONObject?) {
        if (sr != null) {
            cache[key] = sr
            failAt.remove(key)
            o.put("sr", sr)
        } else {
            failAt[key] = SystemClock.elapsedRealtime()
        }
    }

    private fun pack(t: String, rows: List<Triple<String, String, String>>): JSONObject? {
        val r = JSONArray()
        val max = if (t.isNotEmpty()) 3 else 4
        for ((l, a, h) in rows) {
            if (r.length() >= max) break
            if (a.isEmpty() && h.isEmpty()) continue
            r.put(JSONArray().put(l).put(a.ifEmpty { "-" }).put(h.ifEmpty { "-" }))
        }
        if (r.length() == 0 && t.isEmpty()) return null
        return JSONObject().put("t", t).put("r", r)
    }

    /** "W3" → "連勝3", "L2" → "連敗2" */
    private fun streak(code: String?): String {
        if (code.isNullOrEmpty()) return ""
        val n = code.drop(1).trim().toIntOrNull() ?: return code
        return when (code[0]) {
            'W', 'w' -> "連勝$n"
            'L', 'l' -> "連敗$n"
            else -> code
        }
    }

    private fun lead(aw: Int, hw: Int, an: String, hn: String): String =
        if (aw == hw) "平手$aw-$hw" else if (aw > hw) "$an$aw-$hw" else "$hn$hw-$aw"

    // ================= MLB =================
    private val MLB_ROUND = mapOf("F" to "外卡賽", "D" to "分區賽", "L" to "聯盟冠軍賽", "W" to "世界大賽")

    private fun mlbStandings(season: String): Map<String, Map<String, String>>? = std("MLB$season") {
        val txt = ScoreApi.http("https://statsapi.mlb.com/api/v1/standings?leagueId=103,104&season=$season&standingsTypes=regularSeason")
        if (txt == null) {
            null
        } else {
            val out = HashMap<String, Map<String, String>>()
            val recs = JSONObject(txt).optJSONArray("records")
            if (recs != null) for (i in 0 until recs.length()) {
                val trs = recs.getJSONObject(i).optJSONArray("teamRecords") ?: continue
                for (j in 0 until trs.length()) {
                    val r = trs.getJSONObject(j)
                    val id = r.optJSONObject("team")?.optInt("id") ?: continue
                    val m = HashMap<String, String>()
                    m["rec"] = r.optInt("wins").toString() + "-" + r.optInt("losses")
                    val sc = r.optJSONObject("streak")?.optString("streakCode").orEmpty()
                    if (sc.isNotEmpty()) m["stk"] = sc
                    val dr = r.optString("divisionRank")
                    if (dr.isNotEmpty()) m["rank"] = dr
                    val sp = r.optJSONObject("records")?.optJSONArray("splitRecords")
                    if (sp != null) for (k in 0 until sp.length()) {
                        val x = sp.getJSONObject(k)
                        if (x.optString("type") == "lastTen") m["l10"] = x.optInt("wins").toString() + "-" + x.optInt("losses")
                    }
                    out[id.toString()] = m
                }
            }
            out
        }
    }

    /** 兩隊本季對戰 (只算已完賽的例行賽): 回傳 (本季 客隊勝, 主隊勝, 目前這個系列 客隊勝, 主隊勝) */
    private fun mlbH2h(aId: Int, hId: Int, g: JSONObject): IntArray? {
        val date = g.optString("officialDate")
        if (date.length < 10) return null
        val year = date.take(4)
        val txt = ScoreApi.http("https://statsapi.mlb.com/api/v1/schedule?sportId=1&teamId=$aId&opponentId=$hId&startDate=$year-02-01&endDate=$date&gameType=R")
            ?: return null
        class G(val pk: Long, val ms: String, val aWon: Boolean, val hWon: Boolean)

        val list = ArrayList<G>()
        val ds = JSONObject(txt).optJSONArray("dates") ?: return null
        for (i in 0 until ds.length()) {
            val gs = ds.getJSONObject(i).optJSONArray("games") ?: continue
            for (j in 0 until gs.length()) {
                val x = gs.getJSONObject(j)
                if (x.optJSONObject("status")?.optString("abstractGameState") != "Final") continue
                val ta = x.getJSONObject("teams").getJSONObject("away")
                val th = x.getJSONObject("teams").getJSONObject("home")
                val awayIsA = ta.optJSONObject("team")?.optInt("id") == aId
                val awWin = ta.optBoolean("isWinner", ta.optInt("score") > th.optInt("score"))
                val hmWin = th.optBoolean("isWinner", th.optInt("score") > ta.optInt("score"))
                // 換算成「追蹤清單裡的客隊 (aId) / 主隊 (hId)」誰贏
                val aWon = if (awayIsA) awWin else hmWin
                val hWon = if (awayIsA) hmWin else awWin
                list.add(G(x.optLong("gamePk"), x.optString("gameDate"), aWon, hWon))
            }
        }
        list.sortBy { it.ms }
        val aw = list.count { it.aWon }
        val hw = list.count { it.hWon }
        val num = g.optInt("seriesGameNumber", 0)
        val idx = list.indexOfFirst { it.pk == g.optLong("gamePk") }
        var sa = 0
        var sh = 0
        if (num > 0 && idx >= 0) {
            for (k in maxOf(0, idx - num + 1)..idx) {
                if (list[k].aWon) sa++
                if (list[k].hWon) sh++
            }
        }
        return intArrayOf(aw, hw, sa, sh)
    }

    fun mlb(g: JSONObject, t: Tracked, o: JSONObject) {
        val key = "MLB" + t.id
        cache[key]?.let { o.put("sr", it); return }
        if (gated(key)) return
        var sr: JSONObject? = null
        try {
            val aw = g.getJSONObject("teams").getJSONObject("away")
            val hm = g.getJSONObject("teams").getJSONObject("home")
            val aId = aw.getJSONObject("team").optInt("id")
            val hId = hm.getJSONObject("team").optInt("id")
            val an = Teams.board("MLB", t.a, t.an)
            val hn = Teams.board("MLB", t.h, t.hn)
            val season = g.optString("season").ifEmpty { LocalDate.now().year.toString() }
            val s = mlbStandings(season)
            val ra = s?.get(aId.toString())
            val rh = s?.get(hId.toString())
            fun lr(team: JSONObject): String {
                val x = team.optJSONObject("leagueRecord") ?: return ""
                return if (x.has("wins")) x.optInt("wins").toString() + "-" + x.optInt("losses") else ""
            }
            // 系列賽文字
            var line = ""
            val gt = g.optString("gameType")
            val ss = g.optJSONObject("seriesStatus")
            if (gt != "R" && ss != null) {
                val w = maxOf(ss.optInt("wins"), ss.optInt("losses"))
                val l = minOf(ss.optInt("wins"), ss.optInt("losses"))
                val round = MLB_ROUND[gt] ?: "季後賽"
                val gn = ss.optInt("gameNumber")
                val head = round + (if (gn > 0) "第${gn}戰" else "")
                if (ss.has("wins")) {
                    val winId = ss.optJSONObject("winningTeam")?.optInt("id") ?: 0
                    val nm = if (winId == aId) an else hn
                    line = if (ss.optBoolean("isTied")) "$head 平手$w-$l"
                    else head + " " + nm + (if (ss.optBoolean("isOver")) "晉級" else "領先") + "$w-$l"
                } else {
                    line = head + " " + ss.optString("shortName").ifEmpty { ss.optString("result") }.take(24)
                }
            } else if (gt == "R") {
                val h2 = try {
                    mlbH2h(aId, hId, g)
                } catch (e: Exception) {
                    AppLog.w("SCORE", "MLB 對戰解析失敗: $e")
                    null
                }
                if (h2 != null) {
                    val num = g.optInt("seriesGameNumber", 0)
                    val tot = g.optInt("gamesInSeries", 0)
                    val sp = if (num > 0 && tot > 0) "系列賽第$num/${tot}場 " + lead(h2[2], h2[3], an, hn) + " · " else ""
                    line = sp + "本季交手 " + lead(h2[0], h2[1], an, hn)
                }
            }
            val rows = listOf(
                Triple("戰績", ra?.get("rec") ?: lr(aw), rh?.get("rec") ?: lr(hm)),
                Triple("近十場", ra?.get("l10").orEmpty(), rh?.get("l10").orEmpty()),
                Triple("連勝敗", streak(ra?.get("stk")), streak(rh?.get("stk"))),
                Triple("分區", ra?.get("rank")?.let { "第${it}名" }.orEmpty(), rh?.get("rank")?.let { "第${it}名" }.orEmpty())
            )
            sr = pack(line, rows)
        } catch (e: Exception) {
            AppLog.w("SCORE", "MLB 賽後戰績失敗: $e")
        }
        finish(key, o, sr)
    }

    // ================= ESPN 共用 =================
    /** ESPN standings (children → standings.entries): 球隊編號 → (欄位名小寫 → 顯示值) */
    private fun espnStandings(url: String, key: String): Map<String, Map<String, String>>? = std(key) {
        val txt = ScoreApi.http(url)
        if (txt == null) {
            null
        } else {
            val out = HashMap<String, Map<String, String>>()
            fun walk(n: JSONObject) {
                val es = n.optJSONObject("standings")?.optJSONArray("entries")
                if (es != null) for (i in 0 until es.length()) {
                    val e = es.getJSONObject(i)
                    val id = e.optJSONObject("team")?.optString("id").orEmpty()
                    if (id.isEmpty()) continue
                    val m = HashMap<String, String>()
                    val st = e.optJSONArray("stats")
                    if (st != null) for (k in 0 until st.length()) {
                        val x = st.getJSONObject(k)
                        val v = x.optString("displayValue").ifEmpty { x.opt("value")?.toString().orEmpty() }
                        val nm = x.optString("name").lowercase()
                        if (nm.isNotEmpty()) m[nm] = v
                        val sn = x.optString("shortDisplayName").lowercase()
                        if (sn.isNotEmpty() && !m.containsKey(sn)) m[sn] = v
                    }
                    m["_pos"] = (i + 1).toString()
                    out[id] = m
                }
                val ch = n.optJSONArray("children")
                if (ch != null) for (i in 0 until ch.length()) walk(ch.getJSONObject(i))
            }
            walk(JSONObject(txt))
            out
        }
    }

    private fun recOf(c: JSONObject?, vararg types: String): String? {
        val rs = c?.optJSONArray("records") ?: c?.optJSONArray("record") ?: return null
        for (i in 0 until rs.length()) {
            val x = rs.optJSONObject(i) ?: continue
            val ty = x.optString("type").lowercase()
            val nm = x.optString("name").lowercase()
            if (types.any { ty == it || nm == it }) return x.optString("summary").ifEmpty { null }
        }
        return null
    }

    // ================= NBA =================
    private fun zhSeries(raw: String, names: Map<String, String>): String {
        val s = raw.trim()
        Regex("""(?i)tied\s+(\d+)-(\d+)""").find(s)?.let { return "平手" + it.groupValues[1] + "-" + it.groupValues[2] }
        Regex("""(?i)^(\S+)\s+(leads?|wins?|won)\s+(?:the\s+)?(?:series\s+)?(\d+)-(\d+)""").find(s)?.let {
            val nm = names[it.groupValues[1].uppercase()] ?: it.groupValues[1]
            val verb = if (it.groupValues[2].lowercase().startsWith("lead")) "領先" else "晉級"
            return nm + verb + it.groupValues[3] + "-" + it.groupValues[4]
        }
        return s.take(30)
    }

    fun nba(t: Tracked, o: JSONObject, evIn: JSONObject? = null) {
        val key = "NBA" + t.id
        cache[key]?.let { o.put("sr", it); return }
        if (gated(key)) return
        var sr: JSONObject? = null
        try {
            val ev = evIn ?: ScoreApi.findEspn(t)
            if (ev != null) {
                val a = ScoreApi.espnComp(ev, false)
                val h = ScoreApi.espnComp(ev, true)
                val aId = a?.optJSONObject("team")?.optString("id").orEmpty()
                val hId = h?.optJSONObject("team")?.optString("id").orEmpty()
                val s = espnStandings("https://site.api.espn.com/apis/v2/sports/basketball/nba/standings", "NBA")
                val sa = s?.get(aId)
                val sh = s?.get(hId)
                fun pick(m: Map<String, String>?, vararg k: String): String {
                    if (m == null) return ""
                    for (x in k) m[x]?.let { if (it.isNotEmpty()) return it }
                    for ((kk, v) in m) if (v.isNotEmpty() && k.any { x -> kk.contains(x) }) return v
                    return ""
                }
                fun overall(c: JSONObject?, m: Map<String, String>?): String {
                    val r = recOf(c, "total", "overall", "record")
                    if (r != null) return r
                    val o2 = pick(m, "overall")
                    if (o2.isNotEmpty()) return o2
                    return if (m != null && m.containsKey("wins")) m["wins"] + "-" + m["losses"] else ""
                }
                val names = HashMap<String, String>()
                a?.optJSONObject("team")?.optString("abbreviation")?.let { names[it.uppercase()] = Teams.board("NBA", t.a, t.an) }
                h?.optJSONObject("team")?.optString("abbreviation")?.let { names[it.uppercase()] = Teams.board("NBA", t.h, t.hn) }
                // 系列賽 (季後賽) 或本季交手
                var line = ""
                val comp = ev.optJSONArray("competitions")?.optJSONObject(0)
                val series = comp?.optJSONObject("series")
                if (series != null && series.optString("type") == "playoff" && series.optString("summary").isNotEmpty()) {
                    line = "系列賽 " + zhSeries(series.optString("summary"), names)
                } else {
                    val sum = ScoreApi.espnSummary(ev.optString("id"))
                    val ss = sum?.optJSONArray("seasonseries")?.optJSONObject(0)
                    val txt = ss?.optString("summary").orEmpty().ifEmpty { ss?.optString("description").orEmpty() }
                    if (txt.isNotEmpty()) line = "本季交手 " + zhSeries(txt, names)
                }
                val rows = listOf(
                    Triple("戰績", overall(a, sa), overall(h, sh)),
                    Triple("近十場", pick(sa, "lasttengames", "last ten", "l10"), pick(sh, "lasttengames", "last ten", "l10")),
                    Triple("連勝敗", streak(pick(sa, "streak")), streak(pick(sh, "streak"))),
                    Triple("客/主場", recOf(a, "road", "away").orEmpty(), recOf(h, "home").orEmpty())
                )
                sr = pack(line, rows)
            }
        } catch (e: Exception) {
            AppLog.w("SCORE", "NBA 賽後戰績失敗: $e")
        }
        finish(key, o, sr)
    }

    // ================= 足球 =================
    fun soccer(slug: String, t: Tracked, a: JSONObject?, h: JSONObject?, o: JSONObject) {
        val key = "SOC" + t.id
        cache[key]?.let { o.put("sr", it); return }
        if (gated(key)) return
        var sr: JSONObject? = null
        try {
            val aId = a?.optJSONObject("team")?.optString("id").orEmpty()
            val hId = h?.optJSONObject("team")?.optString("id").orEmpty()
            val s = espnStandings("https://site.api.espn.com/apis/v2/sports/soccer/$slug/standings", "SOC$slug")
            val sa = s?.get(aId)
            val sh = s?.get(hId)
            if (sa != null || sh != null) {
                fun v(m: Map<String, String>?, vararg k: String): String {
                    if (m == null) return ""
                    for (x in k) m[x]?.let { if (it.isNotEmpty()) return it }
                    return ""
                }
                fun rank(m: Map<String, String>?): String {
                    val r = v(m, "rank", "_pos")
                    return if (r.isEmpty()) "" else "第${r}名"
                }
                fun wdl(m: Map<String, String>?): String {
                    if (m == null || !m.containsKey("wins")) return ""
                    return v(m, "wins") + "-" + v(m, "ties", "draws") + "-" + v(m, "losses")
                }
                fun gd(m: Map<String, String>?): String {
                    val d = v(m, "pointdifferential", "goaldifference", "gd")
                    val n = d.replace("+", "").toIntOrNull() ?: return d
                    return String.format("%+d", n)
                }
                val rows = listOf(
                    Triple("排名", rank(sa), rank(sh)),
                    Triple("積分", v(sa, "points"), v(sh, "points")),
                    Triple("勝平負", wdl(sa), wdl(sh)),
                    Triple("得失球", gd(sa), gd(sh))
                )
                sr = pack("", rows)
            }
        } catch (e: Exception) {
            AppLog.w("SCORE", "足球積分榜失敗: $e")
        }
        finish(key, o, sr)
    }
}
