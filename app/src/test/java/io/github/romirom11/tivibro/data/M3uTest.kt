package io.github.romirom11.tivibro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Synthetic snippets shaped like the provider's 13 M3U formats (example.com, fake tokens). */
class M3uTest {
    private fun one(text: String) = M3u.parse(text).channels.single()

    @Test fun tivimate() {
        val p = M3u.parse(
            """
            #EXTM3U generation-date="2026.10.31 02:18:50 MSK" url-tvg="https://epg.example/3.1.xml.gz"
            #EXTINF:0 tvg-id="ch332" tvg-name="UA: 1+1 Україна HD" tvg-logo="https://img.example/332.png"  timeshift="7", UA: 1+1 Україна HD
            #EXTGRP:1. Наше
            https://tv.example/ch332/mono.m3u8?token=T
            """.trimIndent(),
        )
        assertEquals(listOf("https://epg.example/3.1.xml.gz"), p.header.tvgUrls)
        val c = p.channels.single()
        assertEquals("ch332", c.id)
        assertEquals("ch332", c.epgId)
        assertEquals("1+1 Україна", c.name)
        assertEquals(true, c.hd)
        assertEquals("Наше", c.group)
        assertEquals(7, c.archiveDays)
        assertEquals("https://img.example/332.png", c.logo)
        assertNull(c.catchupType)
        assertEquals(Catchup.Kind.FLUSSONIC, Catchup.kind(c))
    }

    @Test fun halvaHeaderDefaultsAndNoSpaceAfterDuration() {
        val p = M3u.parse(
            """
            #EXTM3U url-tvg="https://epg.example/3.1.xml.gz" playlist-name="hls.Playlist" catchup-type="flussonic" catchup-time="604800" playlist-logo="https://img.example/logo.png"
            #EXTINF:0tvg-id="ch332" tvg-name="UA: 1+1 Україна HD" group-title="1. Наше" tvg-logo="https://img.example/332.png?x",UA: 1+1 Україна HD
            https://tv.example/ch332/mono.m3u8?token=T
            """.trimIndent(),
        )
        assertEquals("hls.Playlist", p.header.name)
        assertEquals("https://img.example/logo.png", p.header.logo)
        val c = p.channels.single()
        assertEquals("ch332", c.id) // not "0tvg-id"
        assertEquals(7, c.archiveDays)
        assertEquals("flussonic", c.catchupType)
        assertEquals("Наше", c.group)
    }

    @Test fun simpleTvShiftAndZeroDays() {
        val p = M3u.parse(
            """
            #EXTM3U url-tvg="https://epg.example/3.1.xml.gz"
            #EXTINF:0 tvg-id="ch1" tvg-name="A"  catchup-days="7" catchup-type="shift", A
            #EXTGRP:Кіно
            https://tv.example/ch1/mono.m3u8?token=T
            #EXTINF:0 tvg-id="ch2" tvg-name="B"  catchup-days="0" catchup-type="shift", B
            #EXTGRP:Кіно
            https://tv.example/ch2/mono.m3u8?token=T
            """.trimIndent(),
        )
        val (a, b) = p.channels
        assertEquals(7, a.archiveDays)
        assertEquals("shift", a.catchupType)
        assertEquals(Catchup.Kind.SHIFT, Catchup.kind(a))
        assertEquals(0, b.archiveDays)
        assertEquals(2, b.num)
    }

    @Test fun depthPriority() {
        val c = one(
            """
            #EXTM3U catchup-days="2"
            #EXTINF:-1 tvg-id="x" tvg-rec="3" timeshift="4" catchup-days="5",X
            http://h.example/x
            """.trimIndent(),
        )
        assertEquals(5, c.archiveDays)
        assertEquals(4, one("#EXTM3U\n#EXTINF:-1 tvg-rec=\"3\" timeshift=\"4\",X\nhttp://h.example/x").archiveDays)
        assertEquals(3, one("#EXTM3U\n#EXTINF:-1 tvg-rec=\"3\",X\nhttp://h.example/x").archiveDays)
        assertEquals(2, one("#EXTM3U catchup-days=\"2\"\n#EXTINF:-1,X\nhttp://h.example/x").archiveDays)
        assertEquals(1, one("#EXTM3U catchup-time=\"86400\"\n#EXTINF:-1,X\nhttp://h.example/x").archiveDays)
        assertEquals(0, one("#EXTM3U catchup-time=\"86400\"\n#EXTINF:-1 catchup-days=\"0\",X\nhttp://h.example/x").archiveDays)
    }

    @Test fun visionNoTvgIdUsesUrl() {
        val c = one(
            """
            #EXTM3U
            #EXTINF:-1 group-title="1. Наше" tvg-name="UA: 1+1 Int" tvg-rec="7", UA: 1+1 Int
            #EXTGRP:1. Наше
            https://tv.example/ch218/mono.m3u8?token=T
            """.trimIndent(),
        )
        assertEquals("ch218", c.id)
        assertEquals("ch218", c.epgId)
        assertEquals("1+1 Int", c.name)
        assertEquals("Наше", c.group) // group-title wins over #EXTGRP
    }

    @Test fun smartIptvTvgIds() {
        val c = one("#EXTM3U\n#EXTINF:0 tvg-id=\"tvg_332\" timeshift=\"7\", UA: 1+1 Україна HD\n#EXTGRP:1. Наше\nhttps://tv.example/ch332/mono.m3u8?token=T")
        assertEquals("tvg_332", c.id)
        assertEquals("ch332", c.epgId)
        assertEquals(listOf("tvg_332", "ch332", "1+1 україна"), c.epgKeys())
    }

    @Test fun ottPlayIdInTvgNameUsesTitle() {
        val c = one("#EXTM3U\n#EXTINF:0 tvg-id=\"ch332\" tvg-name=\"ch332\" timeshift=\"7\", UA: 1+1 Україна HD\nhttps://tv.example/ch332/mono.m3u8?token=T")
        assertEquals("1+1 Україна", c.name)
    }

    @Test fun kodiIndexAndHeaderXTvgList() {
        val p = M3u.parse(
            """
            ﻿#EXTM3U x-tvg-url="https://a.example/1.xml, https://b.example/2.xml.gz"

            #EXTINF:0 tvg-id="ch5" group-title="Спорт" timeshift="3", Sport, Live HD
            https://tv.example/ch5/index.m3u8?token=T
            """.trimIndent(),
        )
        assertEquals(listOf("https://a.example/1.xml", "https://b.example/2.xml.gz"), p.header.tvgUrls)
        val c = p.channels.single()
        assertEquals("Sport, Live", c.name) // a comma in the title
        assertEquals("Спорт", c.group)
        assertEquals(Catchup.Kind.FLUSSONIC, Catchup.kind(c))
    }

    @Test fun noNameNoId() {
        val c = one("#EXTM3U\n#EXTINF:-1,Мій канал\nhttp://h.example/live/stream")
        assertEquals("n:мій канал", c.id)
        assertEquals("мій канал", c.epgId)
        assertEquals("Інше", c.group)
        assertEquals(listOf("мій канал"), c.epgKeys())
    }

    @Test fun names() {
        assertEquals("1+1 україна", epgName("UA: 1+1 Україна HD"))
        assertEquals("discovery", epgName("Discovery FHD"))
        assertEquals("ch332", normalizeTvgId("tvg_332"))
        assertEquals("abc", normalizeTvgId("abc"))
    }
}
