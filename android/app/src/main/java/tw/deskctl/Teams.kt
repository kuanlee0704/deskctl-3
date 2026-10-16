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

    fun cn(lg: String, code: String): String = when (lg) {
        "MLB" -> mlb[code.uppercase()] ?: code
        "NBA" -> nba[code.uppercase()] ?: code
        "SOC" -> soc[code.uppercase()] ?: code
        else -> code
    }

    /** 給手機畫面: 「洋基 NYY」(CPBL 本來就是中文) */
    fun label(lg: String, code: String): String {
        val c = cn(lg, code)
        return if (c == code) code else "$c $code"
    }
}
