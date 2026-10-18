package tw.deskctl

/** 球隊代號 → 中文隊名 (查不到就回傳原代號, 例如季前賽的外國球隊) */
object Teams {
    private val mlb = mapOf(
        "ARI" to "響尾蛇", "AZ" to "響尾蛇", "ATL" to "勇士", "BAL" to "金鶯", "BOS" to "紅襪", "CHC" to "小熊",
        "CWS" to "白襪", "CHW" to "白襪", "CIN" to "紅人", "CLE" to "守護者", "COL" to "洛磯", "DET" to "老虎",
        "HOU" to "太空人", "KC" to "皇家", "KCR" to "皇家", "LAA" to "天使", "LAD" to "道奇", "MIA" to "馬林魚",
        "MIL" to "釀酒人", "MIN" to "雙城", "NYM" to "大都會", "NYY" to "洋基", "ATH" to "運動家", "OAK" to "運動家",
        "PHI" to "費城人", "PIT" to "海盜", "SD" to "教士", "SDP" to "教士", "SF" to "巨人", "SFG" to "巨人",
        "SEA" to "水手", "STL" to "紅雀", "TB" to "光芒", "TBR" to "光芒", "TEX" to "遊騎兵", "TOR" to "藍鳥",
        "WSH" to "國民", "WAS" to "國民"
    )
    private val nba = mapOf(
        "ATL" to "老鷹", "BOS" to "塞爾提克", "BKN" to "籃網", "BRK" to "籃網", "CHA" to "黃蜂", "CHO" to "黃蜂",
        "CHI" to "公牛", "CLE" to "騎士", "DAL" to "獨行俠", "DEN" to "金塊", "DET" to "活塞", "GSW" to "勇士",
        "GS" to "勇士", "HOU" to "火箭", "IND" to "溜馬", "LAC" to "快艇", "LAL" to "湖人", "MEM" to "灰熊",
        "MIA" to "熱火", "MIL" to "公鹿", "MIN" to "灰狼", "NOP" to "鵜鶘", "NO" to "鵜鶘", "NYK" to "尼克",
        "NY" to "尼克", "OKC" to "雷霆", "ORL" to "魔術", "PHI" to "七六人", "PHX" to "太陽", "PHO" to "太陽",
        "POR" to "拓荒者", "SAC" to "國王", "SAS" to "馬刺", "SA" to "馬刺", "TOR" to "暴龍", "UTA" to "爵士",
        "UTAH" to "爵士", "WAS" to "巫師", "WSH" to "巫師"
    )

    private val soc = mapOf(
        "ARS" to "兵工廠", "AVL" to "維拉", "BOU" to "般尼茅夫", "BRE" to "布倫特福德", "BHA" to "布萊頓", "CHE" to "切爾西",
        "CRY" to "水晶宮", "EVE" to "艾佛頓", "FUL" to "富勒姆", "LIV" to "利物浦", "MCI" to "曼城", "MUN" to "曼聯",
        "NEW" to "紐卡索", "NFO" to "諾丁漢森林", "TOT" to "熱刺", "WHU" to "西漢姆", "WOL" to "狼隊", "LEE" to "里茲聯",
        "BUR" to "般尼", "SUN" to "桑德蘭", "RMA" to "皇家馬德里", "BAR" to "巴塞隆納", "ATM" to "馬競", "SEV" to "塞維亞",
        "BAY" to "拜仁", "DOR" to "多特蒙德", "LEV" to "勒沃庫森", "LEI" to "萊比錫", "JUV" to "尤文圖斯", "INT" to "國際米蘭",
        "MIL" to "AC米蘭", "NAP" to "拿坡里", "ROM" to "羅馬", "PSG" to "巴黎聖日耳曼", "MAR" to "馬賽", "LYO" to "里昂",
        "BEN" to "本菲卡", "SCP" to "里斯本競技", "AJA" to "阿賈克斯", "MIA" to "邁阿密國際", "LAFC" to "洛杉磯FC",
        "LAG" to "洛杉磯銀河", "TPE" to "中華台北", "JPN" to "日本", "KOR" to "南韓", "USA" to "美國", "ENG" to "英格蘭",
        "FRA" to "法國", "GER" to "德國", "ESP" to "西班牙", "ITA" to "義大利", "BRA" to "巴西", "ARG" to "阿根廷"
    )

    /** 使用者自己填的隊名 ("聯盟:代號" → 名稱) 與板子隊名語言; 由 loadCustom 載入 */
    @Volatile private var custom: Map<String, String> = emptyMap()
    @Volatile var english: Boolean = false
        private set

    fun loadCustom(c: android.content.Context) {
        english = Prefs.teamEnglish(c)
        val m = HashMap<String, String>()
        try {
            val o = org.json.JSONObject(Prefs.teamNames(c))
            for (k in o.keys()) m[k] = o.optString(k)
        } catch (_: Exception) {
        }
        custom = m
    }

    fun setCustom(c: android.content.Context, lg: String, code: String, name: String) {
        val o = org.json.JSONObject()
        val m = HashMap(custom)
        if (name.isBlank()) m.remove("$lg:$code") else m["$lg:$code"] = name.trim()
        for ((k, v) in m) o.put(k, v)
        Prefs.setTeamNames(c, o.toString())
        custom = m
    }

    fun setEnglish(c: android.content.Context, v: Boolean) {
        Prefs.setTeamEnglish(c, v)
        english = v
    }

    fun custom(lg: String, code: String): String? = custom["$lg:$code"]

    /** 中文名; 查不到回傳 null。順序: 自訂 → 代號對照 → (足球) 英文全名對照 */
    fun zh(lg: String, code: String, en: String = ""): String? {
        custom["$lg:$code"]?.let { return it }
        val byCode = when (lg) {
            "MLB" -> mlb[code.uppercase()]
            "NBA" -> nba[code.uppercase()]
            "SOC" -> soc[code.uppercase()]
            else -> null
        }
        if (lg == "SOC") return TeamNames.zh(en) ?: byCode
        if (lg == "CPBL") return code
        return byCode ?: TeamNames.zh(en)
    }

    fun cn(lg: String, code: String, en: String = ""): String = zh(lg, code, en) ?: code

    /** 給控制器下方的隊名那一行: 依「隊名語言」; 沒有中文就用英文, 再沒有就用代號 */
    fun board(lg: String, code: String, en: String = ""): String {
        val z = zh(lg, code, en)
        return if (english) en.ifEmpty { z ?: code } else z ?: en.ifEmpty { code }
    }

    /** 給手機畫面: 「曼城 Manchester City (MCI)」; 沒中文就只有英文 */
    fun label(lg: String, code: String, en: String = ""): String {
        val z = zh(lg, code, en)
        val parts = ArrayList<String>()
        if (z != null) parts.add(z)
        if (en.isNotEmpty() && en != z) parts.add(en)
        if (parts.isEmpty()) return code
        if (code != z && code != en && lg != "CPBL") parts.add("($code)")
        return parts.joinToString(" ")
    }
}
