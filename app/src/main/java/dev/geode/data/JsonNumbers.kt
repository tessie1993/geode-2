package dev.geode.data

import org.json.JSONObject

/**
 * JSON number reads that cannot return a non-finite value.
 *
 * Every float in this app's stored formats ultimately reaches `SceneParams` and, from there, a
 * shader uniform. JSON is an untrusted boundary — a preset arrives from a `geode://preset/` link
 * that any app or web page can send, or from a file the user picked — and `org.json` will happily
 * decode `"strobe": "NaN"` to [Double.NaN], because `JSON.toDouble` routes a JSON *string* through
 * `Double.valueOf`.
 *
 * A NaN that gets in is not a transient glitch: the renderer's parameter interpolation feeds its
 * own output back in every frame, so one poisons every interpolated field until the handle is
 * destroyed, and the photosensitivity clamp cannot recover it (every IEEE comparison with a NaN is
 * false, so clamping is a no-op). `±Infinity` clamps correctly downstream but is never a valid
 * parameter either, so both are rejected here.
 *
 * Use these in place of `optDouble`/`getDouble` for anything that becomes a number the engine uses.
 */
internal fun JSONObject.finiteDouble(
    name: String,
    fallback: Double,
): Double {
    val v = optDouble(name, fallback)
    return if (v.isFinite()) v else fallback
}

/**
 * The required-field form, mirroring `getDouble`: absent still throws, and now so does a present
 * but non-finite value — a malformed document, which the callers already quarantine.
 */
internal fun JSONObject.finiteDouble(name: String): Double {
    val v = getDouble(name)
    if (!v.isFinite()) throw org.json.JSONException("JSONObject[\"$name\"] is not a finite number")
    return v
}
