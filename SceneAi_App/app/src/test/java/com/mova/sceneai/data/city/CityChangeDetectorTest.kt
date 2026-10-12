package com.mova.sceneai.data.city

import org.junit.Assert.*
import org.junit.Test

/** 覆盖会造成错误通知的边界，不测试 UI 文案或地图服务的固定输出。 */
class CityChangeDetectorTest {
    @Test fun confirmsNewCityOnceAndIgnoresReturnToBaseline() {
        val detector = CityChangeDetector()
        assertFalse(detector.accept("420100", 0).entered)
        assertFalse(detector.accept("430600", 120_000).entered)
        assertTrue(detector.accept("430600", 180_000).entered)
        assertFalse(detector.accept("430600", 240_000).entered)
        assertFalse(detector.accept("420100", 900_000).entered)
        assertFalse(detector.accept("420100", 960_000).entered)
    }
    @Test fun rejectsBoundaryJitterAndStaleObservations() {
        val detector = CityChangeDetector()
        detector.accept("420100", 0)
        detector.accept("430600", 120_000)
        detector.accept("420100", 150_000)
        assertFalse(detector.accept("430600", 180_000).entered)
        assertFalse(detector.accept("430600", 181_000).entered)
        assertFalse(detector.accept("430600", 100_000).entered)
        assertTrue(detector.accept("430600", 240_000).entered)
    }
    @Test fun destinationModeDoesNotAlertForPassingCities() {
        val detector = CityChangeDetector(destination = "430600")
        detector.accept("420100", 0)
        detector.accept("421200", 120_000)
        assertFalse(detector.accept("421200", 180_000).entered)
        detector.accept("430600", 600_000)
        assertTrue(detector.accept("430600", 660_000).entered)
    }
    @Test fun respectsAlertCooldown() {
        val detector = CityChangeDetector()
        detector.accept("420100", 0)
        detector.accept("430600", 120_000)
        assertTrue(detector.accept("430600", 180_000).entered)
        detector.accept("430100", 240_000)
        assertFalse(detector.accept("430100", 300_000).entered)
    }
}
