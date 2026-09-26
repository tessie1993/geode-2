package dev.geode.ui.opaline

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Per-frame inputs for one piece, filled by the renderer (reused, not allocated per frame). */
internal class OpalineMotionInput {
    /**
     * This piece's own value 0..1 (for B04 the renderer passes value or secondaryValue by motion
     * index).
     */
    var value = 0.5f

    /** Second axis value 0..1 (motion.js `v2`: xy, trackball, joystick). */
    var secondaryValue = 0.5f

    /** Velocity of the value spring, units per second (motion.js `this.value.velocity`). */
    var velocity = 0f

    /** Contact pressure 0..1 (motion.js `p`). */
    var pressure = 0f

    /** Scene time in seconds (motion.js `time`). */
    var time = 0f

    var reducedMotion = false
}

/**
 * src/motion.js MotionController.update for one piece: the per-kind change it writes to each
 * moving part's rest position, rotation and scale, which the part's children follow through the
 * static chain below it. The gel dent, the root's press offset (`root.position.z`) and liquidRail
 * morph weights stay with the renderer; C15's `stack` has no update branch and stays static.
 * Reduced motion (AI-HANDOFF.md: settled views, same semantics) keeps every value-driven pose and
 * settles the secondary motion: velocity stretch, wind sway and the press hinge.
 */
internal object OpalinePieceMotion {
    /**
     * Writes into [out] (16 floats, column-major, android.opengl.Matrix layout) the piece's
     * model matrix relative to the element root, with motion.js MotionController.update's
     * per-kind change applied to the piece's local TRS; with no motion it equals the static
     * transform the renderer used before (OpalineMesh.Piece.transform).
     */
    fun pose(
        piece: OpalineMesh.Piece,
        input: OpalineMotionInput,
        out: FloatArray,
    ) {
        val joint = piece.joint
        if (joint == null || joint.kind !in POSED) {
            piece.transform.copyInto(out)
            return
        }
        val x = translation(joint, X, input)
        val y = translation(joint, Y, input)
        val z = translation(joint, Z, input)
        val ax = rotation(joint, X, input)
        val ay = rotation(joint, Y, input)
        val az = rotation(joint, Z, input)
        // `mesh.scale.copy(part.scale);mesh.scale.x*=stretch;mesh.scale.y/=Math.sqrt(stretch);
        // mesh.scale.z/=Math.sqrt(stretch)` for sliders only.
        val stretch = if (joint.kind == SLIDER) stretch(input) else 1f
        val sx = joint.scale[X] * stretch
        val sy = joint.scale[Y] / sqrt(stretch)
        val sz = joint.scale[Z] / sqrt(stretch)
        // three.js Matrix4.makeRotationFromEuler (order XYZ), columns scaled as Matrix4.compose.
        val a = cos(ax)
        val b = sin(ax)
        val c = cos(ay)
        val d = sin(ay)
        val e = cos(az)
        val f = sin(az)
        val m00 = c * e * sx
        val m10 = (a * f + b * e * d) * sx
        val m20 = (b * f - a * e * d) * sx
        val m01 = -c * f * sy
        val m11 = (a * e - b * f * d) * sy
        val m21 = (b * e + a * f * d) * sy
        val m02 = d * sz
        val m12 = -b * c * sz
        val m22 = a * c * sz
        // out = parent × local × child, one child column at a time.
        val parent = joint.parent
        val child = piece.child
        for (column in 0..3) {
            val cx = child[column * 4]
            val cy = child[column * 4 + 1]
            val cz = child[column * 4 + 2]
            val cw = child[column * 4 + 3]
            val lx = m00 * cx + m01 * cy + m02 * cz + x * cw
            val ly = m10 * cx + m11 * cy + m12 * cz + y * cw
            val lz = m20 * cx + m21 * cy + m22 * cz + z * cw
            for (row in 0..3) {
                out[column * 4 + row] =
                    parent[row] * lx + parent[4 + row] * ly + parent[8 + row] * lz +
                    parent[12 + row] * cw
            }
        }
    }

    /** The position motion.js writes on [axis]; any other axis or kind keeps the rest position. */
    private fun translation(
        joint: OpalineMesh.Joint,
        axis: Int,
        input: OpalineMotionInput,
    ): Float {
        val rest = joint.translation[axis]
        return when (joint.kind) {
            // `mesh.position[c.axis||'x']=lerp(c.min??-.94,c.max??.94,pv)`
            SLIDER ->
                if (axis == joint.axisOr(X)) {
                    lerp(joint.min.unsetOr(-.94f), joint.max.unsetOr(.94f), input.value)
                } else {
                    rest
                }
            ARC, ORBIT -> arc(joint, axis, input.value, rest)
            // `mesh.position.x=lerp(c.bounds[0],c.bounds[1],pv)`, y from bounds 2 and 3 and v2.
            XY ->
                when (axis) {
                    X -> lerp(joint.bounds[0], joint.bounds[1], input.value)
                    Y -> lerp(joint.bounds[2], joint.bounds[3], input.secondaryValue)
                    else -> rest
                }
            else -> rest
        }
    }

    /**
     * arc and orbit: `a=lerp(c.start??0,c.end??Math.PI*2,pv)+(c.phase||0)`, then
     * `x=(c.center?.[0]||0)+Math.cos(a)*(c.radius||.8)` and y with sin; z keeps its rest.
     */
    private fun arc(
        joint: OpalineMesh.Joint,
        axis: Int,
        value: Float,
        rest: Float,
    ): Float {
        val sweep = lerp(joint.start.unsetOr(0f), joint.end.unsetOr(PI_FLOAT * 2f), value)
        val angle = sweep + joint.phase.falsyOr(0f)
        val radius = joint.radius.falsyOr(.8f)
        return when (axis) {
            X -> joint.center[0].falsyOr(0f) + cos(angle) * radius
            Y -> joint.center[1].falsyOr(0f) + sin(angle) * radius
            else -> rest
        }
    }

    /** The Euler angle motion.js writes on [axis]; any other axis or kind keeps the rest angle. */
    private fun rotation(
        joint: OpalineMesh.Joint,
        axis: Int,
        input: OpalineMotionInput,
    ): Float {
        val rest = joint.rotation[axis]
        val value = input.value
        val live = !input.reducedMotion
        return when (joint.kind) {
            // `mesh.rotation[c.axis||'y']=lerp(c.min,c.max,pv)`
            ROCKER -> if (axis == joint.axisOr(Y)) lerp(joint.min, joint.max, value) else rest
            // `mesh.rotation[c.axis||'z']=part.rotation[c.axis||'z']-(pv-.5)*Math.PI*1.6`
            DIAL, WHEEL ->
                if (axis == joint.axisOr(Z)) rest - (value - .5f) * PI_FLOAT * 1.6f else rest
            TRACKBALL -> trackball(axis, input, rest)
            JOYSTICK -> joystick(joint, axis, input, rest)
            // `mesh.rotation.z=c.baseAngle+(pv-.5)*.9`
            IRIS -> if (axis == Z) joint.baseAngle + (value - .5f) * .9f else rest
            // `mesh.rotation.z=part.rotation.z+Math.sin(time*.7+(c.phase||0))*.04`
            WIND ->
                if (axis == Z && live) {
                    rest + sin(input.time * .7f + joint.phase.falsyOr(0f)) * .04f
                } else {
                    rest
                }
            // `mesh.rotation[c.axis||'y']=part.rotation[c.axis||'y']+p*.18`
            HINGE -> if (axis == joint.axisOr(Y) && live) rest + input.pressure * .18f else rest
            else -> rest
        }
    }

    /** `rotation.x=part.rotation.x+(pv-.5)*Math.PI; rotation.y=part.rotation.y+(v2-.5)*Math.PI`. */
    private fun trackball(
        axis: Int,
        input: OpalineMotionInput,
        rest: Float,
    ): Float =
        when (axis) {
            X -> rest + (input.value - .5f) * PI_FLOAT
            Y -> rest + (input.secondaryValue - .5f) * PI_FLOAT
            else -> rest
        }

    /** `rotation.x=(v2-.5)*(c.maxAngle||.5)*2; rotation.y=(pv-.5)*(c.maxAngle||.5)*2`. */
    private fun joystick(
        joint: OpalineMesh.Joint,
        axis: Int,
        input: OpalineMotionInput,
        rest: Float,
    ): Float {
        val reach = joint.maxAngle.falsyOr(.5f) * 2f
        return when (axis) {
            X -> (input.secondaryValue - .5f) * reach
            Y -> (input.value - .5f) * reach
            else -> rest
        }
    }

    /**
     * A moving pearl stretches in its travel direction and recovers at its stop:
     * `stretch=1+Math.min(.28,Math.abs(this.value.velocity)*.12)`.
     */
    private fun stretch(input: OpalineMotionInput): Float {
        if (input.reducedMotion) return 1f
        return 1f + min(.28f, abs(input.velocity) * .12f)
    }

    /** THREE.MathUtils.lerp: `(1-t)*x+t*y`. */
    private fun lerp(
        x: Float,
        y: Float,
        t: Float,
    ) = (1 - t) * x + t * y

    /** JavaScript `??`: [fallback] where the config leaves the number out. */
    private fun Float.unsetOr(fallback: Float) = if (isNaN()) fallback else this

    /** JavaScript `||`: [fallback] where the config leaves the number out or authors 0. */
    private fun Float.falsyOr(fallback: Float) = if (isNaN() || this == 0f) fallback else this

    /** motion.js `c.axis||fallback`. */
    private fun OpalineMesh.Joint.axisOr(fallback: Int) = if (axis < 0) fallback else axis

    private const val X = 0
    private const val Y = 1
    private const val Z = 2

    private const val SLIDER = "slider"
    private const val ARC = "arc"
    private const val ORBIT = "orbit"
    private const val ROCKER = "rocker"
    private const val DIAL = "dial"
    private const val WHEEL = "wheel"
    private const val TRACKBALL = "trackball"
    private const val XY = "xy"
    private const val JOYSTICK = "joystick"
    private const val IRIS = "iris"
    private const val WIND = "wind"
    private const val HINGE = "hinge"

    /** The kinds whose motion.js update branch moves the part (liquidRail only morphs). */
    private val POSED =
        setOf(
            SLIDER,
            ARC,
            ORBIT,
            ROCKER,
            DIAL,
            WHEEL,
            TRACKBALL,
            XY,
            JOYSTICK,
            IRIS,
            WIND,
            HINGE,
        )

    private val PI_FLOAT = PI.toFloat()
}
