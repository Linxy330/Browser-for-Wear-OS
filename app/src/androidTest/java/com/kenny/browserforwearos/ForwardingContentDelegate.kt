package com.kenny.browserforwearos

import android.view.PointerIcon
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.SlowScriptResponse
import org.mozilla.geckoview.WebResponse

/**
 * Explicit forwarding for all 18 GeckoView 158 ContentDelegate methods.
 * Kotlin `by` does not generate forwarders for Java default methods; inheriting their defaults
 * would silently discard production events when a test observes only one callback.
 */
internal open class ForwardingContentDelegate(
    private val original: GeckoSession.ContentDelegate,
) : GeckoSession.ContentDelegate {
    override fun onTitleChange(session: GeckoSession, title: String?) = original.onTitleChange(session, title)

    override fun onPreviewImage(session: GeckoSession, previewImageUrl: String) =
        original.onPreviewImage(session, previewImageUrl)

    override fun onFocusRequest(session: GeckoSession) = original.onFocusRequest(session)

    override fun onCloseRequest(session: GeckoSession) = original.onCloseRequest(session)

    override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) = original.onFullScreen(session, fullScreen)

    override fun onMetaViewportFitChange(session: GeckoSession, viewportFit: String) =
        original.onMetaViewportFitChange(session, viewportFit)

    override fun onContextMenu(
        session: GeckoSession, screenX: Int, screenY: Int,
        element: GeckoSession.ContentDelegate.ContextElement,
    ) = original.onContextMenu(session, screenX, screenY, element)

    override fun onExternalResponse(session: GeckoSession, response: WebResponse) =
        original.onExternalResponse(session, response)

    override fun onCrash(session: GeckoSession) = original.onCrash(session)

    override fun onKill(session: GeckoSession) = original.onKill(session)

    override fun onFirstComposite(session: GeckoSession) = original.onFirstComposite(session)

    override fun onFirstContentfulPaint(session: GeckoSession) = original.onFirstContentfulPaint(session)

    override fun onPaintStatusReset(session: GeckoSession) = original.onPaintStatusReset(session)

    override fun onPointerIconChange(session: GeckoSession, icon: PointerIcon) = original.onPointerIconChange(session, icon)

    override fun onWebAppManifest(session: GeckoSession, manifest: JSONObject) = original.onWebAppManifest(session, manifest)

    override fun onSlowScript(session: GeckoSession, scriptFileName: String): GeckoResult<SlowScriptResponse>? =
        original.onSlowScript(session, scriptFileName)

    override fun onShowDynamicToolbar(session: GeckoSession) = original.onShowDynamicToolbar(session)

    override fun onHideDynamicToolbar(session: GeckoSession) = original.onHideDynamicToolbar(session)
}
