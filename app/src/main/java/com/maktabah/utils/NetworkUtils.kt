package com.maktabah.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

fun Context.isNetworkAvailable(): Boolean {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val activeNetwork = cm.activeNetwork ?: return false
    val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

fun isNetworkError(throwable: Throwable?, context: Context? = null): Boolean {
    if (context != null && !context.isNetworkAvailable()) {
        return true
    }
    var cause = throwable
    while (cause != null) {
        if (cause is UnknownHostException ||
            cause is ConnectException ||
            cause is SocketTimeoutException ||
            cause is SocketException ||
            cause is NoRouteToHostException ||
            cause is SSLException
        ) {
            return true
        }
        val msg = cause.message?.lowercase() ?: ""
        if (msg.contains("unable to resolve host") ||
            msg.contains("no address associated with hostname") ||
            msg.contains("network is unreachable") ||
            msg.contains("connection refused") ||
            msg.contains("timeout") ||
            msg.contains("failed to connect") ||
            msg.contains("network error") ||
            msg.contains("route to host") ||
            msg.contains("software caused connection abort") ||
            msg.contains("broken pipe")
        ) {
            return true
        }
        cause = cause.cause
    }
    return false
}
