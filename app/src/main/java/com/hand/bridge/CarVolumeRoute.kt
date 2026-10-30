package com.hand.bridge

import android.content.Context
import android.media.MediaRouter

/**
 * Strict no-permission probe for the actual externally selected Android audio route.
 *
 * Android Auto normally carries audio over its own transport, NOT as an externally
 * adjustable Android MediaRouter route. If the selected route is local or fixed,
 * this class intentionally refuses to touch Pixel volume.
 *
 * A successful API request means only "Android accepted a request", NOT that the
 * BMW amplifier's volume changed. The user must verify the car's actual level.
 */
@Suppress("DEPRECATION")
object CarVolumeRoute {
    @Volatile var lastResult: String = "Not tested"
        private set
    @Volatile var lastRoute: String = "Not checked"
        private set

    private fun router(ctx: Context): MediaRouter =
        ctx.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter

    private fun route(ctx: Context): MediaRouter.RouteInfo =
        router(ctx).getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)

    private fun name(ctx: Context, r: MediaRouter.RouteInfo) =
        r.getName(ctx).toString().ifBlank { "(unnamed)" }

    private fun isVehicleName(s: String): Boolean =
        listOf("bmw", "idrive", "android auto", "x5", "car", "vehicle")
            .any { s.contains(it, ignoreCase = true) }

    fun describe(ctx: Context): String = try {
        val r = route(ctx)
        val title = name(ctx, r)
        val remote = r.playbackType == MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE
        val variable = r.volumeHandling == MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE
        val car = isVehicleName(title)
        val supported = remote && variable && car
        val kind = if (remote) "remote" else "local"
        val control = if (variable) "variable" else "fixed"
        lastRoute = "$title · $kind · $control · ${r.volume}/${r.volumeMax}"
        if (supported) "Potential BMW route: $lastRoute; needs physical confirmation"
        else "No verified BMW volume route. Selected: $lastRoute"
    } catch (e: Exception) {
        lastRoute = "${e.javaClass.simpleName}: ${e.message ?: "no details"}"
        "Android route inspection unavailable: $lastRoute"
    }

    fun adjust(ctx: Context, direction: Int): Boolean {
        try {
            val r = route(ctx)
            val title = name(ctx, r)
            val remote = r.playbackType == MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE
            val variable = r.volumeHandling == MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE
            if (!remote || !variable || !isVehicleName(title)) {
                lastResult = "Not sent: selected audio route is not an adjustable BMW route. " +
                    "(name=$title, remote=$remote, variable=$variable)"
                Trace.add("car volume route: $lastResult")
                return false
            }
            // Request in the current car/audio route only. Never change Pixel STREAM_MUSIC.
            // Android does not return confirmation that the car accepted this command.
            r.requestUpdateVolume(if (direction >= 0) 1 else -1)
            lastResult = "Requested ${if (direction >= 0) "up" else "down"} on $title. " +
                "Verify BMW's actual volume changed; no head-unit acknowledgement is provided."
            Trace.add("car volume route: $lastResult")
            return true
        } catch (e: Exception) {
            lastResult = "Remote volume request unavailable: ${e.javaClass.simpleName}: ${e.message ?: "no details"}"
            Trace.add("car volume route: $lastResult")
            return false
        }
    }

    fun allRoutes(ctx: Context): String = try {
        val r = router(ctx)
        (0 until r.routeCount).joinToString(" | ") { i ->
            val route = r.getRouteAt(i)
            "${name(ctx, route)}: ${if (route.playbackType == MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE) "remote" else "local"}, " +
                "${if (route.volumeHandling == MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE) "variable" else "fixed"}"
        }.ifEmpty { "(no routes)" }
    } catch (e: Exception) { "${e.javaClass.simpleName}: ${e.message ?: ""}" }
}
