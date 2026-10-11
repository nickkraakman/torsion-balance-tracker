package dev.qi.torsionbalance.vision

import dev.qi.torsionbalance.TrackingFlags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.PI

/**
 * Synthetic-image tests for [MarkerTracker] using desktop OpenCV natives (openpnp).
 */
class MarkerTrackerTest {

    private lateinit var tracker: MarkerTracker
    private val mats = mutableListOf<Mat>()

    @Before
    fun setUp() {
        assumeTrue("OpenCV native library not loaded", openCvReady)
        tracker = MarkerTracker(
            config = MarkerTrackLogic.Config(
                lostFrameThreshold = 10,
                armJumpMaxPx = 20f,
                refJumpMaxPx = 3f,
            ),
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
        val frame = brightFrame()
        Imgproc.circle(frame, Point(400.0, 200.0), 8, Scalar(20.0), -1)
        Imgproc.circle(frame, Point(200.0, 200.0), 8, Scalar(20.0), -1)
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

        for (r in listOf(7, 7, 8, 8, 9, 9, 10)) {
            val frame = brightFrame(bg = 200.0)
            Imgproc.circle(frame, Point(400.0, 200.0), r, Scalar(25.0), -1)
            Imgproc.circle(frame, Point(200.0, 200.0), r, Scalar(25.0), -1)
            val result = process(frame)
            assertEquals("radius=$r", TrackingFlags.OK, result.flags)
        }
    }

    /**
     * Large marker (~r=30, area≈2800) must be accepted at missFrames=0 — ROI half grows
     * from expected area so the contour does not touch the border.
     */
    @Test
    fun largeMarker_acceptedAtMissFramesZero_noAlternatingHeld() {
        val radius = 30
        val area = PI * radius * radius
        val frame = brightFrame()
        Imgproc.circle(frame, Point(320.0, 180.0), radius, Scalar(20.0), -1)
        Imgproc.circle(frame, Point(160.0, 180.0), 8, Scalar(20.0), -1)

        tracker.seedArm(320f, 180f, expectedArea = area)
        tracker.seedReference(160f, 180f, expectedArea = 180.0)

        repeat(5) {
            val result = process(frame)
            assertEquals("frame=$it flags=${result.flags}", TrackingFlags.OK, result.flags)
            assertTrue(result.arm.found)
            assertFalse(result.arm.held)
        }
    }

    /**
     * Two-frame jump-gate sequence on real images: a 50 px shadow is rejected on the
     * first miss and still rejected on the next frame (gate grows, not disabled).
     */
    @Test
    fun jumpGate_twoFrameSequence_shadowNotAcceptedOnSecondFrame() {
        val trueX = 400.0
        val shadowX = 350.0 // 50 px left of true centre
        tracker.seedArm(trueX.toFloat(), 200f, expectedArea = 180.0)
        tracker.seedReference(200f, 200f, expectedArea = 180.0)

        // Frame 0: only the true marker — lock on.
        val good = brightFrame()
        Imgproc.circle(good, Point(trueX, 200.0), 8, Scalar(20.0), -1)
        Imgproc.circle(good, Point(200.0, 200.0), 8, Scalar(20.0), -1)
        assertEquals(TrackingFlags.OK, process(good).flags)

        // Frame 1: true marker gone; only a shadow 50 px away.
        val shadowOnly = brightFrame()
        Imgproc.circle(shadowOnly, Point(shadowX, 200.0), 8, Scalar(20.0), -1)
        Imgproc.circle(shadowOnly, Point(200.0, 200.0), 8, Scalar(20.0), -1)
        val miss1 = process(shadowOnly)
        assertEquals(TrackingFlags.HELD, miss1.flags)
        assertFalse(miss1.arm.found)
        assertEquals(trueX.toFloat(), miss1.arm.x, 1f)

        // Frame 2: same shadow — must still reject (allowed jump = 40 < 50).
        val miss2 = process(shadowOnly)
        assertEquals(TrackingFlags.HELD, miss2.flags)
        assertFalse(miss2.arm.found)
        assertEquals(trueX.toFloat(), miss2.arm.x, 1f)
    }

    @Test
    fun runBlobSearch_rejectsBorderTouchingContour() {
        val roi = Mat(120, 120, CvType.CV_8UC1, Scalar(220.0))
        mats += roi
        // Contour glued to left edge.
        Imgproc.rectangle(roi, Point(0.0, 40.0), Point(20.0, 80.0), Scalar(10.0), -1)
        // Valid centre blob.
        Imgproc.circle(roi, Point(60.0, 60.0), 8, Scalar(20.0), -1)

        val result = tracker.runBlobSearch(
            roiGray = roi,
            blobAreaMin = 20.0,
            blobAreaMax = 8000.0,
            preferX = 60f,
            preferY = 60f,
            expectedArea = 180.0,
            allowedJumpPx = 20f,
        )
        assertTrue(result.found)
        assertEquals(60f, result.x, 6f)
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
            openCvReady = OpenCvTestSupport.isReady
        }
    }
}
