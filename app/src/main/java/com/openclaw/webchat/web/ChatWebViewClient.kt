package com.openclaw.webchat.web

import android.graphics.Bitmap
import android.util.Log
import android.webkit.*
import android.widget.Toast

class ChatWebViewClient(
    private val onPageLoaded: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val onTokenNeeded: () -> Unit
) : WebViewClient() {

    companion object {
        private const val TAG = "ChatWebViewClient"
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url)
        Log.d(TAG, "Page started: $url")
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        Log.d(TAG, "Page finished: $url")

        // Inject CSS + JS to optimize for mobile
        view?.evaluateJavascript("""
            (function() {
                // Inject mobile optimization styles
                var style = document.createElement('style');
                style.textContent = `
                    * { 
                        font-size: max(16px, 1.2vw) !important; 
                        box-sizing: border-box;
                    }
                    html, body {
                        -webkit-text-size-adjust: 100%;
                        overflow-x: hidden;
                    }
                    input, textarea { 
                        font-size: 18px !important; 
                        padding: 12px !important;
                        border-radius: 8px !important;
                    }
                    button { 
                        min-height: 48px !important;
                        min-width: 48px !important;
                        font-size: 16px !important;
                        padding: 12px 24px !important;
                        border-radius: 8px !important;
                        touch-action: manipulation !important;
                    }
                    img, video { 
                        max-width: 100% !important; 
                        height: auto !important; 
                    }
                    iframe { border: none; }
                    /* Prevent horizontal scroll */
                    body { overflow-x: hidden; width: 100vw !important; }
                    /* Improve touch targets */
                    a, button, [role="button"] {
                        min-height: 44px !important;
                        min-width: 44px !important;
                    }
                `;
                document.head.appendChild(style);

                // Dark mode handling
                if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
                    document.documentElement.setAttribute('data-theme', 'dark');
                }

                // Check if this looks like a login page (no chat content)
                var hasChat = document.querySelector('[data-role="chat"], .chat, #chat, [class*="message"]');
                if (!hasChat && (document.title === '' || document.title === 'Login' || document.title === 'Sign In')) {
                    window.OpenClawApp && window.OpenClawApp.showTokenPrompt && window.OpenClawApp.showTokenPrompt();
                }

                // Signal page loaded
                window.dispatchEvent(new CustomEvent('webchat-ready'));
            })();
        """, null)

        onPageLoaded(true)
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        Log.d(TAG, "URL loading: $url")
        // Let all URLs load in WebView
        return false
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        if (request?.isForMainFrame == true) {
            val desc = error?.description ?: "Unknown error"
            Log.e(TAG, "WebView error: $desc")
            onError(desc.toString())
        }
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?
    ) {
        if (request?.isForMainFrame == true) {
            val status = errorResponse?.statusCode ?: 0
            Log.e(TAG, "HTTP error: $status")
            if (status == 401 || status == 403) {
                onTokenNeeded()
            }
        }
    }
}
