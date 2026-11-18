package app.mocast.data

/**
 * 推荐 / 示例订阅（0.4.0 P0 收尾，冷启动）。
 *
 * 只在首页无订阅时展示，不自动订阅任何节目，点一个订阅一个。
 * 每个 RSS 都在 2026-11-18 核实过：除 BBC 一条外均直接拉 feed 本体、标题与
 * 最新一集对得上；BBC 一条因直连被策略拦截，改由两个独立目录（podengine /
 * podstatus）交叉核对同一 feed 地址与 2026-11-12 最新一集。核实失败的不要
 * 写进来，宁可少不要编。
 * Hard Fork 因多个来源给出的 Simplecast feed ID 不一致（6HKOhNgS /
 * dHoohVNH / l2i9YnTd），无法确定哪一个是当前有效的，已排除，未收录。
 */
data class RecommendedShow(
    val name: String,
    val blurb: String,
    /** "英语学习" 或 "科技访谈" */
    val category: String,
    val feedUrl: String,
    /** RSS 来源：写清是哪个节目，避免以后分不清链接从哪来。 */
    val source: String
)

object RecommendedShows {

    val all: List<RecommendedShow> = listOf(
        RecommendedShow(
            name = "6 Minute English",
            blurb = "BBC 每周 6 分钟，聊一个话题、学几个地道表达，入门练听力最顺的一档。",
            category = "英语学习",
            feedUrl = "https://podcasts.files.bbci.co.uk/p02pc9tn.rss",
            source = "BBC Learning English 官方 feed"
        ),
        RecommendedShow(
            name = "English Learning for Curious Minds",
            blurb = "中高级学习者：用清楚的英音讲历史、心理、商业里的怪故事，带字幕和重点词汇。",
            category = "英语学习",
            feedUrl = "https://feeds.transistor.fm/leonardo-english-english-language-learning-for-curious-minds",
            source = "Leonardo English（Transistor 托管）"
        ),
        RecommendedShow(
            name = "All Ears English",
            blurb = "两个美国老师聊日常口语、俚语和文化梗，每天一集，练“听懂真人说话”。",
            category = "英语学习",
            feedUrl = "https://feeds.megaphone.fm/allearsenglish",
            source = "All Ears English（Megaphone 托管）"
        ),
        RecommendedShow(
            name = "TED Talks Daily",
            blurb = "每天一篇 TED 演讲音频，话题从 AI 到动物学都有，单集短、适合通勤听。",
            category = "科技访谈",
            feedUrl = "https://feeds.acast.com/public/shows/67587e77c705e441797aff96",
            source = "TED（Acast 托管）"
        ),
        RecommendedShow(
            name = "Lex Fridman Podcast",
            blurb = "长访谈：AI、数学、历史、哲学，一期常 3 小时以上，字幕翻译最能发挥的一档。",
            category = "科技访谈",
            feedUrl = "https://lexfridman.com/feed/podcast/",
            source = "Lex Fridman 官网"
        ),
        RecommendedShow(
            name = "No Priors",
            blurb = "Elad Gil 和 Sarah Guo 对谈 AI 研究者与创始人，每周一期，聊 AGI 离多远、哪些市场会被颠覆。",
            category = "科技访谈",
            feedUrl = "https://feeds.megaphone.fm/nopriors",
            source = "Conviction（Megaphone 托管）"
        ),
        RecommendedShow(
            name = "Acquired",
            blurb = "讲大公司的发家史：从 Home Depot 到 Seinfeld，一集讲透一家公司的打法。",
            category = "科技访谈",
            feedUrl = "https://feeds.transistor.fm/acquired",
            source = "Acquired（Transistor 托管）"
        )
    )
}
