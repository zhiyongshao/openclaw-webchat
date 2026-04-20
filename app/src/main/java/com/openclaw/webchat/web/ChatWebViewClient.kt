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
                wv.postDelayed({
                    wv.evaluateJavascript(
                        "(function(){" +
                        "try{" +
                        "var s=document.createElement('style');" +
                        "s.id='oc-mobile';" +
                        "s.textContent='*{font-size:max(16px,1.2vw)!important;box-sizing:border-box}" +
                        "html,body{-webkit-text-size-adjust:100%;overflow-x:hidden}" +
                        "input,textarea{font-size:18px!important;padding:12px!important}" +
                        "button{min-height:48px!important;padding:12px 24px!important}" +
                        "img,video{max-width:100%!important;height:auto!important}" +
                        "iframe{border:none}';" +
                        "if(!document.getElementById('oc-mobile'))document.head.appendChild(s);" +
                        "var t=document.title||'';" +
                        "var b=document.body?(document.body.innerHTML.length+'chars'):'no-body';" +
                        "window.OpenClawApp&&window.OpenClawApp.log('Ready:'+t+' body:'+b);" +
                        "window.dispatchEvent(new CustomEvent('oc-ready'));" +
                        "}catch(e){window.OpenClawApp&&window.OpenClawApp.log('Err:'+e.message);}" +
                        "})();",
                        null
                    )
                }, 1500)
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
