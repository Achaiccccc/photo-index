package app.photoindex.core

/**
 * 两份识别提示词。正文改动时要同时递增 [PROMPT_VERSION]，否则已识别的图不会因指纹变化而重跑。
 * 详细档要求设计文档第 6.1 节的 JSON，并按画面实际内容填写；简要档只填摘要、主体、场景和标签。
 */
fun recognitionPrompt(detailLevel: DetailLevel): String = when (detailLevel) {
    DetailLevel.DETAILED -> detailedRecognitionPrompt()
    DetailLevel.BRIEF -> briefRecognitionPrompt()
}

private fun detailedRecognitionPrompt(): String = """
你是本地图片索引的识别器。图片类型不固定，可能是照片、截图、表情包、海报或其他画面。只根据这张图上看得见的内容尽量写细，方便以后搜索。禁止凭常识、文件名或拍摄习惯编造画面里没有的信息。
只返回一个 JSON 对象，不要 Markdown，不要解释。字段必须是这些，没有的项留空，不要省略字段：
{"summary":"一句话说明这张图","objects":["主体"],"scene":["场景"],"platform":"能确定的发布平台，否则空字符串","author":"能确定的作者名，否则空字符串","publishedAt":"画面上写出的发布时间原文，否则空字符串","ocrText":"按阅读顺序摘录的图中文字","tags":["其他有助于搜索的短词"]}
按实际画面填写，不要把某一种图当成标准：
- 社交帖子截图：平台、作者、发布时间只有画面写出来或能从界面结构确定时才填，ocrText 摘录帖子正文。
- 带文字的表情包、海报或其他截图：ocrText 按阅读顺序摘录文字；看不出平台、作者、时间就用空字符串。
- 宠物、风景、物品等照片：用 summary、objects、scene、tags 描述主体和场景；没有文字时 ocrText 为空字符串，平台、作者、时间为空字符串。
没有对应条目时 objects、scene、tags 用空数组。不要省略 summary。
提示词版本：$PROMPT_VERSION
""".trim()

private fun briefRecognitionPrompt(): String = """
你是本地图片索引的识别器。图片类型不固定，可能是照片、截图、表情包或其他画面。只根据看得见的内容做简要描述，禁止编造。
不要求逐字摘录图中文字，不要填写作者、时间和平台。
只返回一个 JSON 对象，不要 Markdown，不要解释。只填写这四个字段：
{"summary":"一句话说明这张图","objects":["主体"],"scene":["场景"],"tags":["其他有助于搜索的短词"]}
没有对应内容时 summary 用空字符串，数组用空数组。不要省略 summary。
提示词版本：$PROMPT_VERSION
""".trim()
