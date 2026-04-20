package com.openclaw.webchat.web

import android.util.Log
import android.webkit.*

class ChatWebViewClient(private val callback: ChatWebViewCallback) {

    companion object {
        private const val TAG = "ChatWebViewClient"
    }

    val webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            Log.d(TAG, "Page started: $url")
            callback.onPageStarted(url ?: "")
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            Log.d(TAG, "Page finished: $url")

            view?.let { wv ->
                // Wait for JS to fully render, then check content
                wv.postDelayed({
                    wv.evaluateJavascript(
                        "(function(){" +
                        "try{" +
                        // Capture any JS errors\n"                        "window.onerror = function(msg,src,line){" +
                        "window.OpenClawApp&&window.OpenClawApp.onJsError(msg+' at '+src+':'+line);" +
                        "return false;" +
                        "};" +
                        // Check DOM state\n"                        "var body = document.body;" +
                        "var children = body ? body.children.length : 0;" +
                        "var innerHTML = body ? body.innerHTML.length : 0;" +
                        "var title = document.title || 'no-title';" +
                        "window.OpenClawApp&&window.OpenClawApp.log('DOM: children='+children+' htmlLen='+innerHTML+' title='+title);" +
                        "}catch(e){window.OpenClawApp&&window.OpenClawApp.onJsError(e.message);}" +
                        "})();",
                        null
                    )
                }, 2000)
            }

            callback.onPageLoaded(true)
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            return false
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            if (request?.isForMainFrame == true) {
                val msg = error?.description?.toString() ?: "Unknown error"
                Log.e(TAG, "WebView error: $msg")
                callback.onError(msg)
            }
        }
    }
}
