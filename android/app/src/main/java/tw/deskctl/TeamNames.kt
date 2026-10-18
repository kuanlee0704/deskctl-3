package tw.deskctl

import java.text.Normalizer

/**
 * 足球等球隊的「英文名 → 中文名」對照 (ESPN 給的簡稱不固定, 用全名比對最準)。
 * 比對前會去掉重音與 FC / AC / CF 等字樣、全轉小寫, 所以 "Atlético Madrid" 與 "Atletico de Madrid" 都對得上。
 * 查不到的球隊手機畫面顯示英文, 也可在「比分」分頁的追蹤清單按「改名」自己填。
 */
object TeamNames {
    private val DROP = setOf("fc", "afc", "cf", "sc", "ac", "as", "ssc", "us", "cd", "ud", "rc", "rcd", "fk", "sk", "bk", "if", "sv", "vfl", "vfb", "tsg", "fsv", "ssd", "ca", "club", "de", "the", "1")

    fun norm(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return n.lowercase().replace("&", " and ").replace(Regex("[^a-z0-9 ]"), " ")
            .split(" ").filter { it.isNotEmpty() && it !in DROP }.joinToString(" ")
    }

    private const val RAW = """
Arsenal|兵工廠
Aston Villa|阿斯頓維拉
Bournemouth|般尼茅夫
Brentford|布倫特福德
Brighton and Hove Albion|布萊頓
Brighton|布萊頓
Burnley|般尼
Chelsea|切爾西
Crystal Palace|水晶宮
Everton|艾佛頓
Fulham|富勒姆
Leeds United|里茲聯
Liverpool|利物浦
Manchester City|曼城
Manchester United|曼聯
Newcastle United|紐卡索
Nottingham Forest|諾丁漢森林
Sunderland|桑德蘭
Tottenham Hotspur|熱刺
West Ham United|西漢姆聯
Wolverhampton Wanderers|狼隊
Leicester City|萊斯特城
Ipswich Town|伊普斯維奇
Southampton|南安普敦
Sheffield United|謝菲聯
Sheffield Wednesday|謝菲爾德星期三
Norwich City|諾域治
West Bromwich Albion|西布朗
Middlesbrough|米杜士堡
Watford|屈福特
Coventry City|高雲地利
Blackburn Rovers|般尼流浪者
Stoke City|斯托克城
Swansea City|史雲斯
Hull City|赫爾城
Preston North End|普雷斯頓
Millwall|米禾爾
Queens Park Rangers|女王公園巡游者
Bristol City|布里斯托城
Derby County|打比郡
Birmingham City|伯明罕
Portsmouth|樸茨茅斯
Oxford United|牛津聯
Plymouth Argyle|普利茅斯
Cardiff City|卡迪夫城
Luton Town|盧頓
Wrexham|雷克瀚
Charlton Athletic|查爾頓
Real Madrid|皇家馬德里
Barcelona|巴塞隆納
Atletico Madrid|馬德里競技
Sevilla|塞維亞
Real Sociedad|皇家社會
Real Betis|皇家貝蒂斯
Villarreal|比利亞雷爾
Athletic Bilbao|畢爾包競技
Athletic|畢爾包競技
Valencia|瓦倫西亞
Girona|赫羅納
Celta Vigo|塞爾塔
Osasuna|奧薩蘇納
Getafe|赫塔費
Mallorca|馬約卡
Rayo Vallecano|巴列卡諾
Alaves|阿拉維斯
Deportivo Alaves|阿拉維斯
Espanyol|西班牙人
Las Palmas|拉斯帕爾馬斯
Elche|埃爾切
Levante|萊萬特
Real Oviedo|皇家奧維耶多
Leganes|萊加內斯
Real Valladolid|巴拉多利德
Bayern Munich|拜仁慕尼黑
Borussia Dortmund|多特蒙德
RB Leipzig|萊比錫紅牛
Bayer Leverkusen|勒沃庫森
Bayer 04 Leverkusen|勒沃庫森
Eintracht Frankfurt|法蘭克福
VfB Stuttgart|斯圖加特
Stuttgart|斯圖加特
Borussia Monchengladbach|門興格拉德巴赫
Wolfsburg|沃爾夫斯堡
Werder Bremen|不來梅
Freiburg|弗賴堡
Hoffenheim|霍芬海姆
1899 Hoffenheim|霍芬海姆
Mainz|美因茨
Mainz 05|美因茨
Augsburg|奧格斯堡
Union Berlin|柏林聯合
Heidenheim|海登海姆
St Pauli|聖保利
Hamburg|漢堡
Hamburger SV|漢堡
Koln|科隆
Cologne|科隆
Holstein Kiel|基爾
Bochum|波鴻
Juventus|尤文圖斯
Inter Milan|國際米蘭
Internazionale|國際米蘭
Inter|國際米蘭
AC Milan|AC米蘭
Napoli|拿坡里
Roma|羅馬
Lazio|拉齊奧
Atalanta|亞特蘭大
Fiorentina|佛羅倫斯
ACF Fiorentina|佛羅倫斯
Bologna|波隆那
Torino|都靈
Genoa|熱那亞
Udinese|烏甸尼斯
Cagliari|卡利亞里
Hellas Verona|維羅納
Verona|維羅納
Lecce|萊切
Parma|帕爾馬
Como|科莫
Sassuolo|薩索羅
Cremonese|克雷莫納
Pisa|比薩
Empoli|恩波利
Monza|蒙薩
Venezia|威尼斯
Paris Saint Germain|巴黎聖日耳曼
Marseille|馬賽
Olympique Marseille|馬賽
Olympique de Marseille|馬賽
Lyon|里昂
Olympique Lyonnais|里昂
Olympique Lyon|里昂
Monaco|摩納哥
Lille|里爾
Nice|尼斯
OGC Nice|尼斯
Lens|朗斯
Rennes|雷恩
Stade Rennais|雷恩
Strasbourg|史特拉斯堡
Nantes|南特
Toulouse|圖盧茲
Brest|布雷斯特
Stade Brestois|布雷斯特
Reims|蘭斯
Montpellier|蒙彼利埃
Auxerre|歐塞爾
Angers|昂熱
Le Havre|勒阿弗爾
Lorient|洛里昂
Metz|梅斯
Paris|巴黎FC
Benfica|本菲卡
SL Benfica|本菲卡
Porto|波圖
Sporting CP|里斯本競技
Sporting Lisbon|里斯本競技
Braga|布拉加
Sporting Braga|布拉加
Ajax|阿賈克斯
PSV Eindhoven|PSV燕豪芬
PSV|PSV燕豪芬
Feyenoord|飛燕諾
AZ Alkmaar|阿爾克馬爾
Celtic|塞爾提克
Rangers|格拉斯哥流浪者
Galatasaray|加拉塔薩雷
Fenerbahce|費內巴切
Besiktas|貝西克塔斯
Club Brugge|布魯日
Anderlecht|安德萊赫特
Union Saint Gilloise|聖吉洛斯聯
Shakhtar Donetsk|頓內次克礦工
Red Bull Salzburg|薩爾茨堡紅牛
Salzburg|薩爾茨堡紅牛
Copenhagen|哥本哈根
Olympiacos|奧林匹亞科斯
Young Boys|年輕人
Dinamo Zagreb|薩格勒布戴拿摩
Red Star Belgrade|貝爾格勒紅星
Slavia Prague|布拉格斯拉維亞
Sparta Prague|布拉格斯巴達
Bodo Glimt|博多格林特
Qarabag|卡拉巴赫
Kairat Almaty|凱拉特
Pafos|帕福斯
Viktoria Plzen|比爾森勝利
Inter Miami|邁阿密國際
LA Galaxy|洛杉磯銀河
Los Angeles Galaxy|洛杉磯銀河
LAFC|洛杉磯FC
Los Angeles|洛杉磯FC
Seattle Sounders|西雅圖海灣人
Portland Timbers|波特蘭伐木工
New York City|紐約城
New York Red Bulls|紐約紅牛
Atlanta United|亞特蘭大聯
Columbus Crew|哥倫布機員
Philadelphia Union|費城聯合
Toronto|多倫多
Montreal|蒙特婁
Vancouver Whitecaps|溫哥華白帽
Austin|奧斯汀
Dallas|達拉斯
Houston Dynamo|休士頓迪納摩
Sporting Kansas City|堪薩斯城體育會
Real Salt Lake|皇家鹽湖城
Colorado Rapids|科羅拉多急流
Minnesota United|明尼蘇達聯
Nashville|納許維爾
Orlando City|奧蘭多城
Charlotte|夏洛特
DC United|華盛頓聯
New England Revolution|新英格蘭革命
Chicago Fire|芝加哥火焰
Cincinnati|辛辛那提
San Jose Earthquakes|聖荷西地震
St Louis City|聖路易城
San Diego|聖地牙哥
Vissel Kobe|神戶勝利船
Yokohama F Marinos|橫濱水手
Kawasaki Frontale|川崎前鋒
Urawa Red Diamonds|浦和紅鑽
Kashima Antlers|鹿島鹿角
Gamba Osaka|大阪鋼巴
Cerezo Osaka|大阪櫻花
Sanfrecce Hiroshima|廣島三箭
Tokyo|東京FC
Nagoya Grampus|名古屋鯨魚
Kashiwa Reysol|柏太陽神
Machida Zelvia|町田澤維亞
Avispa Fukuoka|福岡黃蜂
Chinese Taipei|中華台北
Taiwan|中華台北
Japan|日本
South Korea|南韓
Korea Republic|南韓
China|中國
China PR|中國
Hong Kong|香港
North Korea|北韓
USA|美國
United States|美國
Mexico|墨西哥
Canada|加拿大
Brazil|巴西
Argentina|阿根廷
Uruguay|烏拉圭
Colombia|哥倫比亞
Chile|智利
Peru|秘魯
Ecuador|厄瓜多
Paraguay|巴拉圭
Venezuela|委內瑞拉
Bolivia|玻利維亞
England|英格蘭
France|法國
Germany|德國
Spain|西班牙
Italy|義大利
Portugal|葡萄牙
Netherlands|荷蘭
Belgium|比利時
Croatia|克羅埃西亞
Switzerland|瑞士
Denmark|丹麥
Sweden|瑞典
Norway|挪威
Poland|波蘭
Austria|奧地利
Czechia|捷克
Czech Republic|捷克
Serbia|塞爾維亞
Scotland|蘇格蘭
Wales|威爾斯
Ireland|愛爾蘭
Republic of Ireland|愛爾蘭
Northern Ireland|北愛爾蘭
Turkey|土耳其
Turkiye|土耳其
Ukraine|烏克蘭
Greece|希臘
Hungary|匈牙利
Romania|羅馬尼亞
Slovakia|斯洛伐克
Slovenia|斯洛維尼亞
Albania|阿爾巴尼亞
Finland|芬蘭
Iceland|冰島
Russia|俄羅斯
Morocco|摩洛哥
Senegal|塞內加爾
Nigeria|奈及利亞
Egypt|埃及
Ghana|迦納
Cameroon|喀麥隆
Algeria|阿爾及利亞
Tunisia|突尼西亞
Ivory Coast|象牙海岸
Cote d Ivoire|象牙海岸
South Africa|南非
Mali|馬利
Australia|澳洲
Iran|伊朗
Saudi Arabia|沙烏地阿拉伯
Qatar|卡達
Iraq|伊拉克
Uzbekistan|烏茲別克
Jordan|約旦
United Arab Emirates|阿拉伯聯合大公國
New Zealand|紐西蘭
Costa Rica|哥斯大黎加
Panama|巴拿馬
Jamaica|牙買加
Honduras|宏都拉斯
Haiti|海地
Curacao|古拉索
Thailand|泰國
Vietnam|越南
Indonesia|印尼
India|印度
"""

    private val map: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for (line in RAW.lines()) {
            val p = line.trim().split("|")
            if (p.size == 2 && p[0].isNotEmpty()) m[norm(p[0])] = p[1]
        }
        m
    }

    fun zh(english: String): String? = if (english.isBlank()) null else map[norm(english)]
}
