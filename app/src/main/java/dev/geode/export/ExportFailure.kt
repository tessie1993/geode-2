package dev.geode.export

import android.content.Context
import androidx.annotation.StringRes

/**
 * An export failure that already knows how to describe itself to the user.
 *
 * The exporters diagnose their failures precisely — the encoder produced no output, it stalled
 * while flushing, a buffer came back null — and used to raise every one of them through `check` /
 * `checkNotNull`, i.e. as a plain [IllegalStateException]. Nothing downstream could tell those
 * apart from a genuinely malformed project, so all four reached the user as the same
 * "check that its clips still point at files Geode can read" — advice that cannot help, because
 * the project was fine and the codec was not.
 *
 * Carrying the string resource on the exception keeps the diagnosis attached to the throw, and
 * leaves [IllegalArgumentException] free to keep meaning what it says.
 *
 * It extends [IllegalStateException] deliberately: [LoopRender] and [LoopExtend] already catch that
 * type around these same code paths, and they surface `message`, which is resolved here. A handler
 * that wants the localised text re-resolved — after a configuration change, say — calls [describe].
 */
class ExportFailure(
    override val message: String,
    @StringRes val messageRes: Int,
    val messageArgs: List<Any> = emptyList(),
) : IllegalStateException(message) {
    /** Re-resolves [messageRes] against a live [context], for a UI layer that wants localisation. */
    @Suppress("SpreadOperator")
    fun describe(context: Context): String = context.getString(messageRes, *messageArgs.toTypedArray())
}

/** Builds an [ExportFailure] whose [ExportFailure.message] is already resolved from [resId]. */
@Suppress("SpreadOperator")
internal fun Context.exportFailure(
    @StringRes resId: Int,
    vararg args: Any,
): ExportFailure = ExportFailure(getString(resId, *args), resId, args.toList())
