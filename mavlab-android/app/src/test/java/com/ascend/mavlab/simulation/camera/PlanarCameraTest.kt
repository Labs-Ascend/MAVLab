package com.ascend.mavlab.simulation.camera

import kotlin.math.PI
import kotlin.test.*

class PlanarCameraTest {
    @Test fun independentCardinalFootprints() {
        val p = VirtualCameraProfile()
        val n = PlanarCamera(p, CameraPose(0.0, 0.0, 60.0, 0.0))
        assertEquals(60.0, p.horizontalFovDegrees, 1e-10)
        assertEquals(69.2820323027551, n.groundWidth, 1e-8)
        assertEquals(51.9615242270663, n.groundHeight, 1e-8)
        assertEquals(-34.6410161513775, n.corners[0].east, 1e-8)
        assertEquals(25.9807621135332, n.corners[0].north, 1e-8)
        val e = PlanarCamera(p, CameraPose(100.0, -50.0, 60.0, PI/2))
        assertEquals(125.9807621135332, e.groundAt(320.0, 0.0).east, 1e-8)
        assertEquals(-50.0, e.groundAt(320.0,0.0).north, 1e-8)
    }
    @Test fun pixelCentreAndArbitraryRotationRoundTrips() {
        val camera = PlanarCamera(VirtualCameraProfile(), CameraPose(130.0, 240.0, 120.0, 1.127))
        for (x in listOf(0.5,320.5,639.5)) for (y in listOf(0.5,240.5,479.5)) {
            val g = camera.groundAt(x,y)
            val image = camera.imageAt(g.east,g.north)
            assertEquals(x,image.x,1e-9); assertEquals(y,image.y,1e-9)
        }
        assertEquals(2*69.2820323027551,camera.groundWidth,1e-8)
    }
    @Test fun rejectsInvalidInputs() {
        for (h in listOf(Double.NaN,Double.POSITIVE_INFINITY,0.0,-1.0,5_001.0))
            assertFailsWith<IllegalArgumentException> { CameraPose(0.0,0.0,h,0.0) }
        assertFailsWith<IllegalArgumentException> { VirtualCameraProfile(focalLengthMm=Double.NaN) }
        assertFailsWith<IllegalArgumentException> { VirtualCameraProfile(width=10) }
    }

    @Test fun stabilizedCameraQuaternionPointsOpticalForwardDownAndRightAlongImageRight() {
        for (heading in listOf(0.0, PI/2, -0.71)) {
            val q=PlanarCamera(VirtualCameraProfile(),CameraPose(0.0,0.0,60.0,heading)).quaternionNed().map { it.toDouble() }
            val (w,x,y,z)=q
            assertEquals(1.0,q.sumOf { it*it },1e-6)
            // Independently rotate camera FRD unit axes into world NED.
            assertEquals(0.0,1-2*(y*y+z*z),1e-6)
            assertEquals(0.0,2*(x*y+w*z),1e-6)
            assertEquals(1.0,2*(x*z-w*y),1e-6)
            assertEquals(-kotlin.math.sin(heading),2*(x*y-w*z),1e-6)
            assertEquals(kotlin.math.cos(heading),1-2*(x*x+z*z),1e-6)
            assertEquals(0.0,2*(y*z+w*x),1e-6)
        }
    }
}
