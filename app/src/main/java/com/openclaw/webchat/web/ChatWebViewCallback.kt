package com.openclaw.webchat.web

interface ChatWebViewCallback {
    fun onPageLoaded(success: Boolean)
    fun onError(message: String)
    fun onPageStarted(url: String)
}
