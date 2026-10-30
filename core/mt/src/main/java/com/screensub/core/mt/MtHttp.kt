// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.mt

import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

/**
 * 翻译引擎共用的 HTTP 连接池。
 *
 * ## 为什么必须共享
 *
 * 每个翻译器原本各自 `OkHttpClient.Builder().build()`。而**翻译链是会被重建的**
 * （测试翻译页每次都新建一条链），一重建就是全新的 client、空的连接池，
 * 于是每句翻译都要重新走 DNS → TCP → TLS，光握手动辄三四百毫秒。
 *
 * 实测阿里云翻一句英语 743ms，其中真正花在翻译上的只有一两百毫秒，
 * 剩下全在握手——测出来的数字反映的是"建连速度"，不是"引擎速度"。
 *
 * `base.newBuilder()` 会**复用同一个 ConnectionPool 和 Dispatcher**，
 * 所以各引擎仍然可以有各自的超时，但连接是共享的：链重建后连接还在池子里。
 *
 * ## 为什么不用一个 client 配到底
 *
 * 各引擎的超时要求差得远：Azure 走内网 800ms 就够，MyMemory 是公共免费接口
 * 得给 2.5s，LLM 生成式输出要 5s 以上。统一超时只会让快的等慢的。
 */
internal object MtHttp {

    /**
     * 连接池容量 8：同时用到的主机只有 4~6 个（阿里云、火山、谷歌、MyMemory、
     * LLM、Azure），8 足够覆盖，且不会因为闲置连接占太多内存。
     * keepAlive 5 分钟——比厂商网关的 idle 超时短，避免拿到已被对端关掉的连接。
     */
    private val base: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .build()

    /**
     * 基于共享连接池派生一个 client。
     *
     * @param connectMs 建连超时。国内服务 1~2s 足够；跨境接口适当放大
     * @param readMs 读超时。翻译是短请求，3~5s；LLM 生成要更长
     * @param dns 需要绕开系统 DNS 的引擎（如谷歌）传自己的实现
     */
    fun newClient(
        connectMs: Long = 2_000L,
        readMs: Long = 5_000L,
        writeMs: Long = 0L,
        dns: Dns? = null,
        retryOnConnectionFailure: Boolean = false,
        http1Only: Boolean = false,
    ): OkHttpClient = base.newBuilder()
        .connectTimeout(connectMs, TimeUnit.MILLISECONDS)
        .readTimeout(readMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(retryOnConnectionFailure)
        .apply {
            if (writeMs > 0) writeTimeout(writeMs, TimeUnit.MILLISECONDS)
            if (dns != null) dns(dns)
            // 火山 V4 签名里含 host 头，HTTP/2 没有 host（用 :authority），必须锁 1.1
            if (http1Only) protocols(listOf(Protocol.HTTP_1_1))
        }
        .build()
}
