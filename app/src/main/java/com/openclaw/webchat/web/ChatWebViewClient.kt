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
                    // Fix blank screen: body/shell have height=0px — use fixed positioning to force viewport fill
                    wv.evaluateJavascript(
                        "(function(){var m=document.createElement('meta');m.name='viewport';m.content='width=device-width,initial-scale=1.0,maximum-scale=1.0,user-scalable=no';document.head.appendChild(m);var s=document.createElement('style');s.textContent='html,body{position:fixed!important;top:0!important;left:0!important;width:100%!important;height:100%!important;margin:0!important;padding:0!important;overflow:hidden!important;display:block!important}.shell,.shell--chat{position:fixed!important;top:0!important;left:0!important;width:100%!important;height:100%!important;min-height:100vh!important;overflow:hidden!important}';document.head.appendChild(s);})();",
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
