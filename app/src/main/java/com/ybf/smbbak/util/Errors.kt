package com.ybf.smbbak.util

import android.content.Context
import com.ybf.smbbak.R
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 把底层网络/SMB 异常翻译成对用户友好的中文提示 */
object Errors {

    fun connect(context: Context, t: Throwable, host: String, port: Int): String {
        val msg = t.message ?: ""
        return when {
            t is UnknownHostException ->
                context.getString(R.string.err_connect_host, host)
            t is SocketTimeoutException || msg.contains("timed out", true) ->
                context.getString(R.string.err_connect_timeout)
            t is ConnectException ->
                context.getString(R.string.err_connect_refused, host, port)
            msg.contains("STATUS_LOGON_FAILURE") || msg.contains("0xc000006d") ->
                context.getString(R.string.err_login_failed)
            msg.contains("STATUS_BAD_NETWORK_NAME") || msg.contains("0xc00000cc") ->
                context.getString(R.string.err_share_not_found, host)
            msg.contains("STATUS_ACCESS_DENIED") || msg.contains("0xc0000022") ->
                context.getString(R.string.err_access_denied)
            else -> context.getString(R.string.err_generic, short(t))
        }
    }

    fun generic(t: Throwable): String = short(t)

    private fun short(t: Throwable): String {
        val m = (t.message ?: "").trim().take(220)
        if (m.isNotEmpty()) return m
        return t.javaClass.simpleName
    }
}
