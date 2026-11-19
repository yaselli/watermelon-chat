package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatStickerTest {
    @Test
    fun localReferencesCarryCategoryAndOnlyResolveManagedFilenames() {
        val filename = "afda54ac-4057-4523-bb1d-5d78319f8c72.gif"
        val ref = ChatSticker.LOCAL_PREFIX + filename
        assertEquals(filename, ChatSticker.localFilename(ref))
        assertNull(ChatSticker.localFilename(ChatSticker.LOCAL_PREFIX + "../" + filename))
        assertEquals("![西瓜瓣表情 · 小狗 · 撒娇 · 求抱抱]($ref)", ChatSticker("小狗", "求抱抱", ref, "撒娇").markdown())
    }

    @Test
    fun encodesLinkAndKeepsMeaningOnOneLine() {
        val sticker = requireNotNull(ChatSticker.fromInput(" 企鹅 ", "撒娇\n逗人", "https://example.com/a(1).png"))
        assertEquals("![西瓜瓣表情 · 企鹅 · 撒娇 逗人](https://example.com/a%281%29.png)", sticker.markdown())
    }

    @Test
    fun rejectsMissingNameAndNonWebLinks() {
        assertNull(ChatSticker.fromInput(" ", "", "https://example.com/a"))
        assertNull(ChatSticker.fromInput("猫", "", "file:///cat"))
        assertNull(ChatSticker.fromInput("猫", "", "https://user:pass@example.com/a"))
    }
}
