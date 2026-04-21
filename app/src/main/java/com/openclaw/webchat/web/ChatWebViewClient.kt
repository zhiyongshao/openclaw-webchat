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
                // Wait for JS + CSS to fully render (increased delay)
                wv.postDelayed({
                    // Fix blank screen: body/shell have height=0px — inject viewport height CSS
                    wv.evaluateJavascript(
                        "(function(){var s=document.createElement('style');s.textContent='html,body,.shell,.shell--chat{height:100%!important;min-height:100vh!important;overflow:hidden!important}';document.head.appendChild(s);})();",
                        null
                    )
                    wv.evaluateJavascript(
                        "(function(){" +
                        "var errHandler = function(msg,src,line){" +
                        "try{if(window.OpenClawApp&&window.OpenClawApp.onJsError){window.OpenClawApp.onJsError(msg+' at '+src+':'+line);}}catch(e){} return false;};" +
                        "if(window.onerror){var prev=window.onerror;window.onerror=function(m,s,l){errHandler(m,s,l);try{prev(m,s,l);}catch(e){}}}else{window.onerror=errHandler;}" +
                        "var body=document.body;" +
                        "var children=body?body.children.length:0;" +
                        "var innerHTML=body?body.innerHTML.length:0;" +
                        "var title=document.title||'no-title';" +
                        "try{if(window.OpenClawApp&&window.OpenClawApp.log){window.OpenClawApp.log('DOM: children='+children+' htmlLen='+innerHTML+' title='+title);}}catch(e){}" +
                        "})();",
                        null
                    )
                }, 4000)
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
