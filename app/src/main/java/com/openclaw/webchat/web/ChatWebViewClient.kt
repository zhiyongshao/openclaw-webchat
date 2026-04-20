package com.openclaw.webchat.web

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class ChatWebViewClient(
    private val onPageLoaded: (Boolean) -> Unit
) : WebViewClient() {

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageLoaded(true)

        // Inject CSS to hide browser UI elements and optimize for mobile
        view?.evaluateJavascript("""
            (function() {
                // Hide address bar, toolbars if any
                var style = document.createElement('style');
                style.textContent = `
                    * { font-size: max(16px, 1.2vw) !important; }
                    input, textarea { font-size: 18px !important; }
                    button { min-height: 48px; }
                    img, video { max-width: 100% !important; height: auto !important; }
                    body { overflow-x: hidden; }
                    /* Hide any iframe borders or extra padding */
                    iframe { border: none; }
                `;
                document.head.appendChild(style);

                // Force dark mode if system prefers
                if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
                    document.body.setAttribute('data-theme', 'dark');
                }
            })();
        """, null)
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        // Let WebView handle all URLs
        return false
    }
}
