package com.dj.dot.data

/**
 * Strips download-site junk that ends up in tags: "Chitta Kurta (DjPunjab.CoM)",
 * "Cheez Badi - PagalNew", "Filhall (Pagalworld.name)", "320 Kbps". Display only; files are
 * never modified.
 */
object TagCleaner {
    private const val SITES = "pagal\\w*|djpunjab\\w*|riskyjatt\\w*|mr-?jatt\\w*|djjohal\\w*|jattjugadu\\w*|" +
        "songspk\\w*|wapking\\w*|djmaza\\w*|downloadming\\w*|bestwap\\w*|mp3tau\\w*|raagjatt\\w*|pendujatt\\w*|" +
        "vipjatt\\w*|masstamilan\\w*|naasongs\\w*|starmusiq\\w*|songs\\.pk"
    private const val DOMAIN = "[\\w-]+\\.(?:com|co|in|net|org|name|me|io|cc|info|fm|pk|world|live|site|xyz|pro|link|cm)"

    private val bracketed = Regex("""\s*[(\[{][^)\]}]*(?:$DOMAIN|\b(?:$SITES)|kbps|downloaded from)[^)\]}]*[)\]}]""", RegexOption.IGNORE_CASE)
    private val trailing = Regex("""\s*[-–|:~]\s*(?:$DOMAIN|(?:$SITES))[\w.]*\s*$""", RegexOption.IGNORE_CASE)
    private val bareDomain = Regex("""\s*\b$DOMAIN\b""", RegexOption.IGNORE_CASE)
    private val bareSite = Regex("""\s*\b(?:$SITES)\b""", RegexOption.IGNORE_CASE)
    private val bitrate = Regex("""\s*\b\d{2,3}\s*kbps\b""", RegexOption.IGNORE_CASE)
    private val spaces = Regex("""\s{2,}""")

    /** Cleaned text, or an empty string if nothing meaningful is left. */
    fun clean(raw: String): String {
        var s = raw.replace('_', ' ')
        s = bracketed.replace(s, " ")
        s = trailing.replace(s, "")
        s = bitrate.replace(s, "")
        s = bareDomain.replace(s, "")
        s = bareSite.replace(s, "")
        return spaces.replace(s, " ").replace(" ,", ",").trim().trim('-', '–', '|', ':', ',', '~', ' ')
    }
}
