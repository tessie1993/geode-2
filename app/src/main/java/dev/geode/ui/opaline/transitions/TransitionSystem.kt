package dev.geode.ui.opaline.transitions

import dev.geode.ui.opaline.transitions.TransitionMath.bump
import dev.geode.ui.opaline.transitions.TransitionMath.clamp
import dev.geode.ui.opaline.transitions.TransitionMath.smooth
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * src/transitions.js `TransitionSystem`: authored 3D choreography with explicit physics ports.
 * Poses are world space, deformation local space; one running transition owns each object and a
 * new one on it starts from the current pose and velocity. L16/L17 have sphere contacts; other
 * recipes expose `ports.resolvePose`. [generated] lists the meshes the renderer draws for it (L15
 * aperture, L03 tubes and heads) until [dispose]. Drive it from the render thread with [update].
 */
internal class TransitionSystem(
    private val camera: TransitionObject? = null,
    private val eventBus: ((TransitionEvent) -> Unit)? = null,
) {
    private class Motion(
        val velocity: TransitionVector,
        val angularVelocity: TransitionVector,
        val scaleVelocity: TransitionVector,
    )

    val generated = mutableListOf<TransitionAuxiliary>()
    private val active = LinkedHashMap<Int, TransitionHandle>()
    private val owners = WeakHashMap<TransitionObject, TransitionHandle>()
    private val motion = WeakHashMap<TransitionObject, Motion>()
    private val mounts = WeakHashMap<TransitionObject, TransitionPose>()
    private val fanOrigins = WeakHashMap<TransitionObject, TransitionPose>()
    private val up = TransitionVector(0.0, 1.0, 0.0)
    private var next = 1
    private var time = 0.0
    private var disposed = false

    /** Plays the recipe of a catalogue behaviour [action] ([ACTIONS]); null when it has none. */
    fun playAction(
        action: String,
        options: TransitionOptions,
    ): TransitionHandle? {
        val id = ACTIONS[action] ?: return null
        if (action != "expand-radial") return play(id, options)
        return play(id, options.copy(radialReveal = true, connectionLights = true))
    }

    /** Plays [path]'s recipes in turn, each when the previous completes; returns the first. */
    fun play(
        path: TransitionPath,
        options: (String) -> TransitionOptions,
    ): TransitionHandle = step(path.recipes, 0, options)

    private fun step(
        recipes: List<String>,
        index: Int,
        options: (String) -> TransitionOptions,
    ): TransitionHandle {
        val own = options(recipes[index])
        if (index == recipes.lastIndex) return play(recipes[index], own)
        val chained =
            own.copy(
                onComplete = { done ->
                    own.onComplete?.invoke(done)
                    step(recipes, index + 1, options)
                },
            )
        return play(recipes[index], chained)
    }

    fun play(
        id: String,
        options: TransitionOptions = TransitionOptions(),
    ): TransitionHandle {
        check(!disposed) { "TransitionSystem has been disposed." }
        val recipe = id.uppercase()
        val name = requireNotNull(RECIPES[recipe]) { "Unknown transition $recipe" }
        val duration = options.duration
        require(duration > 0 && duration.isFinite()) { "duration must be positive and finite." }
        val mass = options.mass
        require(mass == null || (mass.isFinite() && mass > 0)) {
            "Transfer mass must be positive and finite."
        }
        val subjects = (options.subjects ?: defaultSubjects(recipe, options)).distinct()
        require(subjects.isNotEmpty()) { "$recipe requires object, objects, or a camera for L18." }
        val shape = options.targetGeometry
        val topology =
            shape == null || subjects.all { s -> s.meshes.all { it.geometry.sameTopology(shape) } }
        require(topology) { "targetGeometry must have identical vertex count and index topology." }
        val interrupted = subjects.mapNotNullTo(LinkedHashSet<TransitionHandle>()) { owners[it] }
        for (owner in interrupted) cancel(owner)
        val handle = TransitionHandle(next++, recipe, name, duration, options, this)
        val center = TransitionVector()
        for (subject in subjects) center.add(worldPose(subject).position)
        handle.center = options.center?.clone() ?: center.multiplyScalar(1.0 / subjects.size)
        subjects.forEachIndexed { index, subject ->
            addEntry(handle, subject, index, subjects.size, interrupted.isEmpty())
        }
        if (recipe in DEFORMING) {
            val meshes =
                if (recipe == "L11") {
                    options.support?.meshes ?: subjects[0].meshes.take(1)
                } else {
                    subjects.flatMap { it.meshes }
                }
            for (mesh in meshes) handle.geometry.add(geometryState(mesh))
        }
        if (recipe == "L12") {
            val points =
                options.path
                    ?: listOf(
                        TransitionVector(-.8, 0.0, 0.0),
                        TransitionVector(0.0, .4, .2),
                        TransitionVector(.8, .6, .5),
                        TransitionVector(1.8, .35, .8),
                    )
            handle.branchPath = TransitionCurve(points.map { it.clone() })
        }
        if (recipe == "L15") createAperture(handle)
        if (recipe == "L03" && options.radialReveal) createConnections(handle)
        if (recipe == "L11") handle.children = options.children.map { it to worldPose(it) }
        if (recipe == "L13") {
            val ports = options.ports
            val accounted = ports?.withdraw != null && ports.deposit != null
            handle.transfer = TransitionTransfer(options.mass ?: 1.0, accounted)
        }
        active[handle.key] = handle
        emit("transition:start", handle)
        return handle
    }

    /** Advances every running transition by [dt] seconds in substeps of at most 1/120 s. */
    fun update(dt: Double) {
        require(dt.isFinite() && dt >= 0) { "dt must be finite and nonnegative." }
        if (disposed || dt == 0.0) return
        var remaining = dt
        while (remaining > 1e-9) {
            val step = min(remaining, SUBSTEP)
            remaining -= step
            time += step
            for (handle in active.values.toList()) {
                if (handle.status == TransitionStatus.RUNNING) advance(handle, step)
            }
        }
    }

    /**
     * Stops [handle] where it is (transforms and deformed vertices stay); with
     * [preserveVelocity] its motion carries into the next transition or the release port.
     */
    fun cancel(
        handle: TransitionHandle,
        preserveVelocity: Boolean = true,
    ): Boolean {
        if (handle.status != TransitionStatus.RUNNING) return false
        handle.status = TransitionStatus.CANCELLED
        if (!preserveVelocity) stop(handle)
        release(handle, preserveVelocity)
        emit("transition:cancel", handle, preserveVelocity = preserveVelocity)
        return true
    }

    /** Cancels everything and removes the generated meshes; installed clones stay on objects. */
    fun dispose() {
        for (handle in active.values.toList()) cancel(handle)
        generated.clear()
        disposed = true
    }

    private fun defaultSubjects(
        recipe: String,
        options: TransitionOptions,
    ): List<TransitionObject> {
        if (recipe == "L18") return listOfNotNull(options.subject ?: camera)
        val subject = options.subject ?: return emptyList()
        val split = recipe in ASSEMBLIES && subject.pieces.size > 1
        return if (split) subject.pieces else listOf(subject)
    }

    private fun addEntry(
        handle: TransitionHandle,
        subject: TransitionObject,
        index: Int,
        count: Int,
        fresh: Boolean,
    ) {
        val options = handle.options
        var start = worldPose(subject)
        val from = options.from
        if (from != null && fresh && count == 1) {
            start = from(start.clone())
            setWorldPose(subject, start)
        }
        val memory = motion[subject]
        val entry =
            TransitionEntry(
                subject,
                index,
                start.clone(),
                options.radius ?: subject.transitionRadius ?: .07,
                memory?.velocity ?: TransitionVector(),
                memory?.angularVelocity ?: TransitionVector(),
                memory?.scaleVelocity ?: TransitionVector(),
            )
        if (handle.id == "L01" && !mounts.containsKey(subject)) mounts[subject] = start.clone()
        if (handle.id == "L09") fanOrigins[subject] = start.clone()
        defaultEnd(handle, entry, count)
        val explicit = options.targets?.getOrNull(index) ?: options.to.takeIf { count == 1 }
        if (explicit != null) entry.end = explicit(entry.end.clone())
        handle.entries.add(entry)
        owners[subject] = handle
        options.ports?.acquire?.invoke(
            TransitionPortEvent(subject, start.clone(), entry.velocity.clone(), handle),
        )
    }

    private fun defaultEnd(
        handle: TransitionHandle,
        entry: TransitionEntry,
        count: Int,
    ) {
        val options = handle.options
        val p = entry.end.position
        val i = entry.index
        val order = i - (count - 1) / 2.0
        when (handle.id) {
            "L01" -> p.add(options.offset ?: TransitionVector(0.0, .75, 1.25))
            "L02" -> entry.end = (mounts[entry.subject] ?: entry.start).clone()
            "L03" -> orbitEnd(handle, entry, count)
            "L07" -> p.add(options.offset ?: TransitionVector(3.2, 0.0, 0.0))
            "L08" -> p.add(options.offset ?: TransitionVector(2.8, 0.0, -.35))
            "L09" -> {
                val spread = order * (options.spacing ?: 1.0)
                val depth = TransitionVector(spread, abs(order) * .22, -abs(order) * .38)
                p.copy(handle.center).add(depth)
                val turn = TransitionQuaternion().setFromAxisAngle(up, order * .22)
                entry.end.quaternion.premultiply(turn)
            }
            "L10" -> {
                val origin = fanOrigins[entry.subject]
                entry.end = (origin ?: entry.start).clone()
                if (origin == null) {
                    val stack = TransitionVector(0.0, order * (options.spacing ?: .26), -i * .06)
                    entry.end.position.copy(handle.center).add(stack)
                }
            }
            "L13" -> p.add(options.offset ?: TransitionVector(1.8, 0.0, .5))
            "L16" -> {
                val n = ceil(Math.cbrt(count.toDouble())).toInt()
                val space = options.spacing ?: .19
                val middle = (n - 1) / 2.0
                val cell =
                    TransitionVector(
                        (i % n - middle) * space,
                        (i / n % n - middle) * space,
                        (i / (n * n) - middle) * space,
                    )
                p.copy(handle.center).add(cell)
            }
            "L17" -> {
                val a = i * 2.3999632297
                val z = 1 - 2 * (i + .5) / count
                val r = sqrt(1 - z * z)
                val direction = TransitionVector(r * cos(a), z * .6 + .4, r * sin(a))
                p.copy(entry.start.position).addScaled(direction, options.distance ?: 2.4)
            }
            "L18" -> p.add(options.offset ?: TransitionVector(1.25, -.15, -1.7))
        }
    }

    private fun orbitEnd(
        handle: TransitionHandle,
        entry: TransitionEntry,
        count: Int,
    ) {
        val options = handle.options
        val p = entry.end.position
        if (options.radialReveal) {
            val angle = (options.startAngle ?: 0.0) + entry.index.toDouble() / count * PI * 2
            val r = options.radius ?: 1.65
            val socket = TransitionVector(cos(angle) * r, options.lift ?: .6, sin(angle) * r)
            p.copy(handle.center).add(socket)
            return
        }
        val d = entry.start.position.clone().sub(handle.center)
        if (d.length() < .01) d.set(.65 + count * .12, 0.0, 0.0)
        d.applyYRotation(options.angle ?: (PI * .85))
        p.copy(handle.center).add(d)
    }

    private fun geometryState(mesh: TransitionMesh): TransitionGeometryState {
        val geometry = mesh.geometry.clone()
        mesh.geometry = geometry
        val center = TransitionVector()
        val half = TransitionVector()
        geometry.bounds(center, half)
        half.multiplyScalar(.5)
        half.set(max(half.x, 1e-5), max(half.y, 1e-5), max(half.z, 1e-5))
        return TransitionGeometryState(mesh, geometry, geometry.positions.copyOf(), center, half)
    }

    private fun createAperture(handle: TransitionHandle) {
        val options = handle.options
        val material =
            options.material
                ?: TransitionMaterial(
                    color = handle.entries[0].subject.meshes.firstOrNull()?.color ?: 0xD6E9F4,
                    roughness = .18,
                    transmission = .65,
                    thickness = .12,
                    ior = 1.37,
                    clearcoat = 1.0,
                )
        val mesh =
            TransitionAuxiliary(
                "Opaline/Animated-membrane-aperture",
                TransitionGeometry.aperture(),
                material,
            )
        generated.add(mesh)
        val pose = handle.entries[0].start.clone()
        pose.position.add(options.apertureOffset ?: TransitionVector(0.0, 0.0, .65))
        place(mesh, pose)
        handle.aperture = mesh
        handle.auxiliary.add(mesh)
        updateAperture(handle, mesh, 0.0)
    }

    private fun createConnections(handle: TransitionHandle) {
        val options = handle.options
        val center = options.centerObject?.let { worldPose(it).position } ?: handle.center.clone()
        val color = options.connectionColor ?: 0x77CCE6
        val light = options.connectionLights
        for (entry in handle.entries) {
            val end = target(handle, entry).position.clone()
            val middle = center.clone().lerp(end, .55).add(TransitionVector(0.0, .18, 0.0))
            val curve = TransitionCurve(listOf(center, middle, end))
            val tube =
                TransitionAuxiliary(
                    "Opaline/Radial-connection",
                    TransitionGeometry.tube(curve, 48, options.connectionRadius ?: .013, 8),
                    TransitionMaterial(
                        color = color,
                        roughness = .16,
                        transmission = .75,
                        thickness = .03,
                        ior = 1.333,
                        emissive = color,
                        emissiveIntensity = .025,
                    ),
                )
            val head =
                TransitionAuxiliary(
                    "Opaline/Traveling-light-front",
                    TransitionGeometry.sphere(.028, 12, 8),
                    TransitionMaterial(
                        color = 0xC7FAFF,
                        roughness = .12,
                        transmission = .4,
                        thickness = .05,
                        emissive = 0x7CE8FF,
                        emissiveIntensity = 0.0,
                    ),
                    if (light) TransitionPointLight(0x7FEAFF, 0.0, .7, 2.0) else null,
                )
            generated.add(tube)
            generated.add(head)
            handle.connections.add(TransitionConnection(curve, tube, head, entry.index))
            handle.auxiliary.add(tube)
            handle.auxiliary.add(head)
        }
    }

    private fun radialUpdate(
        handle: TransitionHandle,
        t: Double,
    ) {
        for (connection in handle.connections) {
            val travel = clamp((t - .16 - connection.index * .009) / .43)
            val pose = connection.head.worldPose.clone()
            connection.curve.getPoint(travel, pose.position)
            place(connection.head, pose)
            val wave = sin(PI * travel)
            val pulse = wave * wave
            connection.head.material.emissiveIntensity = 3.5 * pulse
            connection.tube.material.emissiveIntensity = .025 + .08 * smooth(clamp((t - .3) / .4))
            connection.head.light?.intensity = .45 * pulse
        }
        val center = handle.options.centerObject
        if (center != null) {
            val x = (t - .2) / .13
            for (mesh in center.meshes) mesh.setInteraction(TransitionVector(), .8 * exp(-(x * x)))
        }
        for ((threshold, stage) in STAGES) {
            if (t >= threshold && handle.stages.add(stage)) {
                emit("transition:$stage", handle, center = handle.center.clone())
            }
        }
    }

    private fun updateAperture(
        handle: TransitionHandle,
        mesh: TransitionAuxiliary,
        t: Double,
    ) {
        val g = mesh.geometry
        val meta = checkNotNull(g.aperture)
        val outer = handle.options.outerRadius ?: 1.55
        val inner = (handle.options.innerRadius ?: 1.15) * smooth(t) + .002
        for (i in 0 until g.count) {
            val angle = meta[i * 3]
            val fraction = meta[i * 3 + 1]
            val radius = inner + (outer - inner) * fraction
            val strain = sin(angle * 3 + t * 4) * bump(t) * .025 * (1 - fraction)
            val depth =
                meta[i * 3 + 2] * .045 + sin(angle * 2 + t * 3) * bump(t) * .055 * (1 - fraction)
            g.positions[i * 3] = (cos(angle) * (radius + strain)).toFloat()
            g.positions[i * 3 + 1] = (sin(angle) * (radius + strain)).toFloat()
            g.positions[i * 3 + 2] = depth.toFloat()
        }
        g.computeVertexNormals()
    }

    private fun target(
        handle: TransitionHandle,
        entry: TransitionEntry,
    ): TransitionPose {
        val options = handle.options
        val source =
            options.targets?.getOrNull(entry.index)
                ?: options.to.takeIf { handle.entries.size == 1 }
                ?: options.ports?.mount.takeIf { handle.id == "L02" }
        return source?.invoke(entry.end.clone()) ?: entry.end
    }

    private fun pose(
        handle: TransitionHandle,
        entry: TransitionEntry,
        t: Double,
    ): TransitionPose {
        val options = handle.options
        val target = target(handle, entry)
        val s = smooth(t)
        val p = TransitionPose()
        val duration = handle.duration
        val start = entry.start
        TransitionMath.hermite(
            start.position,
            target.position,
            entry.initialVelocity,
            duration,
            t,
            p.position,
        )
        p.quaternion.copy(start.quaternion).slerp(target.quaternion, s)
        TransitionMath.hermite(
            start.scale,
            target.scale,
            entry.initialScaleVelocity,
            duration,
            t,
            p.scale,
        )
        val w = entry.initialAngularVelocity
        val angle = w.length() * duration * t * (1 - t) * (1 - t)
        if (angle > 1e-8) {
            val residual = TransitionQuaternion().setFromAxisAngle(w.clone().normalize(), angle)
            p.quaternion.premultiply(residual)
        }
        val lift = options.arcHeight ?: .4
        if (handle.id in ARCED) p.position.y += bump(t) * lift
        when (handle.id) {
            "L03" -> {
                if (options.radialReveal) {
                    radialPose(handle, entry, target, t, p)
                } else {
                    orbitPose(handle, entry, t, lift, p)
                }
            }
            "L13" -> {
                p.position.y += bump(t) * (options.arcHeight ?: 1.1)
                val stretch = 1 + bump(t) * .5
                p.scale.multiply(TransitionVector(1 / sqrt(stretch), stretch, 1 / sqrt(stretch)))
            }
            "L18" -> cameraPose(handle, entry, target, t, p)
        }
        val resolve = options.ports?.resolvePose ?: return p
        val event = TransitionPortEvent(entry.subject, p, entry.velocity.clone(), handle, t)
        return resolve(event) ?: p
    }

    private fun radialPose(
        handle: TransitionHandle,
        entry: TransitionEntry,
        target: TransitionPose,
        t: Double,
        p: TransitionPose,
    ) {
        val options = handle.options
        val local = clamp((t - .28 - entry.index * (options.stagger ?: .012)) / .55)
        val phase = smooth(local)
        val residual = handle.duration * t * (1 - t) * (1 - t)
        p.position
            .copy(entry.start.position)
            .lerp(target.position, phase)
            .addScaled(entry.initialVelocity, residual)
        p.position.y += bump(local) * (options.arcHeight ?: .32)
        p.quaternion.copy(entry.start.quaternion).slerp(target.quaternion, phase)
    }

    private fun orbitPose(
        handle: TransitionHandle,
        entry: TransitionEntry,
        t: Double,
        lift: Double,
        p: TransitionPose,
    ) {
        val options = handle.options
        val s = smooth(t)
        val delta = entry.start.position.clone().sub(handle.center)
        val angle = options.angle ?: (PI * .85)
        if (delta.length() < .01) delta.set(.65 + handle.entries.size * .12, 0.0, 0.0)
        val orbit = delta.clone().applyYRotation(angle * s)
        val end = delta.clone().applyYRotation(angle)
        p.position.add(orbit.sub(delta.clone().lerp(end, s)))
        p.position.y += bump(t) * (lift + entry.index * (options.laneSpacing ?: .25))
        p.quaternion.premultiply(TransitionQuaternion().setFromAxisAngle(up, sin(PI * s) * .2))
    }

    private fun cameraPose(
        handle: TransitionHandle,
        entry: TransitionEntry,
        target: TransitionPose,
        t: Double,
        p: TransitionPose,
    ) {
        val options = handle.options
        val s = smooth(t)
        val path = options.path
        if (path == null) {
            p.position.add(TransitionVector(sin(PI * s) * .22, bump(t) * .12, 0.0))
        } else {
            val points = listOf(entry.start.position) + path + target.position
            val curve = handle.cameraPath ?: TransitionCurve(points.map { it.clone() })
            handle.cameraPath = curve
            val linear = entry.start.position.clone().lerp(target.position, s)
            p.position.add(curve.getPoint(s).sub(linear))
        }
        val look = options.lookAt ?: return
        p.quaternion.slerp(TransitionQuaternion().setFromLookAt(p.position, look(), up), s)
    }

    private fun deform(
        handle: TransitionHandle,
        t: Double,
    ) {
        val original = TransitionVector()
        val q = TransitionVector()
        val target = TransitionVector()
        for (state in handle.geometry) {
            val base = state.base
            val positions = state.geometry.positions
            for (k in base.indices step 3) {
                original.set(base[k].toDouble(), base[k + 1].toDouble(), base[k + 2].toDouble())
                q.set(
                    (base[k] - state.center.x) / state.half.x,
                    (base[k + 1] - state.center.y) / state.half.y,
                    (base[k + 2] - state.center.z) / state.half.z,
                )
                val blend = vertexTarget(handle, state, k, original, q, t, target)
                original.lerp(target, blend)
                positions[k] = original.x.toFloat()
                positions[k + 1] = original.y.toFloat()
                positions[k + 2] = original.z.toFloat()
            }
            state.geometry.computeVertexNormals()
            if (handle.id == "L06" && t > .72) {
                // The light phase: a local excitation sweeps the settled panel.
                val sweep = (t - .72) / .28
                val point =
                    TransitionVector(
                        state.center.x + (sweep * 2 - 1) * state.half.x,
                        state.center.y + state.half.y,
                        state.center.z,
                    )
                state.mesh.setInteraction(point, sin(PI * sweep) * .7)
            }
        }
        if (handle.id != "L11") return
        // Mounted children keep their own state while socket spacing grows.
        val anchor = handle.entries[0].start.position
        val spread = 1 + ((handle.options.growth ?: 1.8) - 1) * smooth(t)
        for ((child, start) in handle.children) {
            val p = start.clone()
            p.position.x = anchor.x + (p.position.x - anchor.x) * spread
            setWorldPose(child, p)
        }
    }

    /** Writes vertex [k]'s deformation target into [target] and returns its blend. */
    private fun vertexTarget(
        handle: TransitionHandle,
        state: TransitionGeometryState,
        k: Int,
        original: TransitionVector,
        q: TransitionVector,
        t: Double,
        target: TransitionVector,
    ): Double {
        val options = handle.options
        val s = smooth(t)
        val shape = options.targetGeometry
        target.copy(original)
        if (shape != null) {
            val v = shape.positions
            target.set(v[k].toDouble(), v[k + 1].toDouble(), v[k + 2].toDouble())
        } else if (handle.id in ROUNDED) {
            val row = handle.id == "L05"
            val size =
                options.targetSize
                    ?: if (row) TransitionVector(3.5, .38, .58) else TransitionVector(2.8, .4, 1.95)
            val half = size.clone().multiplyScalar(.5)
            TransitionMath.roundedTarget(q.x, q.y, q.z, half, if (row) 3.5 else 4.5, target)
            target.add(state.center)
        }
        return when (handle.id) {
            "L04" -> {
                target.y += sin(PI * t) * .09 * (1 - q.x * q.x)
                s
            }
            "L05" -> smooth(clamp((t - .32 * (q.x + 1) * .5) / .68))
            "L06" -> smooth(clamp((t / .72 - .26 * (1 - max(abs(q.x), abs(q.z)))) / .74))
            "L07" -> {
                // Fold the closed mesh cross-section; the rear camera sees the curl.
                val wave = bump(t)
                val phase = q.z * PI * .8
                target.y += wave * (.5 + .55 * cos(phase))
                target.z += wave * .46 * sin(phase)
                target.x += sin(q.z * 3 + t * 5) * wave * .12
                1.0
            }
            "L08" -> {
                val envelope = bump(t)
                target.z += sin(q.x * 3.8 + t * 8 + q.y * 1.2) * envelope * .27
                target.x += sin(q.y * 2.4 - t * 5) * envelope * .08
                target.y -= (q.y + 1) * .5 * envelope * .12
                1.0
            }
            "L11" -> {
                val anchor = state.center.x - state.half.x
                target.x = anchor + (original.x - anchor) * (options.growth ?: 1.8)
                smooth(clamp((t - .25 * (q.x + 1) * .5) / .75))
            }
            "L12" -> branchTarget(handle, state, q, t, target)
            "L14" -> {
                // Fixed-topology bridge broadening; it does not merge disjoint fluid meshes.
                val width = 1 + .5 * s
                val neck = 1 + exp(-q.x * q.x * 7) * .46 * s
                val c = state.center
                target.set(
                    c.x + (original.x - c.x) * width,
                    c.y + (original.y - c.y) * neck,
                    c.z + (original.z - c.z) * neck,
                )
                1.0
            }
            else -> s
        }
    }

    private fun branchTarget(
        handle: TransitionHandle,
        state: TransitionGeometryState,
        q: TransitionVector,
        t: Double,
        target: TransitionVector,
    ): Double {
        val path = checkNotNull(handle.branchPath)
        val u = clamp((q.x + 1) * .5)
        val tangent = path.getTangent(u).normalize()
        val reference = if (abs(tangent.y) > .95) TransitionVector(1.0, 0.0, 0.0) else up
        val side = TransitionVector().cross(tangent, reference).normalize()
        val normal = TransitionVector().cross(side, tangent).normalize()
        path
            .getPoint(u, target)
            .addScaled(normal, q.y * state.half.y)
            .addScaled(side, q.z * state.half.z)
        return smooth(clamp((t - .38 * u) / .62))
    }

    private fun particleStep(
        handle: TransitionHandle,
        dt: Double,
        t: Double,
    ): Boolean {
        val options = handle.options
        val omega = options.stiffness ?: 11.0
        val drag = options.drag ?: 2.2
        for (entry in handle.entries) {
            val current = worldPose(entry.subject)
            val target = target(handle, entry).position
            val acceleration =
                if (handle.id == "L16") {
                    target
                        .clone()
                        .sub(current.position)
                        .multiplyScalar(omega * omega)
                        .addScaled(entry.velocity, -2 * omega)
                } else {
                    val flight =
                        target.clone().sub(entry.start.position).multiplyScalar(1 / handle.duration)
                    val wind =
                        options.wind?.invoke(current.position, time)?.clone()
                            ?: TransitionVector(.3, .12, .08)
                    flight.add(wind).sub(entry.velocity).multiplyScalar(drag)
                }
            entry.velocity.addScaled(acceleration, dt)
            current.position.addScaled(entry.velocity, dt)
            val spin = options.spin?.clone() ?: TransitionVector(.2 + entry.index * .013, .35, .17)
            entry.angularVelocity.copy(spin)
            val angle = spin.length() * dt
            if (angle != 0.0) {
                val turn = TransitionQuaternion().setFromAxisAngle(spin.normalize(), angle)
                current.quaternion.premultiply(turn)
            }
            val floor = options.floor
            if (floor != null && current.position.y < floor + entry.radius) {
                current.position.y = floor + entry.radius
                if (entry.velocity.y < 0) entry.velocity.y *= -.3
            }
            setWorldPose(entry.subject, current)
        }
        // Actual sphere contacts between persistent pieces, with equal-mass impulses.
        repeat(3) {
            for (i in handle.entries.indices) {
                for (j in i + 1 until handle.entries.size) contact(handle, i, j)
            }
        }
        if (handle.id == "L16" && t >= 1) {
            val tolerance = options.settleTolerance ?: .003
            return handle.entries.all { entry ->
                val position = worldPose(entry.subject).position
                position.distanceTo(target(handle, entry).position) < tolerance &&
                    entry.velocity.length() < .025
            }
        }
        return handle.id == "L17" && t >= 1
    }

    private fun contact(
        handle: TransitionHandle,
        i: Int,
        j: Int,
    ) {
        val a = handle.entries[i]
        val b = handle.entries[j]
        val pa = worldPose(a.subject)
        val pb = worldPose(b.subject)
        val d = pb.position.clone().sub(pa.position)
        val distance = d.length()
        val reach = a.radius + b.radius
        if (distance >= reach) return
        val normal =
            if (distance > 1e-8) {
                d.divideScalar(distance)
            } else {
                TransitionVector(if (i % 2 == 1) 1.0 else -1.0, 0.0, 0.0)
            }
        val correction = (reach - distance) * .5
        pa.position.addScaled(normal, -correction)
        pb.position.addScaled(normal, correction)
        val approach = b.velocity.clone().sub(a.velocity).dot(normal)
        if (approach < 0) {
            val impulse = -(1 + (handle.options.restitution ?: .15)) * approach * .5
            a.velocity.addScaled(normal, -impulse)
            b.velocity.addScaled(normal, impulse)
        }
        setWorldPose(a.subject, pa)
        setWorldPose(b.subject, pb)
    }

    private fun transfer(
        handle: TransitionHandle,
        ledger: TransitionTransfer,
        t: Double,
    ) {
        val ports = handle.options.ports
        if (t >= .18 && ledger.state == TransferState.SOURCE) {
            val withdraw = ports?.withdraw
            ledger.packet =
                if (withdraw == null) {
                    TransitionPacket(ledger.mass, handle.options.pigment)
                } else {
                    checkNotNull(withdraw(handle)) {
                        "Fluid source did not provide a transfer packet."
                    }
                }
            ledger.state = TransferState.IN_TRANSIT
            emit("transition:detach", handle, transfer = ledger)
        }
        if (t >= .88 && ledger.state == TransferState.IN_TRANSIT) {
            ports?.deposit?.invoke(ledger.packet, handle)
            ledger.state = TransferState.DESTINATION
            emit("transition:deposit", handle, transfer = ledger)
        }
    }

    private fun advance(
        handle: TransitionHandle,
        step: Double,
    ) {
        val options = handle.options
        handle.elapsed += step
        val t = clamp(handle.elapsed / handle.duration)
        handle.progress = t
        val complete =
            if (handle.id in PARTICULATE) particleStep(handle, step, t) else follow(handle, step, t)
        if (handle.geometry.isNotEmpty()) deform(handle, t)
        handle.aperture?.let { updateAperture(handle, it, t) }
        if (handle.connections.isNotEmpty()) radialUpdate(handle, t)
        handle.transfer?.let { transfer(handle, it, t) }
        for (entry in handle.entries) {
            entry.subject.readWorldPose(entry.last)
            motion[entry.subject] = memory(entry)
            val pose = entry.last.clone()
            options.ports?.update?.invoke(
                TransitionPortEvent(entry.subject, pose, entry.velocity.clone(), handle, t, step),
            )
        }
        options.onUpdate?.invoke(handle)
        if (complete) {
            finish(handle)
        } else if (handle.elapsed > handle.duration + (options.maxSettleTime ?: 6.0)) {
            // Impossible packed destinations are reported instead of snapped through each other.
            handle.status = TransitionStatus.BLOCKED
            release(handle, true)
            val reason = "Particle target/contact constraints did not settle."
            emit("transition:blocked", handle, reason = reason)
        }
    }

    private fun follow(
        handle: TransitionHandle,
        step: Double,
        t: Double,
    ): Boolean {
        for (entry in handle.entries) {
            val pose = pose(handle, entry, t)
            setWorldPose(entry.subject, pose)
            entry.velocity.copy(pose.position).sub(entry.last.position).divideScalar(step)
            val spin = entry.angularVelocity
            TransitionMath.angularVelocity(entry.last.quaternion, pose.quaternion, step, spin)
            entry.scaleVelocity.copy(pose.scale).sub(entry.last.scale).divideScalar(step)
        }
        return t >= 1
    }

    private fun finish(handle: TransitionHandle) {
        handle.status = TransitionStatus.COMPLETED
        handle.progress = 1.0
        if (handle.id != "L17") stop(handle)
        release(handle, true)
        emit("transition:complete", handle)
        handle.options.onComplete?.invoke(handle)
    }

    private fun stop(handle: TransitionHandle) {
        for (entry in handle.entries) {
            entry.velocity.set(0.0, 0.0, 0.0)
            entry.angularVelocity.set(0.0, 0.0, 0.0)
            entry.scaleVelocity.set(0.0, 0.0, 0.0)
            motion[entry.subject] = memory(entry)
        }
    }

    private fun release(
        handle: TransitionHandle,
        preserveVelocity: Boolean,
    ) {
        active.remove(handle.key)
        for (entry in handle.entries) {
            if (owners[entry.subject] === handle) owners.remove(entry.subject)
            val velocity = if (preserveVelocity) entry.velocity.clone() else TransitionVector()
            val spin = if (preserveVelocity) entry.angularVelocity.clone() else TransitionVector()
            val pose = worldPose(entry.subject)
            handle.options.ports?.release?.invoke(
                TransitionPortEvent(entry.subject, pose, velocity, handle, angularVelocity = spin),
            )
        }
    }

    private fun emit(
        type: String,
        handle: TransitionHandle,
        center: TransitionVector? = null,
        transfer: TransitionTransfer? = null,
        reason: String? = null,
        preserveVelocity: Boolean? = null,
    ) {
        val event = TransitionEvent(type, handle, time, center, transfer, reason, preserveVelocity)
        eventBus?.invoke(event)
        handle.options.onEvent?.invoke(event)
    }

    private fun memory(entry: TransitionEntry): Motion =
        Motion(entry.velocity.clone(), entry.angularVelocity.clone(), entry.scaleVelocity.clone())

    private fun worldPose(subject: TransitionObject): TransitionPose =
        TransitionPose().also { subject.readWorldPose(it) }

    private fun setWorldPose(
        subject: TransitionObject,
        pose: TransitionPose,
    ) {
        check(pose.isFinite()) { "Transition produced a non-finite pose." }
        subject.writeWorldPose(pose)
    }

    private fun place(
        mesh: TransitionAuxiliary,
        pose: TransitionPose,
    ) {
        check(pose.isFinite()) { "Transition produced a non-finite pose." }
        mesh.worldPose.copy(pose)
    }

    companion object {
        /** `TRANSITIONS`: recipe id → name. */
        val RECIPES =
            mapOf(
                "L01" to "Lift to foreground",
                "L02" to "Return to mount",
                "L03" to "Orbit reconfiguration",
                "L04" to "Pebble-to-panel transformation",
                "L05" to "Seed-to-row construction",
                "L06" to "Panel contour reveal",
                "L07" to "Water crest wipe",
                "L08" to "Liquid curtain reveal",
                "L09" to "Depth fan expansion",
                "L10" to "Depth fan collapse",
                "L11" to "Dock growth",
                "L12" to "Branch extension",
                "L13" to "Droplet transfer",
                "L14" to "Fluid merge reveal",
                "L15" to "Membrane aperture reveal",
                "L16" to "Particulate assembly",
                "L17" to "Particulate dispersal",
                "L18" to "Camera passage",
            )

        /**
         * compositions.json `behaviorBindings[].action` → recipe, by name: lift, return-to-mount,
         * orbit-reconfigure, depth-fan-expand/-collapse; the activate `select*` actions → L01
         * (elements.json L01 "One selected body"). content-reveal → L06 and expand-radial → L03
         * radial reveal are a mapping by behaviour, not catalogue-linked.
         */
        val ACTIONS =
            mapOf(
                "lift" to "L01",
                "return-to-mount" to "L02",
                "orbit-reconfigure" to "L03",
                "depth-fan-expand" to "L09",
                "depth-fan-collapse" to "L10",
                "select" to "L01",
                "select-compartment" to "L01",
                "select-date" to "L01",
                "select-exclusive" to "L01",
                "select-page" to "L01",
                "select-radial" to "L01",
                "select-result" to "L01",
                "select-tab" to "L01",
                "content-reveal" to "L06",
                "expand-radial" to "L03",
            )

        private const val SUBSTEP = 1.0 / 120
        private val DEFORMING = setOf("L04", "L05", "L06", "L07", "L08", "L11", "L12", "L14")
        private val ASSEMBLIES = setOf("L03", "L09", "L10", "L16", "L17")
        private val PARTICULATE = setOf("L16", "L17")
        private val ROUNDED = setOf("L04", "L05", "L06")
        private val ARCED = setOf("L01", "L02", "L09", "L10")
        private val STAGES =
            listOf(
                .08 to "awakening",
                .18 to "connection-front",
                .33 to "radial-lift",
                .6 to "socket-approach",
                .8 to "aftermath",
            )
    }
}

/** The reference-video paths (plan §12a), played in order on the same objects. */
internal enum class TransitionPath(
    val recipes: List<String>,
) {
    /** Item tap: it lifts toward the viewer and becomes a panel (V4). */
    ITEM_TAP(listOf("L01", "L04")),

    /** Now Playing / visualizer: the camera pushes in (V1). */
    CAMERA_PASSAGE(listOf("L18")),

    /** Section change: content disperses and reassembles (V3). */
    SECTION_CHANGE(listOf("L17", "L16")),

    /** Back: it settles onto its mount. */
    BACK(listOf("L02")),
}
