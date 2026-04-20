package com.openclaw.webchat.web

import android.util.Log
import android.webkit.*

class ChatWebViewClient(
    private val onPageLoaded: (Boolean) -> Unit,
    private val onError: ((String) -> Unit)? = null,
    private val onPageStarted: ((String) -> Unit)? = null
) : WebViewClient() {

    companion object {
        private const val TAG = "ChatWebViewClient"
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url)
        Log.d(TAG, "Page started: $url")
        onPageStarted?.invoke(url ?: "")
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        Log.d(TAG, "Page finished: $url")

        view?.let { wv ->
            // Wait a bit for JS to render, then check content
            wv.postDelayed({
                wv.evaluateJavascript("""
                    (function() {
                        try {
                            // Inject mobile-friendly styles
                            var style = document.createElement('style');
                            style.id = 'openclaw-mobile-style';
                            style.textContent = `
                                * { font-size: max(16px, 1.2vw) !important; box-sizing: border-box; }
                                html, body { -webkit-text-size-adjust: 100%; overflow-x: hidden; }
                                input, textarea { font-size: 18px !important; padding: 12px !important; }
                                button { min-height: 48px !important; padding: 12px 24px !important; }
                                img, video { max-width: 100% !important; height: auto !important; }
                                iframe { border: none; }
                            `;
                            if (!document.getElementById('openclaw-mobile-style')) {
                                document.head.appendChild(style);
                            }
                            // Check if page has content
                            var hasContent = document.body && document.body.children.length > 0;
                            var bodyHTML = document.body ? document.body.innerHTML.substring(0, 200) : 'empty';
                            window.dispatchEvent(new CustomEvent('webchat-ready', {detail: {
                                hasContent: hasContent,
                                title: document.title,
                                bodyPreview: bodyHTML
                            }}));
                            window.OpenClawApp && window.OpenClawApp.log('Page ready: ' + document.title + ' body:' + bodyHTML.length + ' chars');
                        } catch(e) {
                            window.OpenClawApp && window.OpenClawApp.log('Error: ' + e.message);
                        }
                    })();
                """, null)

                // Also try re-loading after a brief delay to ensure full render
                setTimeout(function() {
                    wv.dispatchEvent(new Event('resize'));
                }, 500);
            }, 1000)
        }

        onPageLoaded(true)
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
            onError?.invoke(msg)
        }
    }
}
