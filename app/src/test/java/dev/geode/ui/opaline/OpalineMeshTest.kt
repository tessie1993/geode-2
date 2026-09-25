package dev.geode.ui.opaline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OpalineMeshTest {
    @Test
    fun allBundledLibraryMeshesLoadIncludingUnindexedTriangles() {
        OpalineMesh.ELEMENTS.forEach { id ->
            val mesh = OpalineMesh.read(File("src/main/assets/opaline-native/$id.glb").readBytes())
            assertTrue(id, mesh.pieces.isNotEmpty())
            assertTrue(id, (0..2).all { mesh.maximum[it] > mesh.minimum[it] })
            mesh.pieces.forEach { piece ->
                assertEquals(0, piece.indices.size % 3)
                assertTrue(piece.color.all { it.isFinite() })
                assertTrue(piece.ior >= 1f)
                assertTrue(piece.attenuationDistance > 0f)
            }
        }
    }

    @Test
    fun meniscusKeepsItsSevenAuthoredShapes() {
        val mesh = OpalineMesh.read(File("src/main/assets/opaline-native/B07.glb").readBytes())
        assertTrue(mesh.pieces.any { it.motion == "liquidRail" && it.morphs.size == 7 })
    }

    @Test
    fun dialMarkerInheritsItsCapsMotionAndPivot() {
        val mesh = OpalineMesh.read(File("src/main/assets/opaline-native/B13.glb").readBytes())
        val moving = mesh.pieces.filter { it.motion == "dial" }
        assertEquals(2, moving.size)
        assertTrue(moving[0].motionPivot.contentEquals(moving[1].motionPivot))
    }

    @Test(expected = IllegalArgumentException::class)
    fun corruptHeaderIsRejectedBeforeMeshAllocation() {
        OpalineMesh.read(ByteArray(32))
    }
}
