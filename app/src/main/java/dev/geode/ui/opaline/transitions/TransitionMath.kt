package dev.geode.ui.opaline.transitions

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** The scalar and vector helpers at the top of src/transitions.js. */
internal object TransitionMath {
    /** `clamp=(x,a=0,b=1)=>Math.max(a,Math.min(b,x))`. */
    fun clamp(
        x: Double,
        low: Double = 0.0,
        high: Double = 1.0,
    ): Double = max(low, min(high, x))

    /** `smooth`: the clamped quintic smoothstep t³(t(6t − 15) + 10). */
    fun smooth(t: Double): Double {
        val c = clamp(t)
        return c * c * c * (c * (c * 6 - 15) + 10)
    }

    /** `bump=t=>16*t*t*(1-t)*(1-t)`: 0 at both ends, 1 at t = ½. */
    fun bump(t: Double): Double = 16 * t * t * (1 - t) * (1 - t)

    /**
     * `hermite(a,b,velocity,duration,t,target)`: cubic Hermite from [a] to [b] that leaves [a]
     * with the retained [velocity] (scaled by [duration]) and arrives at [b] at rest.
     */
    fun hermite(
        a: TransitionVector,
        b: TransitionVector,
        velocity: TransitionVector,
        duration: Double,
        t: Double,
        target: TransitionVector,
    ): TransitionVector {
        val t2 = t * t
        val t3 = t2 * t
        val start = 2 * t3 - 3 * t2 + 1
        val tangent = (t3 - 2 * t2 + t) * duration
        val end = -2 * t3 + 3 * t2
        return target.set(
            a.x * start + velocity.x * tangent + b.x * end,
            a.y * start + velocity.y * tangent + b.y * end,
            a.z * start + velocity.z * tangent + b.z * end,
        )
    }

    /**
     * `angularVelocity(previous,current,dt)`: the rotation from [previous] to [current] as an
     * axis × angle rate, on the shorter arc; zero below a 1e-7 half-angle sine.
     */
    fun angularVelocity(
        previous: TransitionQuaternion,
        current: TransitionQuaternion,
        dt: Double,
        out: TransitionVector,
    ): TransitionVector {
        // current × previous⁻¹, normalised.
        val q =
            TransitionQuaternion(-previous.x, -previous.y, -previous.z, previous.w)
                .premultiply(current)
                .normalize()
        if (q.w < 0) q.set(-q.x, -q.y, -q.z, -q.w)
        val angle = 2 * acos(clamp(q.w, -1.0, 1.0))
        val sine = sqrt(max(0.0, 1 - q.w * q.w))
        if (sine < 1e-7) return out.set(0.0, 0.0, 0.0)
        val rate = angle / (sine * dt)
        return out.set(q.x * rate, q.y * rate, q.z * rate)
    }

    /**
     * `roundedTarget(q,half,exponent)`: the point of the superellipsoid with semi-axes [half] in
     * the direction of the normalised coordinate [qx], [qy], [qz], written into [out].
     */
    fun roundedTarget(
        qx: Double,
        qy: Double,
        qz: Double,
        half: TransitionVector,
        exponent: Double,
        out: TransitionVector,
    ): TransitionVector {
        out.set(qx, qy, qz).normalize()
        val sum = abs(out.x).pow(exponent) + abs(out.y).pow(exponent) + abs(out.z).pow(exponent)
        val norm = sum.pow(1 / exponent)
        val den = if (norm == 0.0 || norm.isNaN()) 1.0 else norm
        return out.multiplyScalar(1 / den).multiply(half)
    }
}
