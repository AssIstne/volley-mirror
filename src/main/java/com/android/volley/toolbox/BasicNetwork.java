/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.volley.toolbox;

import android.os.SystemClock;
import android.util.Log;

import com.android.volley.AuthFailureError;
import com.android.volley.Cache;
import com.android.volley.Cache.Entry;
import com.android.volley.CacheDispatcher;
import com.android.volley.ClientError;
import com.android.volley.Network;
import com.android.volley.NetworkDispatcher;
import com.android.volley.NetworkError;
import com.android.volley.NetworkResponse;
import com.android.volley.NoConnectionError;
import com.android.volley.Request;
import com.android.volley.RetryPolicy;
import com.android.volley.ServerError;
import com.android.volley.TimeoutError;
import com.android.volley.VolleyError;
import com.android.volley.VolleyLog;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.impl.cookie.DateUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * A network performing Volley requests over an {@link HttpStack}.
 */
public class BasicNetwork implements Network {
    protected static final boolean DEBUG = VolleyLog.DEBUG;

    private static int SLOW_REQUEST_THRESHOLD_MS = 3000;

    private static int DEFAULT_POOL_SIZE = 4096;

    protected final HttpStack mHttpStack;
    // TODO: 16/5/15 什么用途?
    protected final ByteArrayPool mPool;

    /**
     * @param httpStack HTTP stack to be used
     */
    public BasicNetwork(HttpStack httpStack) {
        // If a pool isn't passed in, then build a small default pool that will give us a lot of
        // benefit and not use too much memory.
        this(httpStack, new ByteArrayPool(DEFAULT_POOL_SIZE));
    }

    /**
     * @param httpStack HTTP stack to be used
     * @param pool a buffer pool that improves GC performance in copy operations
     */
    public BasicNetwork(HttpStack httpStack, ByteArrayPool pool) {
        mHttpStack = httpStack;
        mPool = pool;
    }

    /** 会在{@link com.android.volley.NetworkDispatcher#run()}里面被调用, 负责处理请求
     * 会把原生的{@link HttpResponse}转化成{@link NetworkResponse},
     * 之后又会在{@link Request#parseNetworkResponse(NetworkResponse)}中转化为{@link com.android.volley.Response}*/
    @Override
    public NetworkResponse performRequest(Request<?> request) throws VolleyError {
        // 用来计算请求消耗的时间的
        long requestStart = SystemClock.elapsedRealtime();
        /** 该方法是在worker thread中被调用, 因此无限循环不会阻塞UI */
        while (true) {
            HttpResponse httpResponse = null;
            byte[] responseContents = null;
            Map<String, String> responseHeaders = Collections.emptyMap();
            try {
                // Gather headers.
                Map<String, String> headers = new HashMap<String, String>();
                // 缓存的信息
                addCacheHeaders(headers, request.getCacheEntry());
                /** 通过{@link HttpStack}发起网络请求, 这里是实际发起请求的地方, 耗时操作 */
                httpResponse = mHttpStack.performRequest(request, headers);
                StatusLine statusLine = httpResponse.getStatusLine();
                int statusCode = statusLine.getStatusCode();
                // 转换响应的头部信息
                responseHeaders = convertHeaders(httpResponse.getAllHeaders());
                // Handle cache validation.
                /**
                 * {@link HttpStatus#SC_NOT_MODIFIED} = 304
                 * 如果客户端发送的是一个条件验证(Conditional Validation)请求,
                 * 则web服务器可能会返回HTTP/304响应,这就表明了客户端中所请求资源的缓存仍然是有效的
                 * 条件验证请求: 客户端提供给服务器一个If-Modified-Since请求头,其值为服务器上次返回的Last-Modified响应头中的日期值,
                 * 还需要提供一个If-None-Match请求头,值为服务器上次返回的ETag响应头的值
                 * 这些头部信息应该会在{@link #addCacheHeaders(Map, Entry)}中添加入Request的头部信息中
                 * */
                if (statusCode == HttpStatus.SC_NOT_MODIFIED) {
                    /**
                     * 此时服务器返回的body应该是没有数据的, 因此直接返回缓存实例
                     * 这个实例是在{@link CacheDispatcher#run()}中获取到缓存时放入Request的 */
                    Entry entry = request.getCacheEntry();
                    // 正常情况下, 返回304的时候, 缓存不应该为null
                    if (entry == null) {
                        return new NetworkResponse(HttpStatus.SC_NOT_MODIFIED, null,
                                responseHeaders, true,
                                SystemClock.elapsedRealtime() - requestStart);
                    }

                    // A HTTP 304 response does not have all header fields. We
                    // have to use the header fields from the cache entry plus
                    // the new ones from the response.
                    // http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html#sec10.3.5
                    // 更新缓存的头部信息
                    entry.responseHeaders.putAll(responseHeaders);
                    return new NetworkResponse(HttpStatus.SC_NOT_MODIFIED, entry.data,
                            entry.responseHeaders, true,
                            SystemClock.elapsedRealtime() - requestStart);
                }

                // Some responses such as 204s do not have content.  We must check.
                if (httpResponse.getEntity() != null) {
                  // TODO: 16/5/15 为什么要转成字节码?
                  responseContents = entityToBytes(httpResponse.getEntity());
                } else {
                  // Add 0 byte response as a way of honestly representing a
                  // no-content request.
                  responseContents = new byte[0];
                }

                // if the request is slow, log it.
                long requestLifetime = SystemClock.elapsedRealtime() - requestStart;
                // 如果请求耗时超过3s, 记录下来
                logSlowRequests(requestLifetime, request, responseContents, statusLine);
                /** 返回码小于200, 大于299抛出异常
                 * 1xx是临时返回, 仅包含信息(Informational)
                 * 3xx是重定向(Redirection), 提示需要进一步处理, 304Not Modified特殊处理了
                 * 4xx是客户端错误(Client Error)
                 * 5xx是服务器错误(Server Error)*/
                if (statusCode < 200 || statusCode > 299) {
                    throw new IOException();
                }
                return new NetworkResponse(statusCode, responseContents, responseHeaders, false,
                        SystemClock.elapsedRealtime() - requestStart);
            } catch (SocketTimeoutException e) {
                /** 超时时尝试重试, 因为这里是无限循环, 所以会再发起请求, 默认是发起一次 */
                attemptRetryOnException("socket", request, new TimeoutError());
            } catch (ConnectTimeoutException e) {
                // 超时时尝试重试
                attemptRetryOnException("connection", request, new TimeoutError());
            } catch (MalformedURLException e) {
                /**
                 * URL解析失败的时候抛出
                 * 运行时异常, 会报错退出循环, 但不会退出应用程序, 因为在{@link NetworkDispatcher#run()}里面会捕抓这个异常 */
                throw new RuntimeException("Bad URL " + request.getUrl(), e);
            } catch (IOException e) {
                // TODO: 16/5/15 什么情况下回抛出这个异常
                /**
                 * 1. {@link #entityToBytes(HttpEntity)}
                 * 2. 返回码小于200, 大于299
                 * 3. {@link HttpStack#performRequest(Request, Map)}当{@link com.android.volley.toolbox.HurlStack.UrlRewriter#rewriteUrl(String)}
                 * 返回的的url为空时会抛出 */
                int statusCode;
                if (httpResponse != null) {
                    statusCode = httpResponse.getStatusLine().getStatusCode();
                } else {
                    /** 如果为空, 证明在响应返回前就报错了, 直接中止循环, 可以当做是网络原因 */
                    throw new NoConnectionError(e);
                }
                VolleyLog.e("Unexpected response code %d for %s", statusCode, request.getUrl());
                NetworkResponse networkResponse;
                /**
                 * 不为空证明这个异常不是{@link #entityToBytes(HttpEntity)}抛出的 */
                if (responseContents != null) {
                    networkResponse = new NetworkResponse(statusCode, responseContents,
                            responseHeaders, false, SystemClock.elapsedRealtime() - requestStart);
                    /** 401, 403就重试, 4xx的其他错误就不重试, 5xx的根据{@link Request#mShouldRetryServerErrors}决定 */
                    if (statusCode == HttpStatus.SC_UNAUTHORIZED ||
                            statusCode == HttpStatus.SC_FORBIDDEN) {
                        attemptRetryOnException("auth",
                                request, new AuthFailureError(networkResponse));
                    } else if (statusCode >= 400 && statusCode <= 499) {
                        // Don't retry other client errors.
                        throw new ClientError(networkResponse);
                    } else if (statusCode >= 500 && statusCode <= 599) {
                        if (request.shouldRetryServerErrors()) {
                            attemptRetryOnException("server",
                                    request, new ServerError(networkResponse));
                        } else {
                            throw new ServerError(networkResponse);
                        }
                    } else {
                        /**
                         * 前面已经处理了304, 其他3xx不管 */
                        // 3xx? No reason to retry.
                        throw new ServerError(networkResponse);
                    }
                } else {
                    /**
                     * {@link #entityToBytes(HttpEntity)}抛出异常, 重试
                     * 还有作用可以故意抛出异常来重试, 例如301/302重定向, 修改url后抛出异常就可以重新请求
                     * */
                    attemptRetryOnException("network", request, new NetworkError());
                }
            }
        }
    }

    /**
     * Logs requests that took over SLOW_REQUEST_THRESHOLD_MS to complete.
     */
    private void logSlowRequests(long requestLifetime, Request<?> request,
            byte[] responseContents, StatusLine statusLine) {
        if (DEBUG || requestLifetime > SLOW_REQUEST_THRESHOLD_MS) {
            VolleyLog.d("HTTP response for request=<%s> [lifetime=%d], [size=%s], " +
                    "[rc=%d], [retryCount=%s]", request, requestLifetime,
                    responseContents != null ? responseContents.length : "null",
                    statusLine.getStatusCode(), request.getRetryPolicy().getCurrentRetryCount());
        }
    }

    /**
     * Attempts to prepare the request for a retry. If there are no more attempts remaining in the
     * request's retry policy, a timeout exception is thrown.
     * @param request The request to use.
     */
    private static void attemptRetryOnException(String logPrefix, Request<?> request,
            VolleyError exception) throws VolleyError {
        RetryPolicy retryPolicy = request.getRetryPolicy();
        int oldTimeout = request.getTimeoutMs();

        try {
            /** 在默认的{@link com.android.volley.DefaultRetryPolicy}中, 会将超时时间推迟*/
            retryPolicy.retry(exception);
        } catch (VolleyError e) {
            /** 重试次数超出最大次数的时候就运行到这里, 这里异常就是传进来的异常 */
            request.addMarker(
                    String.format("%s-timeout-giveup [timeout=%s]", logPrefix, oldTimeout));
            /** 这里抛出异常会令到循环中止, 该异常会在{@link NetworkDispatcher#run()}中被捕抓 */
            throw e;
        }
        request.addMarker(String.format("%s-retry [timeout=%s]", logPrefix, oldTimeout));
    }

    /**
     * 将缓存的一些信息放入请求头部, 如果缓存存在, 并且信息完整就会将当前请求变为条件验证请求(Conditional Validation), 有可能返回304
     * 在进行条件请求时,客户端会提供给服务器一个If-Modified-Since请求头, 其值为服务器上次返回的Last-Modified响应头中的日期值,
     * 还会提供一个If-None-Match请求头, 值为服务器上次返回的ETag响应头的值
     * */
    private void addCacheHeaders(Map<String, String> headers, Cache.Entry entry) {
        // If there's no cache entry, we're done.
        if (entry == null) {
            return;
        }
        if (entry.etag != null) {
            headers.put("If-None-Match", entry.etag);
        }

        if (entry.lastModified > 0) {
            Date refTime = new Date(entry.lastModified);
            headers.put("If-Modified-Since", DateUtils.formatDate(refTime));
        }
    }

    protected void logError(String what, String url, long start) {
        long now = SystemClock.elapsedRealtime();
        VolleyLog.v("HTTP ERROR(%s) %d ms to fetch %s", what, (now - start), url);
    }

    /** Reads the contents of HttpEntity into a byte[]. */
    private byte[] entityToBytes(HttpEntity entity) throws IOException, ServerError {
        PoolingByteArrayOutputStream bytes =
                new PoolingByteArrayOutputStream(mPool, (int) entity.getContentLength());
        byte[] buffer = null;
        try {
            InputStream in = entity.getContent();
            if (in == null) {
                throw new ServerError();
            }
            buffer = mPool.getBuf(1024);
            int count;
            while ((count = in.read(buffer)) != -1) {
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        } finally {
            try {
                // Close the InputStream and release the resources by "consuming the content".
                entity.consumeContent();
            } catch (IOException e) {
                // This can happen if there was an exception above that left the entity in
                // an invalid state.
                VolleyLog.v("Error occured when calling consumingContent");
            }
            mPool.returnBuf(buffer);
            bytes.close();
        }
    }

    /**
     * Converts Headers[] to Map<String, String>.
     */
    protected static Map<String, String> convertHeaders(Header[] headers) {
        Map<String, String> result = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 0; i < headers.length; i++) {
            result.put(headers[i].getName(), headers[i].getValue());
        }
        return result;
    }
}
