package app.honmani.reader.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VolumeTitleTest {
    @Test
    fun numberBeforeALabelInBrackets() {
        assertEquals(
            VolumeTitle.Split("10", "無職転生 ～異世界行ったら本気だす～ (MFブックス)"),
            VolumeTitle.split("無職転生 ～異世界行ったら本気だす～ 10 (MFブックス)"),
        )
        assertEquals(
            VolumeTitle.Split("1", "無職転生 ～異世界行ったら本気だす～ (MFブックス)"),
            VolumeTitle.split("無職転生 ～異世界行ったら本気だす～ 01 (MFブックス)"),
        )
    }

    @Test
    fun usualWaysOfWritingAVolume() {
        assertEquals(VolumeTitle.Split("3", "ソードアート・オンライン"), VolumeTitle.split("ソードアート・オンライン 3"))
        assertEquals(VolumeTitle.Split("3", "ソードアート・オンライン"), VolumeTitle.split("ソードアート・オンライン　３"))
        assertEquals(VolumeTitle.Split("12", "薬屋のひとりごと"), VolumeTitle.split("薬屋のひとりごと 第12巻"))
        assertEquals(VolumeTitle.Split("2", "ある魔女"), VolumeTitle.split("ある魔女2巻"))
        assertEquals(VolumeTitle.Split("4", "Re:Zero"), VolumeTitle.split("Re:Zero Vol. 4"))
        assertEquals(VolumeTitle.Split("5", "〈物語〉シリーズ 【電子版】"), VolumeTitle.split("〈物語〉シリーズ（5）【電子版】"))
        assertEquals(VolumeTitle.Split("2", "とある魔術の～新約～"), VolumeTitle.split("とある魔術の～新約～2"))
    }

    @Test
    fun titlesWithoutAVolumeStayAsTheyAre() {
        assertNull(VolumeTitle.split("注文の多い料理店"))
        assertNull(VolumeTitle.split("ナンバー23"))
        assertNull(VolumeTitle.split("1984"))
        assertNull(VolumeTitle.split("Blade Runner 2049"))
        assertNull(VolumeTitle.split("Steins;Gate 0"))
        assertNull(VolumeTitle.split("(一般小説) [石田衣良] 赤・黒（ルージュ・ノワール） 池袋ウエストゲートパーク外伝 (青空文庫対応txt 表紙付)(校正08-11-02)"))
    }
}
