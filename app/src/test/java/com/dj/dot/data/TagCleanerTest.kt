package com.dj.dot.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TagCleanerTest {
    @Test fun `drops a bracketed site tag`() = assertEquals("Chitta Kurta", TagCleaner.clean("Chitta Kurta (DjPunjab.CoM)"))

    @Test fun `drops a trailing site name after a dash`() = assertEquals("Cheez Badi", TagCleaner.clean("Cheez Badi - PagalNew"))

    @Test fun `drops a trailing domain`() = assertEquals("Desi Girl", TagCleaner.clean("Desi Girl - RiskyjaTT.CoM"))

    @Test fun `drops the bitrate`() = assertEquals("Janam Janam Dilwale", TagCleaner.clean("Janam Janam Dilwale 320 Kbps"))

    @Test fun `tidies the comma left behind`() =
        assertEquals("B Praak, Akshay Kumar", TagCleaner.clean("B Praak , Akshay Kumar (Pagalworld.name)"))

    @Test fun `returns empty when only junk remains`() = assertEquals("", TagCleaner.clean("PagaliWorld.Com"))

    @Test fun `keeps real brackets and separators`() {
        assertEquals("Arz Kiya Hai | Coke Studio Bharat", TagCleaner.clean("Arz Kiya Hai | Coke Studio Bharat"))
        assertEquals("Ek Nanha Sa Beta LoFi (Birthday Song)", TagCleaner.clean("Ek Nanha Sa Beta LoFi (Birthday Song)"))
        assertEquals("52 Bars - Karan Aujla Live at O2 London", TagCleaner.clean("52 Bars - Karan Aujla Live at O2 London"))
    }

    @Test fun `turns underscores into spaces`() = assertEquals("Tum Hi Ho", TagCleaner.clean("Tum_Hi_Ho"))
}
