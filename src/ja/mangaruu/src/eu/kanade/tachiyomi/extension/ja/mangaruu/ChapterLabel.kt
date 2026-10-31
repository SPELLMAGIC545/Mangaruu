package eu.kanade.tachiyomi.extension.ja.mangaruu

internal class ChapterLabel(val name: String, val number: Float) {
    companion object {
        private val NUMBER = Regex("""(\d+(?:\.\d+)?)\s*話""")
        private val URL_NUMBER = Regex("""-ch-(\d+(?:[.-]\d+)?)(?:-id-|$)""", RegexOption.IGNORE_CASE)

        fun parse(label: String, path: String): ChapterLabel {
            val raw = NUMBER.find(label)?.groupValues?.get(1)
                ?: URL_NUMBER.find(path)?.groupValues?.get(1)?.replace('-', '.')
            val number = raw?.toFloatOrNull() ?: return ChapterLabel(label, -1f)
            return ChapterLabel("${number.toString().removeSuffix(".0")}話", number)
        }
    }
}
