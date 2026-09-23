package com.naomi.hoshimusic.data

/**
 * KORPUS UJI 40 LAGU (10 per bahasa: EN / JP / CN / KR)
 *
 * Tujuan: "melatih" adaptabilitas LrcParser & HoshiRomanizer terhadap
 * dialek LRC yang ditemukan di dunia nyata (standard, enhanced/A2 karaoke,
 * koma desimal Eropa, ms 3-digit, offset, BOM/CRLF, Walaoke gender markers,
 * pipe romaji, multi-timestamp, unsorted, mid-word split, spasi ideografik, dll).
 *
 * SEMUA baris lirik di bawah adalah kalimat generik yang dibuat sendiri
 * (bukan lirik berhak cipta) — hanya judul/artist yang merupakan fakta nyata.
 */
internal enum class CorpusLang { EN, JA, ZH, KO }

internal data class CorpusSong(
    val lang: CorpusLang,
    val artist: String,
    val title: String,
    val dialect: String,
    val lrc: String,
)

// ---- Kalimat generik orisinal per bahasa ---------------------------------
private val EN_LINES = listOf(
    "Gathering the starlight tonight",
    "Echoes drifting through the open night",
    "We are running toward the golden dawn",
    "Hold this moment make it stay",
)

private val JA_LINES = listOf(
    "夜空の光を集めて",
    "こぼれた星を拾うよ",
    "夜明けの風に乗せて",
    "きみの声が響く",
)

// Simplified
private val ZH_LINES = listOf(
    "星光落在窗台上",
    "夜风轻轻唱着歌",
    "我们奔向黎明的方向",
    "把这一刻留在心上",
)

// Traditional (memuat penanda 這/聽/裡 agar deteksi ZH tetap kena)
private val ZH_TRAD_LINES = listOf(
    "這星光落在窗前",
    "聽夜風輕輕唱著歌",
    "我們奔向黎明的方向",
)

private val KO_LINES = listOf(
    "별빛이 창가에 내려앉아",
    "이 밤을 노래하리라",
    "우리는 아침을 향해 달려",
    "그 순간을 붙잡아 줘",
)

// ---- Builder dialek -------------------------------------------------------
private fun meta(ti: String, ar: String, extra: String = ""): String =
    "[ti:$ti]\n[ar:$ar]\n[al:Corpus Album]\n[by:HoshiCorpus]\n$extra"

private fun stdBlock(lines: List<String>, startMs: Int, stepMs: Int): String =
    lines.mapIndexed { i, l ->
        val t = startMs + i * stepMs
        val m = t / 60000
        val s = (t % 60000) / 1000.0
        "[%1\$02d:%2\$05.2f]%3\$s".format(m, s, l)
    }.joinToString("\n")

private fun enhCjkLine(ts: String, text: String): String {
    val chars = text.toList()
    val base = ts.split(":").let { it[0].toInt() * 60000 + (it[1].toDouble() * 1000).toInt() }
    return chars.indices.joinToString("") { i ->
        val t = base + i * 250
        val m = t / 60000
        val s = (t % 60000) / 1000.0
        "<%1\$02d:%2\$05.2f>%3\$s".format(m, s, chars[i])
    }.let { body -> "[$ts]$body" }
}

private fun enhLatinLine(ts: String, words: List<String>): String {
    val base = ts.split(":").let { it[0].toInt() * 60000 + (it[1].toDouble() * 1000).toInt() }
    return words.indices.joinToString(" ") { i ->
        val t = base + i * 300
        val m = t / 60000
        val s = (t % 60000) / 1000.0
        "<%1\$02d:%2\$05.2f>%3\$s".format(m, s, words[i])
    }.let { body -> "[$ts]$body" }
}

// ---- Korpus 40 lagu ------------------------------------------------------
internal val corpusSongs: List<CorpusSong> = buildList {

    // ================= ENGLISH (10) =================
    add(CorpusSong(CorpusLang.EN, "Taylor Swift", "Fortnight", "standard+ID",
        meta("Fortnight", "Taylor Swift") + "\n" + stdBlock(EN_LINES, 12000, 4000)))
    add(CorpusSong(CorpusLang.EN, "Billie Eilish", "Birds of a Feather", "enhanced-words",
        meta("Birds of a Feather", "Billie Eilish") + "\n" +
            enhLatinLine("00:14.00", EN_LINES[0].split(" ")) + "\n" +
            enhLatinLine("00:18.00", EN_LINES[1].split(" "))))
    add(CorpusSong(CorpusLang.EN, "Billie Eilish", "WILDFLOWER", "comma-decimal",
        meta("WILDFLOWER", "Billie Eilish") + "\n" +
            "[00:12,50]${EN_LINES[0]}\n[00:16,00]${EN_LINES[1]}"))
    add(CorpusSong(CorpusLang.EN, "Ed Sheeran", "Under The Tree", "ms3-digit",
        meta("Under The Tree", "Ed Sheeran") + "\n[00:12.345]${EN_LINES[0]}\n[00:16.789]${EN_LINES[1]}"))
    add(CorpusSong(CorpusLang.EN, "Taylor Swift", "I Can Do It With a Broken Heart", "single-digit-min",
        meta("ICDIWABH", "Taylor Swift") + "\n[1:02.5]${EN_LINES[2]}\n[1:06.0]${EN_LINES[3]}"))
    add(CorpusSong(CorpusLang.EN, "Taylor Swift", "Cruel Summer", "multi-stamp",
        meta("Cruel Summer", "Taylor Swift") + "\n[00:10.00][00:40.00]${EN_LINES[3]}\n[00:14.00]${EN_LINES[0]}"))
    add(CorpusSong(CorpusLang.EN, "Billie Eilish", "LUNCH", "offset-positive",
        meta("LUNCH", "Billie Eilish", "[offset:+500]") + "\n[00:12.00]${EN_LINES[1]}"))
    add(CorpusSong(CorpusLang.EN, "Billie Eilish", "CHIHIRO", "bom-crlf",
        "\uFEFF" + meta("CHIHIRO", "Billie Eilish").replace("\n", "\r\n") + "\r\n[00:12.00]${EN_LINES[0]}\r\n[00:16.00]${EN_LINES[1]}"))
    add(CorpusSong(CorpusLang.EN, "Ed Sheeran", "Shape of You", "midword-split",
        meta("Shape of You", "Ed Sheeran") + "\n[00:14.00]wind<00:14.40>ing through the <00:15.00>palms"))
    add(CorpusSong(CorpusLang.EN, "Sabrina Carpenter", "Espresso", "unsorted",
        meta("Espresso", "Sabrina Carpenter") + "\n[00:20.00]${EN_LINES[1]}\n[00:12.00]${EN_LINES[0]}\n[00:16.00]${EN_LINES[2]}"))

    // ================= JAPANESE (10) =================
    add(CorpusSong(CorpusLang.JA, "YOASOBI", "アイドル", "enhanced-cjk-nospace",
        meta("アイドル", "YOASOBI") + "\n" + enhCjkLine("00:12.00", JA_LINES[0]) + "\n" + enhCjkLine("00:16.00", JA_LINES[1])))
    add(CorpusSong(CorpusLang.JA, "YOASOBI", "夜に駆ける", "standard+ID",
        meta("夜に駆ける", "YOASOBI") + "\n" + stdBlock(JA_LINES, 10000, 3800)))
    add(CorpusSong(CorpusLang.JA, "YOASOBI", "群青", "romaji-pipe",
        meta("群青", "YOASOBI") + "\n[00:11.00]${JA_LINES[0]}|yozora no hikari wo atsumete\n[00:15.00]${JA_LINES[1]}|koboreta hoshi wo hirou yo"))
    add(CorpusSong(CorpusLang.JA, "YOASOBI", "怪物", "comma-decimal",
        meta("怪物", "YOASOBI") + "\n[00:09,80]${JA_LINES[2]}\n[00:13,60]${JA_LINES[3]}"))
    add(CorpusSong(CorpusLang.JA, "Kenshi Yonezu", "KICK BACK", "walaoke-duet",
        meta("KICK BACK", "Kenshi Yonezu") + "\n[00:10.00]M:${JA_LINES[0]}\n[00:14.00]F:${JA_LINES[1]}\n[00:18.00]D:${JA_LINES[2]}"))
    add(CorpusSong(CorpusLang.JA, "Kenshi Yonezu", "さよーなら、またいつか！", "instrumental-markers",
        meta("さよーなら、またいつか！", "Kenshi Yonezu") + "\n[00:05.00]間奏\n[00:08.00]${JA_LINES[3]}\n[00:30.00]♪\n[00:34.00]${JA_LINES[0]}"))
    add(CorpusSong(CorpusLang.JA, "Ado", "Show", "offset-negative-enhanced",
        meta("Show", "Ado", "[offset:-250]") + "\n" + enhCjkLine("00:13.00", JA_LINES[1])))
    add(CorpusSong(CorpusLang.JA, "Ado", "新時代", "ideographic-space",
        meta("新時代", "Ado") + "\n[00:12.00]夜空の\u3000光を\u3000集めて"))
    add(CorpusSong(CorpusLang.JA, "Ado", "うっせぇわ", "halfwidth-kana",
        meta("うっせぇわ", "Ado") + "\n[00:10.00]ｷﾐﾉｺｴｶﾞﾏﾂｼｲﾖ"))
    add(CorpusSong(CorpusLang.JA, "Ado", "唱", "enhanced-trailing-tag",
        meta("唱", "Ado") + "\n[00:11.00]<00:11.00>さ<00:11.30>あ"))

    // ================= CHINESE (10) =================
    add(CorpusSong(CorpusLang.ZH, "周杰伦", "晴天", "standard+ID",
        meta("晴天", "周杰伦") + "\n" + stdBlock(ZH_LINES, 13000, 4200)))
    add(CorpusSong(CorpusLang.ZH, "周杰伦", "七里香", "enhanced-cjk-nospace",
        meta("七里香", "周杰伦") + "\n" + enhCjkLine("00:14.00", ZH_LINES[0]) + "\n" + enhCjkLine("00:18.00", ZH_LINES[1])))
    add(CorpusSong(CorpusLang.ZH, "周杰伦", "告白气球", "ms3-digit",
        meta("告白气球", "周杰伦") + "\n[00:15.250]${ZH_LINES[2]}\n[00:19.750]${ZH_LINES[3]}"))
    add(CorpusSong(CorpusLang.ZH, "周杰伦", "青花瓷", "traditional",
        meta("青花瓷", "周杰伦") + "\n[00:12.00]${ZH_TRAD_LINES[0]}\n[00:16.00]${ZH_TRAD_LINES[1]}"))
    add(CorpusSong(CorpusLang.ZH, "周杰伦", "稻香", "multi-stamp",
        meta("稻香", "周杰伦") + "\n[00:10.00][00:50.00]${ZH_LINES[3]}\n[00:14.00]${ZH_LINES[0]}"))
    add(CorpusSong(CorpusLang.ZH, "邓紫棋", "光年之外", "offset-positive",
        meta("光年之外", "邓紫棋", "[offset:+400]") + "\n[00:13.00]${ZH_LINES[1]}"))
    add(CorpusSong(CorpusLang.ZH, "邓紫棋", "泡沫", "comma-decimal",
        meta("泡沫", "邓紫棋") + "\n[00:11,20]${ZH_LINES[0]}\n[00:15,80]${ZH_LINES[2]}"))
    add(CorpusSong(CorpusLang.ZH, "邓紫棋", "倒数", "bom-crlf",
        "\uFEFF" + meta("倒数", "邓紫棋").replace("\n", "\r\n") + "\r\n[00:12.00]${ZH_LINES[1]}\r\n[00:16.00]${ZH_LINES[3]}"))
    add(CorpusSong(CorpusLang.ZH, "薛之谦", "演员", "unsorted",
        meta("演员", "薛之谦") + "\n[00:22.00]${ZH_LINES[2]}\n[00:13.00]${ZH_LINES[0]}\n[00:17.00]${ZH_LINES[1]}"))
    add(CorpusSong(CorpusLang.ZH, "薛之谦", "丑八怪", "enhanced-leading-text",
        meta("丑八怪", "薛之谦") + "\n[00:10.00]该 <00:10.60>配 <00:11.20>上什么台词"))

    // ================= KOREAN (10) =================
    add(CorpusSong(CorpusLang.KO, "BTS", "Dynamite", "standard+ID",
        meta("Dynamite", "BTS") + "\n" + stdBlock(KO_LINES, 14000, 4000)))
    add(CorpusSong(CorpusLang.KO, "BTS", "봄날", "enhanced-words",
        meta("봄날", "BTS") + "\n" + enhLatinLine("00:15.00", KO_LINES[0].split(" ")) + "\n" + enhLatinLine("00:19.00", KO_LINES[1].split(" "))))
    add(CorpusSong(CorpusLang.KO, "BTS", "Butter", "kr-romanized-meta",
        meta("Butter", "NewJeans") + "\n[00:12.00]${KO_LINES[2]}\n[00:16.00]${KO_LINES[3]}"))
    add(CorpusSong(CorpusLang.KO, "IU", "Love Wins All", "comma-decimal",
        meta("Love Wins All", "IU") + "\n[00:10,60]${KO_LINES[0]}\n[00:14,20]${KO_LINES[1]}"))
    add(CorpusSong(CorpusLang.KO, "IU", "Celebrity", "ms3-digit",
        meta("Celebrity", "IU") + "\n[00:13.500]${KO_LINES[2]}\n[00:17.900]${KO_LINES[3]}"))
    add(CorpusSong(CorpusLang.KO, "NewJeans", "Super Shy", "offset-negative",
        meta("Super Shy", "NewJeans", "[offset:-300]") + "\n[00:12.00]${KO_LINES[0]}"))
    add(CorpusSong(CorpusLang.KO, "NewJeans", "Ditto", "instrumental-markers",
        meta("Ditto", "NewJeans") + "\n[00:04.00]Interlude\n[00:09.00]${KO_LINES[1]}\n[00:40.00]♪\n[00:44.00]${KO_LINES[2]}"))
    add(CorpusSong(CorpusLang.KO, "NewJeans", "Hype Boy", "walaoke-duet",
        meta("Hype Boy", "NewJeans") + "\n[00:11.00]F:${KO_LINES[3]}\n[00:15.00]M:${KO_LINES[0]}"))
    add(CorpusSong(CorpusLang.KO, "aespa", "Supernova", "unsorted+blank-lines",
        meta("Supernova", "aespa") + "\n[00:24.00]${KO_LINES[3]}\n\n[00:12.00]${KO_LINES[0]}\n   \n[00:18.00]${KO_LINES[1]}"))
    add(CorpusSong(CorpusLang.KO, "aespa", "Next Level", "kr-mixed-latin",
        meta("Next Level", "aespa") + "\n" + enhLatinLine("00:15.00", KO_LINES[0].split(" ")) +
            "\n[00:19.00]I'm on the <00:19.50>Next <00:20.00>Level"))
}
