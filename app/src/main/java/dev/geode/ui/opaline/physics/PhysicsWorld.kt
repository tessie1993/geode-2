package dev.geode.ui.opaline.physics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * world.js `PhysicsWorld`: one fixed-rate clock. At most [maxSubsteps] steps run per call while
 * the rest stays in [backlogSeconds]; wall time beyond [maxFrameDt] is dropped into [droppedTime].
 */
internal class PhysicsWorld(
    val fixedDt: Double = 1.0 / 120,
    val maxSubsteps: Int = 64,
    val maxFrameDt: Double = .5,
) {
    init {
        require(fixedDt > 0 && maxSubsteps >= 1 && maxFrameDt > 0) { "invalid world timing" }
    }

    val systems = mutableListOf<PhysicsSystem>()
    val couplings = mutableListOf<PhysicsCoupling>()
    var backlogSeconds = 0.0
        private set
    var time = 0.0
        private set
    var steps = 0L
        private set
    var droppedTime = 0.0
        private set
    var paused = false

    /** Bounded interpolation phase between the last two substeps. */
    val alpha: Double
        get() = min(1.0, backlogSeconds / fixedDt)

    fun <T : PhysicsSystem> add(system: T): T {
        systems.add(system)
        return system
    }

    fun remove(system: PhysicsSystem) {
        systems.remove(system)
        couplings.removeAll { it.a === system || it.b === system }
    }

    fun couple(
        a: ParticleBody,
        b: ParticleBody,
        radiusA: Double? = null,
        radiusB: Double? = null,
        restitution: Double = .15,
        friction: Double = .25,
        onContact: ((CouplingContact) -> Unit)? = null,
    ): ParticleCoupling =
        ParticleCoupling(a, b, radiusA, radiusB, restitution, friction, onContact).also {
            couplings.add(it)
        }

    fun advance(substeps: Int = 1) {
        repeat(substeps) {
            for (system in systems) system.step(fixedDt)
            for (coupling in couplings) coupling.step(fixedDt)
            time += fixedDt
            steps++
        }
    }

    /** Runs the fixed substeps [elapsed] wall seconds cover; returns how many ran. */
    fun step(elapsed: Double): Int {
        require(elapsed.isFinite() && elapsed >= 0) { "elapsed must be finite and nonnegative" }
        if (paused) return 0
        val accepted = min(elapsed, maxFrameDt)
        droppedTime += elapsed - accepted
        backlogSeconds += accepted
        var count = 0
        while (backlogSeconds + 1e-12 >= fixedDt && count < maxSubsteps) {
            advance()
            backlogSeconds -= fixedDt
            count++
        }
        if (abs(backlogSeconds) < 1e-12) backlogSeconds = 0.0
        return count
    }

    /** Releases every grab of every constraint body; deformation, velocity and pins stay. */
    fun cancelInteractions() {
        for (system in systems) (system as? ConstraintBody)?.cancelInteractions()
    }
}

/** world.js `onContact` payload. */
internal class CouplingContact(
    val position: DoubleArray,
    val normal: DoubleArray,
    val impulse: Double,
    val penetration: Double,
    val indexA: Int,
    val indexB: Int,
)

/**
 * world.js `ParticleCoupling`: bilateral, mass-weighted positional correction plus equal and
 * opposite normal and friction impulses between two particle bodies (staggered coupling).
 */
internal class ParticleCoupling(
    override val a: ParticleBody,
    override val b: ParticleBody,
    radiusA: Double? = null,
    radiusB: Double? = null,
    var restitution: Double = .15,
    var friction: Double = .25,
    var onContact: ((CouplingContact) -> Unit)? = null,
) : PhysicsCoupling {
    val radiusA = radiusA ?: a.couplingRadius ?: .015
    val radiusB = radiusB ?: b.couplingRadius ?: .015
    var contactCount = 0
        private set
    private val neighbors = IntList()

    override fun step(dt: Double) {
        var maxA = radiusA
        var maxB = radiusB
        a.radii?.let { radii -> for (i in 0 until a.count) maxA = max(maxA, radii(i)) }
        b.radii?.let { radii -> for (i in 0 until b.count) maxB = max(maxB, radii(i)) }
        val hash = SpatialHash(maxA + maxB)
        hash.build(b.positions, b.count)
        contactCount = 0
        for (i in 0 until a.count) {
            val k = 3 * i
            hash.query(a.positions(k), a.positions(k + 1), a.positions(k + 2), neighbors)
            for (n in 0 until neighbors.size) touch(i, neighbors[n])
        }
    }

    private fun touch(
        i: Int,
        j: Int,
    ) {
        val pa = a.positions
        val pb = b.positions
        val va = a.velocities
        val vb = b.velocities
        val k = 3 * i
        val l = 3 * j
        val ra = a.radii?.get(i)?.toDouble() ?: radiusA
        val rb = b.radii?.get(j)?.toDouble() ?: radiusB
        val x = pa(k) - pb(l)
        val y = pa(k + 1) - pb(l + 1)
        val z = pa(k + 2) - pb(l + 2)
        var d = hypot(x, y, z)
        if (d >= ra + rb) return
        val degenerate = d < EPS
        val n = if (degenerate) doubleArrayOf(0.0, 1.0, 0.0) else doubleArrayOf(x / d, y / d, z / d)
        if (degenerate) d = 0.0
        val wa = a.couplingInverseMass(i)
        val wb = b.couplingInverseMass(j)
        val sum = wa + wb
        if (sum == 0.0) return
        val penetration = ra + rb - d
        for (q in 0 until 3) {
            pa[k + q] = (pa(k + q) + n[q] * penetration * wa / sum).toFloat()
            pb[l + q] = (pb(l + q) - n[q] * penetration * wb / sum).toFloat()
        }
        val rel = DoubleArray(3) { va(k + it) - vb(l + it) }
        val vn = rel[0] * n[0] + rel[1] * n[1] + rel[2] * n[2]
        val impulse = max(0.0, -(1 + restitution) * vn / sum)
        val tang = DoubleArray(3) { rel[it] - vn * n[it] }
        val len = hypot(tang[0], tang[1], tang[2])
        val fric = min(friction * impulse, len / sum)
        for (q in 0 until 3) {
            val dv = n[q] * impulse - (if (len > EPS) tang[q] / len * fric else 0.0)
            va[k + q] = (va(k + q) + dv * wa).toFloat()
            vb[l + q] = (vb(l + q) - dv * wb).toFloat()
        }
        contactCount++
        val position = DoubleArray(3) { (pa(k + it) + pb(l + it)) / 2 }
        onContact?.invoke(CouplingContact(position, n, impulse, penetration, i, j))
    }
}
