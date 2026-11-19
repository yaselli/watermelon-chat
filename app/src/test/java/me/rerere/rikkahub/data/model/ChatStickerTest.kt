package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatStickerTest {
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
