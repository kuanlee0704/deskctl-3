package tw.deskctl

class FeatureEntry(val id: String, val title: String, val make: (FeatureHost) -> Feature)

/**
 * 功能登記表。要新增功能: 寫一個 Feature 子類別, 然後在 registry 加一行 FeatureEntry。
 */
object Features {
    val registry: List<FeatureEntry> = listOf(
        FeatureEntry("time", "時間同步") { h -> TimeFeature(h) },
        FeatureEntry("battery", "手機電量") { h -> BatteryFeature(h) },
        FeatureEntry("media", "音樂控制") { h -> MediaFeature(h) },
        FeatureEntry("shortcut", "捷徑") { h -> ShortcutFeature(h) },
        FeatureEntry("pad", "手機控制") { h -> PadFeature(h) },
        FeatureEntry("score", "比分板") { h -> ScoreFeature(h) },
        FeatureEntry("message", "手機訊息") { h -> MessageFeature(h) },
        FeatureEntry("clean", "手機清理") { h -> CleanFeature(h) },
        FeatureEntry("system", "手機狀態") { h -> SystemFeature(h) },
        FeatureEntry("device", "控制器狀態與目錄") { h -> DeviceFeature(h) },
        // 新增功能就加在這裡, 例如: FeatureEntry("weather", "天氣") { h -> WeatherFeature(h) },
    )
}
