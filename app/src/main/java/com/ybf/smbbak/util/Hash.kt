package com.ybf.smbbak.util

import java.security.MessageDigest

/** 简单摘要/标识工具 */
object Hash {

    fun sha1Hex(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append("%02x".format(b))
        return sb.toString()
    }

    /** 取 sha1 前 n 位作短标识 */
    fun tag(s: String, n: Int = 10): String = sha1Hex(s).take(n)
}
