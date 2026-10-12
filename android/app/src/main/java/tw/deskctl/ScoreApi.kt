package tw.deskctl

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 追蹤中的比賽。src: nba=NBA 官方資料 / espn=ESPN 備援 / mlb / cpbl */
class Tracked(val lg: String, val src: String, val id: String, val a: String, val h: String, val startMs: Long) {
    fun toJson(): JSONObject = JSONObject().put("lg", lg).put("src", src).put("id", id).put("a", a).put("h", h).put("t", startMs)

    companion object {
        fun from(o: JSONObject) = Tracked(
            o.optString("lg"), o.optString("src"), o.optString("id"), o.optString("a"), o.optString("h"), o.optLong("t")
        )
    }
}

/** 賽程清單上的一場比賽 (給手機畫面選擇用) */
class GameInfo(
    val lg: String, val src: String, val id: String, val a: String, val h: String, val startMs: Long,
    val status: String, val aScore: Int?, val hScore: Int?
) {
    fun tracked() = Tracked(lg, src, id, a, h, startMs)
}

object ScoreStore {
    @Volatile var version = 0
    const val MAX = 8

    fun load(c: android.content.Context): MutableList<Tracked> {
        val out = ArrayList<Tracked>()
        try {
            val a = JSONArray(Prefs.scoreTracked(c))
            for (i in 0 until a.length()) out.add(Tracked.from(a.getJSONObject(i)))
        } catch (_: Exception) {
        }
        return out
    }

    fun save(c: android.content.Context, l: List<Tracked>) {
        val a = JSONArray()
        for (t in l.take(MAX)) a.put(t.toJson())
        Prefs.setScoreTracked(c, a.toString())
        version++
    }
}

/**
 * 比分資料來源 (全部是公開、免金鑰的網路介面):
 *  - MLB : statsapi.mlb.com (官方)  → 比分、局數半局、好壞球、出局、壘包、打者投手、每局得分
 *  - NBA : cdn.nba.com 官方資料 (賽程 / 即時 boxscore → 犯規、暫停、罰球加成); 失敗時用 ESPN (沒有犯規資料)
 *  - CPBL: cpbl.com.tw 官網 (沒有公開 API, 屬盡力而為; 網站改版就可能失效, 失敗時看「記錄」的 CPBL 標籤)
 * 網路請求都必須在背景執行緒呼叫。
 */
object ScoreApi {
    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    private val TPE: ZoneId = ZoneId.of("Asia/Taipei")
    private val ET: ZoneId = ZoneId.of("America/New_York")

    init {
        try {
            if (CookieHandler.getDefault() == null) CookieHandler.setDefault(CookieManager())
        } catch (_: Exception) {
        }
    }

    fun http(url: String, post: String? = null, headers: Map<String, String> = emptyMap()): String? {
        var c: HttpURLConnection? = null
        try {
            c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/json, text/plain, */*")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (post != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                if (!headers.containsKey("Content-Type")) c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                c.outputStream.use { it.write(post.toByteArray()) }
            }
            if (c.responseCode !in 200..299) {
                AppLog.w("SCORE", "HTTP ${c.responseCode} " + url.substringBefore('?').takeLast(60))
                return null
            }
            return BufferedReader(InputStreamReader(c.inputStream, "UTF-8")).use { it.readText() }
        } catch (e: Exception) {
            AppLog.w("SCORE", "連線失敗 " + url.substringBefore('?').takeLast(50) + ": " + e.javaClass.simpleName)
            return null
        } finally {
            c?.disconnect()
        }
    }

    private fun fmtTime(ms: Long): String =
        if (ms <= 0) "--:--" else Instant.ofEpochMilli(ms).atZone(TPE).format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))

    private fun isoMs(s: String): Long = try {
        Instant.parse(s).toEpochMilli()
    } catch (_: Exception) {
        try {
            LocalDateTime.parse(s.take(19)).atZone(TPE).toInstant().toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    fun list(lg: String, date: LocalDate): List<GameInfo> = when (lg) {
        "MLB" -> listMlb(date)
        "NBA" -> listNba(date)
        "CPBL" -> listCpbl(date)
        else -> emptyList()
    }

    /** 回傳 欄位編號 → 要傳給控制器的 JSON (欄位編號 = 在追蹤清單裡的順序) */
    /** sel: 控制器正在看的欄位; detail: 控制器是否正開著比分頁 (是才抓 MLB 即時細節, 那份資料很大) */
    fun fetch(list: List<Tracked>, sel: Int = -1, detail: Boolean = false): Map<Int, JSONObject> {
        val out = HashMap<Int, JSONObject>()
        // MLB 一次抓全部
        val mlb = list.withIndex().filter { it.value.lg == "MLB" }
        if (mlb.isNotEmpty()) {
            val selId = if (detail && sel in list.indices && list[sel].lg == "MLB") list[sel].id else ""
            val m = fetchMlb(mlb.map { it.value }, selId)
            for ((idx, t) in mlb) m[t.id]?.let { out[idx] = it.put("k", "score").put("i", idx) }
        }
        for ((idx, t) in list.withIndex()) {
            val o = when (t.lg) {
                "NBA" -> fetchNba(t)
                "CPBL" -> fetchCpbl(t)
                else -> null
            } ?: continue
            out[idx] = o.put("k", "score").put("i", idx)
        }
        return out
    }

    private fun base(t: Tracked, st: String): JSONObject {
        val o = JSONObject().put("lg", t.lg).put("st", st).put("a", t.a).put("h", t.h)
            .put("tn", Teams.cn(t.lg, t.a) + " v " + Teams.cn(t.lg, t.h))
        if (st == "P") o.put("tm", fmtTime(t.startMs))
        return o
    }

    // ================= MLB =================
    private fun mlbGames(url: String): List<JSONObject> {
        val txt = http(url) ?: return emptyList()
        val out = ArrayList<JSONObject>()
        try {
            val dates = JSONObject(txt).optJSONArray("dates") ?: return out
            for (i in 0 until dates.length()) {
                val g = dates.getJSONObject(i).optJSONArray("games") ?: continue
                for (j in 0 until g.length()) out.add(g.getJSONObject(j))
            }
        } catch (e: Exception) {
            AppLog.w("SCORE", "MLB 解析失敗: $e")
        }
        return out
    }

    private fun mlbState(g: JSONObject): String = when (g.optJSONObject("status")?.optString("abstractGameState")) {
        "Live" -> "L"
        "Final" -> "F"
        else -> "P"
    }

    private fun abbr(team: JSONObject?): String =
        team?.optString("abbreviation")?.ifEmpty { null } ?: team?.optString("teamName")?.take(4) ?: "?"

    /** statsapi 的 date 是「美國當地日期」; 台灣日期 date 的比賽可能落在美國前一天或當天, 兩天都查再用台灣時間篩 */
    private fun listMlb(date: LocalDate): List<GameInfo> {
        val seen = HashSet<Long>()
        val out = ArrayList<GameInfo>()
        for (d in listOf(date.minusDays(1), date)) {
            for (g in mlbGames("https://statsapi.mlb.com/api/v1/schedule?sportId=1&date=$d&hydrate=team")) {
                val pk = g.optLong("gamePk")
                if (!seen.add(pk)) continue
                val start = isoMs(g.optString("gameDate"))
                if (Instant.ofEpochMilli(start).atZone(TPE).toLocalDate() != date) continue
                val aw = g.getJSONObject("teams").getJSONObject("away")
                val hm = g.getJSONObject("teams").getJSONObject("home")
                out.add(
                    GameInfo(
                        "MLB", "mlb", pk.toString(), abbr(aw.optJSONObject("team")), abbr(hm.optJSONObject("team")),
                        start, mlbState(g),
                        if (aw.has("score")) aw.optInt("score") else null, if (hm.has("score")) hm.optInt("score") else null
                    )
                )
            }
        }
        return out.sortedBy { it.startMs }
    }

    private fun fetchMlb(ts: List<Tracked>, selId: String): Map<String, JSONObject> {
        val ids = ts.joinToString(",") { it.id }
        val games = mlbGames("https://statsapi.mlb.com/api/v1/schedule?sportId=1&gamePks=$ids&hydrate=team,linescore,decisions,probablePitcher")
        val out = HashMap<String, JSONObject>()
        for (g in games) {
            try {
                val id = g.optLong("gamePk").toString()
                val t = ts.firstOrNull { it.id == id } ?: continue
                val st = mlbState(g)
                val o = base(t, st)
                val aw = g.getJSONObject("teams").getJSONObject("away")
                val hm = g.getJSONObject("teams").getJSONObject("home")
                val ls = g.optJSONObject("linescore")
                rec(aw)?.let { o.put("ar", it) }
                rec(hm)?.let { o.put("hr", it) }
                if (st == "P") {
                    val ap = aw.optJSONObject("probablePitcher")
                    val hp = hm.optJSONObject("probablePitcher")
                    ap?.optString("fullName")?.let { o.put("ap", lastName(it)) }
                    hp?.optString("fullName")?.let { o.put("hp", lastName(it)) }
                    val ids = listOfNotNull(ap?.optInt("id"), hp?.optInt("id"))
                    if (ids.isNotEmpty()) {
                        val ps = pitcherSeason(ids)
                        ap?.let { ps[it.optInt("id")]?.let { v -> o.put("ax", v) } }
                        hp?.let { ps[it.optInt("id")]?.let { v -> o.put("hx", v) } }
                    }
                }
                if (st != "P") {
                    o.put("as", aw.optInt("score", ls?.optJSONObject("teams")?.optJSONObject("away")?.optInt("runs") ?: 0))
                    o.put("hs", hm.optInt("score", ls?.optJSONObject("teams")?.optJSONObject("home")?.optInt("runs") ?: 0))
                }
                if (st == "F") {
                    val dc = g.optJSONObject("decisions")
                    dc?.optJSONObject("winner")?.optString("fullName")?.let { o.put("wp", lastName(it)) }
                    dc?.optJSONObject("loser")?.optString("fullName")?.let { o.put("lp", lastName(it)) }
                    dc?.optJSONObject("save")?.optString("fullName")?.let { o.put("sv", lastName(it)) }
                }
                if (ls != null && st != "P") {
                    val inn = ls.optInt("currentInning", 1)
                    val half = ls.optString("inningHalf")
                    o.put("in", inn)
                    o.put("hf", if (half == "Bottom" || half == "End") 1 else 0)
                    val mid = half == "Middle" || half == "End"
                    if (st == "L") {
                        o.put("tx", inn.toString() + (if (mid) (if (half == "Middle") "中" else "終") else "局"))
                        if (mid) o.put("mid", 1)
                        o.put("b", ls.optInt("balls")).put("s", ls.optInt("strikes")).put("o", ls.optInt("outs"))
                        val off = ls.optJSONObject("offense")
                        var bs = 0
                        if (off != null && !mid) {
                            if (off.has("first")) bs = bs or 1
                            if (off.has("second")) bs = bs or 2
                            if (off.has("third")) bs = bs or 4
                            off.optJSONObject("batter")?.optString("fullName")?.let { o.put("bt", lastName(it)) }
                        }
                        o.put("bs", bs)
                        ls.optJSONObject("defense")?.optJSONObject("pitcher")?.optString("fullName")?.let { o.put("pt", lastName(it)) }
                    }
                    if (st == "L" && !mid && id == selId) mlbLive(id, o)
                    val lt = ls.optJSONObject("teams")
                    o.put("ah", lt?.optJSONObject("away")?.optInt("hits") ?: 0).put("hh", lt?.optJSONObject("home")?.optInt("hits") ?: 0)
                    o.put("ae", lt?.optJSONObject("away")?.optInt("errors") ?: 0).put("he", lt?.optJSONObject("home")?.optInt("errors") ?: 0)
                    val ai = JSONArray()
                    val hi = JSONArray()
                    val inns = ls.optJSONArray("innings")
                    if (inns != null) {
                        for (i in 0 until inns.length()) {
                            val x = inns.getJSONObject(i)
                            val ar = x.optJSONObject("away")
                            val hr = x.optJSONObject("home")
                            if (ar != null && ar.has("runs")) ai.put(ar.optInt("runs"))
                            if (hr != null && hr.has("runs")) hi.put(hr.optInt("runs"))
                        }
                    }
                    o.put("ai", ai).put("hi", hi)
                }
                out[id] = o
            } catch (e: Exception) {
                AppLog.w("SCORE", "MLB 單場解析失敗: $e")
            }
        }
        return out
    }

    /** "Shohei Ohtani" → "S. Ohtani"; 結尾的 Jr. / II 等會跳過 */
    private fun lastName(n: String): String {
        val p = n.trim().split(" ").filter { it.isNotEmpty() }
        if (p.size < 2) return n.trim()
        val sfx = setOf("jr.", "jr", "sr.", "sr", "ii", "iii", "iv")
        val last = if (p.last().lowercase() in sfx && p.size >= 3) p[p.size - 2] else p.last()
        return p[0].first().uppercaseChar().toString() + ". " + last
    }

    private fun rec(team: JSONObject): String? {
        val lr = team.optJSONObject("leagueRecord") ?: return null
        return if (lr.has("wins")) lr.optInt("wins").toString() + "-" + lr.optInt("losses") else null
    }

    /** 先發投手本季戰績: id → "12-5 ERA 2.85" */
    private fun pitcherSeason(ids: List<Int>): Map<Int, String> {
        val out = HashMap<Int, String>()
        val h = URLEncoder.encode("stats(group=[pitching],type=[season])", "UTF-8")
        val txt = http("https://statsapi.mlb.com/api/v1/people?personIds=" + ids.joinToString(",") + "&hydrate=" + h) ?: return out
        try {
            val ppl = JSONObject(txt).optJSONArray("people") ?: return out
            for (i in 0 until ppl.length()) {
                val p = ppl.getJSONObject(i)
                val sp = p.optJSONArray("stats")?.optJSONObject(0)?.optJSONArray("splits")?.optJSONObject(0)?.optJSONObject("stat") ?: continue
                out[p.optInt("id")] = sp.optInt("wins").toString() + "-" + sp.optInt("losses") + " ERA " + sp.optString("era")
            }
        } catch (e: Exception) {
            AppLog.w("SCORE", "先發投手戰績解析失敗: $e")
        }
        return out
    }

    private fun evAbbr(ev: String): String = when (ev) {
        "Single" -> "1B"
        "Double" -> "2B"
        "Triple" -> "3B"
        "Home Run" -> "HR"
        "Walk" -> "BB"
        "Intent Walk" -> "IBB"
        "Hit By Pitch" -> "HBP"
        "Strikeout", "Strikeout Double Play", "Strikeout Triple Play" -> "K"
        "Flyout", "Sac Fly", "Sac Fly Double Play" -> if (ev == "Flyout") "FO" else "SF"
        "Groundout", "Bunt Groundout" -> "GO"
        "Lineout", "Bunt Lineout" -> "LO"
        "Pop Out", "Bunt Pop Out" -> "PO"
        "Forceout", "Fielders Choice", "Fielders Choice Out" -> "FC"
        "Field Error" -> "E"
        "Grounded Into DP", "Double Play" -> "GDP"
        "Triple Play" -> "TP"
        "Sac Bunt", "Sac Bunt Double Play" -> "SH"
        "Catcher Interference" -> "CI"
        else -> ev.take(3).uppercase()
    }

    /** 即時細節 (只抓控制器正在看的那一場): 打者/投手/下兩棒、上一球球種球速、上一個打席結果 */
    private fun mlbLive(pk: String, o: JSONObject) {
        val txt = http("https://statsapi.mlb.com/api/v1.1/game/$pk/feed/live") ?: return
        try {
            val live = JSONObject(txt).getJSONObject("liveData")
            val plays = live.getJSONObject("plays")
            val cur = plays.optJSONObject("currentPlay")
            val m = cur?.optJSONObject("matchup") ?: return
            val teams = live.getJSONObject("boxscore").getJSONObject("teams")
            fun player(id: Int): JSONObject? {
                for (side in listOf("away", "home")) {
                    val p = teams.getJSONObject(side).optJSONObject("players")?.optJSONObject("ID$id")
                    if (p != null) return p
                }
                return null
            }
            fun avgOf(p: JSONObject?): String {
                val avg = p?.optJSONObject("seasonStats")?.optJSONObject("batting")?.optString("avg") ?: ""
                return if (avg.isEmpty() || avg.contains("-")) "" else (if (avg.startsWith("0.")) avg.substring(1) else avg)
            }
            fun habOf(p: JSONObject?): String {
                val bs = p?.optJSONObject("stats")?.optJSONObject("batting") ?: return ""
                return bs.optInt("hits").toString() + "-" + bs.optInt("atBats")
            }
            val b = m.optJSONObject("batter")
            val p = m.optJSONObject("pitcher")
            if (b != null) {
                o.put("bt", lastName(b.optString("fullName")))
                val bp = player(b.optInt("id"))
                avgOf(bp).let { if (it.isNotEmpty()) o.put("bavg", it) }
                habOf(bp).let { if (it.isNotEmpty()) o.put("bhab", it) }
            }
            if (p != null) {
                o.put("pt", lastName(p.optString("fullName")))
                val pp = player(p.optInt("id"))
                val ps = pp?.optJSONObject("stats")?.optJSONObject("pitching")
                if (ps != null) {
                    if (ps.has("numberOfPitches")) o.put("pc", ps.optInt("numberOfPitches"))
                    ps.optString("inningsPitched").takeIf { it.isNotEmpty() }?.let { o.put("pip", it) }
                    if (ps.has("earnedRuns")) o.put("per", ps.optInt("earnedRuns"))
                    if (ps.has("strikeOuts")) o.put("pk", ps.optInt("strikeOuts"))
                    if (ps.has("baseOnBalls")) o.put("pbb", ps.optInt("baseOnBalls"))
                }
                pp?.optJSONObject("seasonStats")?.optJSONObject("pitching")?.optString("era")?.takeIf { it.isNotEmpty() }?.let { o.put("pe", it) }
            }
            // 下兩棒
            val off = live.optJSONObject("linescore")?.optJSONObject("offense")
            var k = 1
            for (key in listOf("onDeck", "inHole")) {
                val nb = off?.optJSONObject(key) ?: continue
                o.put("n$k", lastName(nb.optString("fullName")))
                val np = player(nb.optInt("id"))
                o.put("v$k", (avgOf(np) + " " + habOf(np)).trim())
                k++
            }
            // 上一球 (目前打席還沒投球就往前一個打席找)
            val all = plays.optJSONArray("allPlays")
            fun lastPitch(play: JSONObject?): JSONObject? {
                val ev = play?.optJSONArray("playEvents") ?: return null
                for (i in ev.length() - 1 downTo 0) {
                    val e = ev.getJSONObject(i)
                    if (e.optBoolean("isPitch")) return e
                }
                return null
            }
            var lp = lastPitch(cur)
            if (lp == null && all != null && all.length() >= 2) lp = lastPitch(all.optJSONObject(all.length() - 2))
            if (lp != null) {
                lp.optJSONObject("details")?.optJSONObject("type")?.optString("code")?.takeIf { it.isNotEmpty() }?.let { o.put("lt", it) }
                val sp = lp.optJSONObject("pitchData")?.optDouble("startSpeed", -1.0) ?: -1.0
                if (sp > 0) o.put("lv", String.format(java.util.Locale.US, "%.1f", sp))
            }
            // 上一個完成的打席結果
            if (all != null) {
                for (i in all.length() - 1 downTo 0) {
                    val pl = all.getJSONObject(i)
                    if (pl.optJSONObject("about")?.optBoolean("isComplete") == true) {
                        val ev = pl.optJSONObject("result")?.optString("event") ?: ""
                        if (ev.isNotEmpty()) {
                            o.put("lr", evAbbr(ev))
                            pl.optJSONObject("matchup")?.optJSONObject("batter")?.optString("fullName")?.let { o.put("ln", lastName(it)) }
                        }
                        break
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.w("SCORE", "MLB 即時資料解析失敗: $e")
        }
    }

    // ================= NBA =================
    private val nbaHeaders = mapOf("Referer" to "https://www.nba.com/", "Origin" to "https://www.nba.com")
    private var nbaSched: JSONArray? = null
    private var nbaSchedAt = 0L

    private fun nbaSchedule(): JSONArray? {
        val now = System.currentTimeMillis()
        if (nbaSched != null && now - nbaSchedAt < 3600_000L) return nbaSched
        val txt = http("https://cdn.nba.com/static/json/staticData/scheduleLeagueV2_1.json", null, nbaHeaders) ?: return nbaSched
        return try {
            val a = JSONObject(txt).getJSONObject("leagueSchedule").getJSONArray("gameDates")
            nbaSched = a
            nbaSchedAt = now
            a
        } catch (e: Exception) {
            AppLog.w("SCORE", "NBA 賽程解析失敗: $e")
            nbaSched
        }
    }

    private fun nbaState(code: Int) = when (code) {
        2 -> "L"
        3 -> "F"
        else -> "P"
    }

    /** 美東日期: 賽程以美東日期為準; 台灣時間的「今天」晚上/清晨對應的比賽多半是美東前一天或當天 */
    private fun listNba(date: LocalDate): List<GameInfo> {
        val out = ArrayList<GameInfo>()
        val sched = nbaSchedule()
        if (sched != null) {
            // 台灣日期 date 的 00:00~24:00 = 美東前一天傍晚~當天: 兩個美東日期都要找, 再用台灣時間過濾
            val wanted = setOf(date.minusDays(1), date)
            val fmt = DateTimeFormatter.ofPattern("MM/dd/yyyy")
            val keys = wanted.map { it.format(fmt) }
            for (i in 0 until sched.length()) {
                val d = sched.getJSONObject(i)
                val gd = d.optString("gameDate").take(10)
                if (gd !in keys) continue
                val gs = d.optJSONArray("games") ?: continue
                for (j in 0 until gs.length()) {
                    val g = gs.getJSONObject(j)
                    val start = isoMs(g.optString("gameDateTimeUTC"))
                    if (Instant.ofEpochMilli(start).atZone(TPE).toLocalDate() != date) continue
                    val ht = g.optJSONObject("homeTeam")
                    val at = g.optJSONObject("awayTeam")
                    val fin = g.optInt("gameStatus", 1)
                    out.add(
                        GameInfo(
                            "NBA", "nba", g.optString("gameId"), at?.optString("teamTricode") ?: "?", ht?.optString("teamTricode") ?: "?",
                            start, nbaState(fin),
                            if (fin >= 2 && at != null && at.has("score")) at.optInt("score") else null,
                            if (fin >= 2 && ht != null && ht.has("score")) ht.optInt("score") else null
                        )
                    )
                }
            }
        }
        // 季前賽等比賽可能不在官方賽程檔裡, 一律再用 ESPN 補上沒出現的比賽 (用兩隊代號比對)
        val espn = listEspnNba(date)
        val have = out.map { nbaKey(it.a) + nbaKey(it.h) }.toSet()
        val extra = espn.filter { (nbaKey(it.a) + nbaKey(it.h)) !in have }
        AppLog.i("SCORE", "NBA " + date + ": 官方賽程 " + out.size + " 場, ESPN 補 " + extra.size + " 場")
        return (out + extra).sortedBy { it.startMs }
    }

    private val nbaAlias = mapOf("GS" to "GSW", "NY" to "NYK", "NO" to "NOP", "SA" to "SAS", "UTAH" to "UTA", "WSH" to "WAS", "PHO" to "PHX", "BKN" to "BKN", "CHA" to "CHA")

    private fun nbaKey(t: String): String = nbaAlias[t.uppercase()] ?: t.uppercase()

    private fun espnDate(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ET).format(DateTimeFormatter.BASIC_ISO_DATE)

    private fun espnEvents(dateStr: String): List<JSONObject> {
        val txt = http("https://site.api.espn.com/apis/site/v2/sports/basketball/nba/scoreboard?dates=$dateStr") ?: return emptyList()
        val out = ArrayList<JSONObject>()
        try {
            val ev = JSONObject(txt).optJSONArray("events") ?: return out
            for (i in 0 until ev.length()) out.add(ev.getJSONObject(i))
        } catch (e: Exception) {
            AppLog.w("SCORE", "ESPN 解析失敗: $e")
        }
        return out
    }

    private fun espnComp(ev: JSONObject, home: Boolean): JSONObject? {
        val cs = ev.optJSONArray("competitions")?.optJSONObject(0)?.optJSONArray("competitors") ?: return null
        for (i in 0 until cs.length()) {
            val c = cs.getJSONObject(i)
            if ((c.optString("homeAway") == "home") == home) return c
        }
        return null
    }

    private fun espnState(ev: JSONObject) = when (ev.optJSONObject("status")?.optJSONObject("type")?.optString("state")) {
        "in" -> "L"
        "post" -> "F"
        else -> "P"
    }

    /** ESPN 的時間格式是 2026-10-12T02:00Z (沒有秒), Instant.parse 讀不了, 補上秒 */
    private fun espnMs(s: String): Long = isoMs(if (s.length == 17 && s.endsWith("Z")) s.dropLast(1) + ":00Z" else s)

    private fun listEspnNba(date: LocalDate): List<GameInfo> {
        val out = ArrayList<GameInfo>()
        val seen = HashSet<String>()
        for (d in listOf(date.minusDays(1), date)) {
            for (ev in espnEvents(d.format(DateTimeFormatter.BASIC_ISO_DATE))) {
                val id = ev.optString("id")
                if (!seen.add(id)) continue
                val start = espnMs(ev.optString("date"))
                if (Instant.ofEpochMilli(start).atZone(TPE).toLocalDate() != date) continue
                val h = espnComp(ev, true)
                val a = espnComp(ev, false)
                val st = espnState(ev)
                out.add(
                    GameInfo(
                        "NBA", "espn", id, a?.optJSONObject("team")?.optString("abbreviation") ?: "?",
                        h?.optJSONObject("team")?.optString("abbreviation") ?: "?", start, st,
                        if (st != "P") a?.optString("score")?.toIntOrNull() else null, if (st != "P") h?.optString("score")?.toIntOrNull() else null
                    )
                )
            }
        }
        return out.sortedBy { it.startMs }
    }

    /** "PT05M32.00S" → "5:32"; 最後一分鐘顯示 "0:45" (秒); 小於 1 分且有小數 → "0:45" */
    private class ClkState(var tenths: Int, var running: Boolean, var same: Int)

    private val clkPrev = HashMap<String, ClkState>()

    /** "PT05M32.40S" → 3324 (0.1 秒); 讀不出來回傳 -1 */
    private fun nbaTenths(s: String): Int {
        val m = Regex("PT(\\d+)M(\\d+)(?:\\.(\\d+))?S").find(s) ?: return -1
        val frac = m.groupValues[3].firstOrNull()?.digitToInt() ?: 0
        return m.groupValues[1].toInt() * 600 + m.groupValues[2].toInt() * 10 + frac
    }

    /** ESPN 的 displayClock: "5:32" 或最後一分鐘的 "45.3" */
    private fun espnTenths(s: String): Int {
        val t = s.trim()
        val c = t.split(":")
        return try {
            if (c.size == 2) c[0].toInt() * 600 + c[1].substringBefore('.').toInt() * 10
            else (t.toDouble() * 10).toInt()
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * 計時器: 送出剩餘 0.1 秒數 cs 與「是否正在跑」cr, 控制器會在兩次更新之間自己往下推算。
     * 判斷方式: 和上一次比, 變少 = 在跑; 連續兩次一樣 = 停表 (暫停、罰球、節間); 開局第一次剛好是整節時間 = 還沒開始。
     */
    private fun clockFields(o: JSONObject, id: String, tenths: Int, brk: Boolean, period: Int) {
        if (tenths < 0) return
        val cs = if (brk) 0 else tenths
        val full = if (period <= 4) 7200 else 3000
        val prev = clkPrev[id]
        val running: Boolean
        if (brk) {
            running = false
            clkPrev[id] = ClkState(cs, false, 0)
        } else if (prev == null) {
            running = cs < full
            clkPrev[id] = ClkState(cs, running, 0)
        } else if (cs < prev.tenths) {
            running = true
            prev.tenths = cs; prev.running = true; prev.same = 0
        } else if (cs == prev.tenths) {
            prev.same++
            if (prev.same >= 2) prev.running = false
            running = prev.running
        } else {
            running = false
            prev.tenths = cs; prev.running = false; prev.same = 0
        }
        o.put("cs", cs).put("cr", if (running) 1 else 0)
    }

    private fun nbaClock(s: String): String {
        val m = Regex("PT(\\d+)M(\\d+)(?:\\.(\\d+))?S").find(s) ?: return ""
        val mm = m.groupValues[1].toInt()
        val ss = m.groupValues[2].toInt()
        return "%d:%02d".format(mm, ss)
    }

    private fun periodScores(arr: JSONArray?): JSONArray {
        val out = JSONArray()
        if (arr == null) return out
        for (i in 0 until arr.length()) out.put(arr.getJSONObject(i).optInt("score"))
        return out
    }

    private fun fetchNba(t: Tracked): JSONObject? {
        if (t.src == "espn") return fetchEspnNba(t)
        val now = System.currentTimeMillis()
        val txt = http("https://cdn.nba.com/static/json/liveData/boxscore/boxscore_${t.id}.json", null, nbaHeaders)
        if (txt == null) {
            // 還沒開賽時沒有 boxscore (404): 顯示開賽時間; 已過開賽時間很久仍抓不到才算失敗
            return if (now < t.startMs) base(t, "P") else fetchEspnNba(t)
        }
        try {
            val g = JSONObject(txt).getJSONObject("game")
            val st = nbaState(g.optInt("gameStatus", 1))
            val o = base(t, st)
            if (st == "P") return o
            val ht = g.getJSONObject("homeTeam")
            val at = g.getJSONObject("awayTeam")
            o.put("as", at.optInt("score")).put("hs", ht.optInt("score"))
            val q = g.optInt("period", 1)
            o.put("q", q)
            val clk = nbaClock(g.optString("gameClock"))
            o.put("c", clk)
            val brk = st == "L" && (clk == "0:00" || clk.isEmpty())
            if (brk) o.put("brk", 1)
            if (st == "L") clockFields(o, t.id, nbaTenths(g.optString("gameClock")), brk, q)
            fun fouls(team: JSONObject): Int {
                val s = team.optJSONObject("statistics") ?: return 0
                return if (s.has("foulsTeam")) s.optInt("foulsTeam") else s.optInt("foulsPersonal")
            }
            o.put("af", fouls(at)).put("hf2", fouls(ht))
            o.put("at", at.optInt("timeoutsRemaining")).put("ht", ht.optInt("timeoutsRemaining"))
            o.put("ab", if (at.optString("inBonus") in listOf("1", "true")) 1 else 0)
            o.put("hb", if (ht.optString("inBonus") in listOf("1", "true")) 1 else 0)
            o.put("aq", periodScores(at.optJSONArray("periods"))).put("hq", periodScores(ht.optJSONArray("periods")))
            return o
        } catch (e: Exception) {
            AppLog.w("SCORE", "NBA boxscore 解析失敗: $e")
            return fetchEspnNba(t)
        }
    }

    /** 在開賽日前後各一天的 ESPN 賽程裡找這場比賽 (ESPN 的日期分界和台灣/美東都不太一樣) */
    private fun findEspn(t: Tracked): JSONObject? {
        val d0 = Instant.ofEpochMilli(if (t.startMs > 0) t.startMs else System.currentTimeMillis()).atZone(ET).toLocalDate()
        val key = nbaKey(t.a) + nbaKey(t.h)
        for (d in listOf(d0, d0.minusDays(1), d0.plusDays(1))) {
            for (ev in espnEvents(d.format(DateTimeFormatter.BASIC_ISO_DATE))) {
                if (t.src == "espn" && ev.optString("id") == t.id) return ev
                if (t.src != "espn") {
                    val a = espnComp(ev, false)?.optJSONObject("team")?.optString("abbreviation") ?: ""
                    val h = espnComp(ev, true)?.optJSONObject("team")?.optString("abbreviation") ?: ""
                    if (nbaKey(a) + nbaKey(h) == key) return ev
                }
            }
        }
        return null
    }

    private fun fetchEspnNba(t: Tracked): JSONObject? {
        val ev = findEspn(t)
        if (ev == null) {
            AppLog.w("SCORE", "ESPN 找不到 " + t.a + "@" + t.h)
            return if (System.currentTimeMillis() < t.startMs + 30 * 60_000L) base(t, "P") else null
        }
        val st = espnState(ev)
        val o = base(t, st)
        if (st == "P") return o
        val h = espnComp(ev, true)
        val a = espnComp(ev, false)
        o.put("as", a?.optString("score")?.toIntOrNull() ?: 0).put("hs", h?.optString("score")?.toIntOrNull() ?: 0)
        val s = ev.optJSONObject("status")
        o.put("q", s?.optInt("period", 1) ?: 1)
        val clk = s?.optString("displayClock") ?: ""
        o.put("c", clk.substringBefore('.'))
        if (st == "L") {
            val ten = espnTenths(clk)
            clockFields(o, t.id, ten, ten == 0, s?.optInt("period", 1) ?: 1)
            if (ten == 0) o.put("brk", 1)
        }
        fun ls(c: JSONObject?): JSONArray {
            val r = JSONArray()
            val arr = c?.optJSONArray("linescores") ?: return r
            for (i in 0 until arr.length()) r.put(arr.getJSONObject(i).optDouble("value", 0.0).toInt())
            return r
        }
        o.put("aq", ls(a)).put("hq", ls(h))
        return o
    }

    // ================= CPBL (盡力而為) =================
    private val cpblShort = mapOf(
        "中信兄弟" to "兄弟", "統一7-ELEVEn獅" to "統一", "統一獅" to "統一", "樂天桃猿" to "樂天",
        "富邦悍將" to "富邦", "味全龍" to "味全", "台鋼雄鷹" to "台鋼"
    )

    private fun cpblName(s: String): String = cpblShort[s] ?: cpblShort.entries.firstOrNull { s.contains(it.value) }?.value ?: s.take(3)

    private var cpblToken: String? = null
    private var cpblTokenAt = 0L

    private fun cpblTokenGet(): String? {
        val now = System.currentTimeMillis()
        if (cpblToken != null && now - cpblTokenAt < 600_000L) return cpblToken
        val html = http("https://www.cpbl.com.tw/schedule") ?: return null
        val pats = listOf(
            Regex("RequestVerificationToken[\"']?\\s*[:=]\\s*[\"']([^\"']+)[\"']"),
            Regex("name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\""),
            Regex("value=\"([^\"]+)\"[^>]*name=\"__RequestVerificationToken\"")
        )
        for (p in pats) {
            val m = p.find(html)
            if (m != null) {
                cpblToken = m.groupValues[1]
                cpblTokenAt = now
                return cpblToken
            }
        }
        AppLog.w("CPBL", "在 cpbl.com.tw/schedule 找不到驗證權杖 (網站可能改版)")
        return null
    }

    private fun cpblPost(path: String, body: String): JSONObject? {
        val tk = cpblTokenGet() ?: return null
        val txt = http(
            "https://www.cpbl.com.tw$path", body,
            mapOf(
                "RequestVerificationToken" to tk, "X-Requested-With" to "XMLHttpRequest", "Referer" to "https://www.cpbl.com.tw/schedule",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
            )
        )
        if (txt == null) {
            cpblToken = null
            return null
        }
        return try {
            JSONObject(txt)
        } catch (e: Exception) {
            AppLog.w("CPBL", "回應不是 JSON: " + txt.take(200).replace('\n', ' '))
            null
        }
    }

    /** 欄位名稱在官網沒有公開文件, 這裡依序嘗試多個可能的名稱 */
    private fun JSONObject.firstStr(vararg keys: String): String {
        for (k in keys) {
            val v = opt(k)
            if (v != null && v != JSONObject.NULL && v.toString().isNotEmpty()) return v.toString()
        }
        return ""
    }

    private fun cpblGames(date: LocalDate): List<JSONObject> {
        val body = "calendar=" + URLEncoder.encode(date.format(DateTimeFormatter.ofPattern("yyyy/MM/dd")), "UTF-8") + "&location=&kindCode=A"
        val o = cpblPost("/schedule/getgamedatas", body) ?: return emptyList()
        val gd = o.opt("GameDatas")
        val arr = try {
            when (gd) {
                is JSONArray -> gd
                is String -> JSONArray(gd)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
        if (arr == null) {
            AppLog.w("CPBL", "賽程格式不符: " + o.toString().take(200))
            return emptyList()
        }
        val out = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) out.add(arr.getJSONObject(i))
        val d = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        return out.filter { g -> g.firstStr("GameDate", "PreExeDate").startsWith(d) || true }
    }

    private fun cpblStart(g: JSONObject): Long = isoMs(g.firstStr("GameDateTimeS", "GameDateTime", "GameDate"))

    private fun cpblStatus(g: JSONObject, start: Long): String {
        val now = System.currentTimeMillis()
        if (now < start) return "P"
        val hasScore = g.firstStr("VisitingScore").isNotEmpty() && g.firstStr("HomeScore").isNotEmpty()
        return if (now - start > 4 * 3600_000L && hasScore) "F" else "L"
    }

    private fun listCpbl(date: LocalDate): List<GameInfo> {
        return cpblGames(date).map { g ->
            val start = cpblStart(g)
            val st = cpblStatus(g, start)
            GameInfo(
                "CPBL", "cpbl", g.firstStr("GameSno") + "@" + date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                cpblName(g.firstStr("VisitingTeamName")), cpblName(g.firstStr("HomeTeamName")), start, st,
                g.firstStr("VisitingScore").toIntOrNull(), g.firstStr("HomeScore").toIntOrNull()
            )
        }.sortedBy { it.startMs }
    }

    private fun fetchCpbl(t: Tracked): JSONObject? {
        val sno = t.id.substringBefore('@')
        val date = try {
            LocalDate.parse(t.id.substringAfter('@'))
        } catch (_: Exception) {
            return null
        }
        val g = cpblGames(date).firstOrNull { it.firstStr("GameSno") == sno } ?: return null
        val st = cpblStatus(g, cpblStart(g).let { if (it > 0) it else t.startMs })
        val o = base(t, st)
        if (st == "P") return o
        o.put("as", g.firstStr("VisitingScore").toIntOrNull() ?: 0).put("hs", g.firstStr("HomeScore").toIntOrNull() ?: 0)
        if (st == "L") {
            val live = cpblPost("/box/getlive", "GameSno=$sno&Year=${date.year}&KindCode=A")
            if (live != null) cpblApplyLive(live, o)
        }
        return o
    }

    /** 即時資訊: 盡力解析 (找不到的欄位就略過, 並把原始內容的開頭寫進記錄方便對照) */
    private fun cpblApplyLive(live: JSONObject, o: JSONObject) {
        var cur: JSONObject? = null
        for (k in listOf("CurtGameDetailJson", "CurrentGameDetailJson", "GameDetailJson")) {
            val v = live.opt(k) ?: continue
            cur = try {
                when (v) {
                    is JSONObject -> v
                    is JSONArray -> v.optJSONObject(0)
                    is String -> if (v.trimStart().startsWith("[")) JSONArray(v).optJSONObject(0) else JSONObject(v)
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
            if (cur != null) break
        }
        if (cur == null) {
            AppLog.w("CPBL", "找不到即時欄位, 回應開頭: " + live.toString().take(300))
            return
        }
        val cu: JSONObject = cur
        val inn = cu.firstStr("InningSeq", "Inning", "InningNo").toIntOrNull()
        if (inn != null) {
            o.put("in", inn)
            val side = cu.firstStr("VisitingHomeType", "HomeVisitingType")
            o.put("hf", if (side == "2") 1 else 0)
            o.put("tx", inn.toString() + "局")
        }
        cu.firstStr("BallCnt", "Ball").toIntOrNull()?.let { o.put("b", it) }
        cu.firstStr("StrikeCnt", "Strike").toIntOrNull()?.let { o.put("s", it) }
        cu.firstStr("OutCnt", "Out").toIntOrNull()?.let { o.put("o", it) }
        fun on(vararg k: String): Boolean {
            val v = cu.firstStr(*k)
            return v.isNotEmpty() && v != "0" && v.lowercase() != "false" && v != "null"
        }
        var bs = 0
        if (on("FirstBase", "FirstBaseman")) bs = bs or 1
        if (on("SecondBase")) bs = bs or 2
        if (on("ThirdBase")) bs = bs or 4
        o.put("bs", bs)
        cu.firstStr("HitterName", "BatterName", "Hitter").takeIf { it.isNotEmpty() }?.let { o.put("bt", it) }
        cu.firstStr("PitcherName", "Pitcher").takeIf { it.isNotEmpty() }?.let { o.put("pt", it) }
    }
}
