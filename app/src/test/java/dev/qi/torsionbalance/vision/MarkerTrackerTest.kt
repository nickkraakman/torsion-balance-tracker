package dev.qi.torsionbalance.vision

import dev.qi.torsionbalance.TrackingFlags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Synthetic-image tests for [MarkerTracker]. Skipped when OpenCV natives are unavailable
 * on the JVM unit-test classpath (CI still covers [MarkerTrackLogicTest]).
 */
class MarkerTrackerTest {

    private lateinit var tracker: MarkerTracker
    private val mats = mutableListOf<Mat>()

    @Before
    fun setUp() {
        assumeTrue("OpenCV native library not loaded", openCvReady)
        tracker = MarkerTracker(
            config = MarkerTrackLogic.Config(lostFrameThreshold = 10, armJumpMaxPx = 20f, refJumpMaxPx = 3f),
        )
    }

    @After
    fun tearDown() {
        if (::tracker.isInitialized) tracker.release()
        mats.forEach { it.release() }
        mats.clear()
    }

    @Test
    fun singleFrameDropout_flagsHeldAndKeepsPosition() {
        val frame = brightFrameWithDots(armX = 400.0, armY = 200.0, refX = 200.0, refY = 200.0)
        tracker.seedArm(400f, 200f, expectedArea = 180.0)
        tracker.seedReference(200f, 200f, expectedArea = 180.0)

        val ok = process(frame)
        assertEquals(TrackingFlags.OK, ok.flags)
        assertTrue(ok.arm.found)

        val blank = brightFrame()
        val held = process(blank)
        assertEquals(TrackingFlags.HELD, held.flags)
        assertTrue(held.arm.held)
        assertEquals(ok.arm.x, held.arm.x, 0.5f)
    }

    @Test
    fun edgeClippedDistractor_doesNotStealLock() {
        // True arm at x=400; a large dark region clipped at the left ROI edge.
        val frame = brightFrame()
        Imgproc.circle(frame, Point(400.0, 200.0), 8, Scalar(20.0), -1)
        Imgproc.circle(frame, Point(200.0, 200.0), 8, Scalar(20.0), -1)
        // Horizontal bar touching the left of a widened ROI would previously false-lock.
        Imgproc.rectangle(frame, Point(0.0, 180.0), Point(40.0, 220.0), Scalar(10.0), -1)

        tracker.seedArm(400f, 200f, expectedArea = 180.0)
        tracker.seedReference(200f, 200f, expectedArea = 180.0)
        val result = process(frame)
        assertTrue(result.arm.found)
        assertEquals(400f, result.arm.x, 8f)
    }

    @Test
    fun brightnessDrift_stillTracksWithAdaptiveArea() {
        tracker.seedArm(400f, 200f, expectedArea = 200.0)
        tracker.seedReference(200f, 200f, expectedArea = 200.0)

        // Gradually brighten background / shrink apparent dark area via threshold shift:
        // draw slightly smaller then larger dots while updating EMA across frames.
        for (r in listOf(7, 7, 8, 8, 9, 9, 10)) {
            val frame = brightFrame(bg = 200.0)
            Imgproc.circle(frame, Point(400.0, 200.0), r, Scalar(25.0), -1)
            Imgproc.circle(frame, Point(200.0, 200.0), r, Scalar(25.0), -1)
            val result = process(frame)
            assertEquals("radius=$r", TrackingFlags.OK, result.flags)
        }
    }

    private fun process(gray: Mat) = tracker.processFrame(
        gray = gray,
        mmPerPixel = 0.1,
        zeroXRelPx = 0.0,
        signMultiplier = 1.0,
        armLengthMm = 100.0,
        blobAreaMin = 20.0,
        blobAreaMax = 8000.0,
        timestampMs = 0L,
    )

    private fun brightFrame(bg: Double = 220.0): Mat {
        val m = Mat(360, 640, CvType.CV_8UC1, Scalar(bg))
        mats += m
        return m
    }

    private fun brightFrameWithDots(
        armX: Double,
        armY: Double,
        refX: Double,
        refY: Double,
        radius: Int = 8,
    ): Mat {
        val m = brightFrame()
        Imgproc.circle(m, Point(armX, armY), radius, Scalar(20.0), -1)
        Imgproc.circle(m, Point(refX, refY), radius, Scalar(20.0), -1)
        return m
    }

    companion object {
        private var openCvReady = false

        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            openCvReady = try {
                // Android OpenCV AAR exposes OpenCVLoader; on JVM unit tests this often fails.
                val loader = Class.forName("org.opencv.android.OpenCVLoader")
                val method = loader.getMethod("initDebug")
                method.invoke(null) as Boolean
            } catch (_: Throwable) {
                try {
                    System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
                    true
                } catch (_: Throwable) {
                    false
                }
            }
        }
    }
}
