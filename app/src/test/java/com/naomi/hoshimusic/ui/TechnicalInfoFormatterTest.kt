package com.naomi.hoshimusic.ui

import com.naomi.hoshimusic.playback.route.RouteType
import org.junit.Assert.assertEquals
import org.junit.Test

class TechnicalInfoFormatterTest {

    @Test
    fun testCleanDeviceName_Bluetooth() {
        assertEquals("Sony WH1000XM4 Audio", TechnicalInfoFormatters.cleanDeviceName("Sony WH-1000XM4 Bluetooth Audio", RouteType.BLUETOOTH))
        assertEquals("FiiO FW5", TechnicalInfoFormatters.cleanDeviceName("Bluetooth • FiiO FW5", RouteType.BLUETOOTH))
        assertEquals("Perangkat Bluetooth", TechnicalInfoFormatters.cleanDeviceName("Bluetooth", RouteType.BLUETOOTH))
    }

    @Test
    fun testCleanDeviceName_USB() {
        assertEquals("FiiO KA3", TechnicalInfoFormatters.cleanDeviceName("FiiO KA3 USB DAC", RouteType.USB))
        assertEquals("External DAC", TechnicalInfoFormatters.cleanDeviceName("USB Audio", RouteType.USB))
    }

    @Test
    fun testCleanDeviceName_Speaker() {
        assertEquals("Speaker Internal", TechnicalInfoFormatters.cleanDeviceName("Internal Speaker", RouteType.SPEAKER))
    }

    @Test
    fun testFormatKhz() {
        assertEquals("44,1 kHz", TechnicalInfoFormatters.formatKhz(44100))
        assertEquals("48 kHz", TechnicalInfoFormatters.formatKhz(48000))
        assertEquals("96 kHz", TechnicalInfoFormatters.formatKhz(96000))
        assertEquals("192 kHz", TechnicalInfoFormatters.formatKhz(192000))
    }

    @Test
    fun testFormatResolution() {
        assertEquals("24-bit / 96 kHz", TechnicalInfoFormatters.formatResolution(24, 96000))
        assertEquals("16-bit / 44,1 kHz", TechnicalInfoFormatters.formatResolution(16, 44100))
        assertEquals("Belum tersedia", TechnicalInfoFormatters.formatResolution(0, 0))
    }

    @Test
    fun testFormatBitrate() {
        assertEquals("2304 kbps", TechnicalInfoFormatters.formatBitrate(2304000))
        assertEquals("320 kbps", TechnicalInfoFormatters.formatBitrate(320000))
        assertEquals("Belum tersedia", TechnicalInfoFormatters.formatBitrate(0))
    }

    @Test
    fun testFormatRouteType() {
        assertEquals("Bluetooth", TechnicalInfoFormatters.formatRouteType(RouteType.BLUETOOTH))
        assertEquals("USB", TechnicalInfoFormatters.formatRouteType(RouteType.USB))
        assertEquals("Kabel (Jack)", TechnicalInfoFormatters.formatRouteType(RouteType.HEADPHONES))
        assertEquals("Internal", TechnicalInfoFormatters.formatRouteType(RouteType.SPEAKER))
    }
}
