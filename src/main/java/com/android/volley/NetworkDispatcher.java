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

package com.android.volley;

import android.annotation.TargetApi;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;

import java.util.concurrent.BlockingQueue;

/**
 * Provides a thread for performing network dispatch from a queue of requests.
 *
 * Requests added to the specified queue are processed from the network via a
 * specified {@link Network} interface. Responses are committed to cache, if
 * eligible, using a specified {@link Cache} interface. Valid responses and
 * errors are posted back to the caller via a {@link ResponseDelivery}.
 */
public class NetworkDispatcher extends Thread {
    /**
     * 网络队列, 不会自己创建, 来源于{@link RequestQueue#start()}, 与{@link CacheDispatcher}共享
     * The queue of requests to service. */
    private final BlockingQueue<Request<?>> mQueue;
    /**
     * 网络处理器, 不会自己创建, 来源于{@link RequestQueue#start()},
     * The network interface for processing requests. */
    private final Network mNetwork;
    /**
     * 缓存机制, 不会自己创建, 来源于{@link RequestQueue#start()}, 与{@link CacheDispatcher}共享
     * The cache to write to. */
    private final Cache mCache;
    /**
     * 分发机制, 不会自己创建, 来源于{@link RequestQueue#start()}, 与{@link CacheDispatcher}共享
     * For posting responses and errors. */
    private final ResponseDelivery mDelivery;
    /**
     * 当线程异常的时候, 决定是继续循环还是退出线程
     * Used for telling us to die. */
    private volatile boolean mQuit = false;

    /**
     * Creates a new network dispatcher thread.  You must call {@link #start()}
     * in order to begin processing.
     *
     * @param queue Queue of incoming requests for triage
     * @param network Network interface to use for performing requests
     * @param cache Cache interface to use for writing responses to cache
     * @param delivery Delivery interface to use for posting responses
     */
    public NetworkDispatcher(BlockingQueue<Request<?>> queue,
            Network network, Cache cache,
            ResponseDelivery delivery) {
        mQueue = queue;
        mNetwork = network;
        mCache = cache;
        mDelivery = delivery;
    }

    /**
     * Forces this dispatcher to quit immediately.  If any requests are still in
     * the queue, they are not guaranteed to be processed.
     */
    public void quit() {
        mQuit = true;
        /**
         * 干净地退出线程
         * 会在{@link #run()}中触发{@link InterruptedException}
         * */
        interrupt();
    }

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    private void addTrafficStatsTag(Request<?> request) {
        // Tag the request (if API >= 14)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            TrafficStats.setThreadStatsTag(request.getTrafficStatsTag());
        }
    }

    /**
     * 当{@link RequestQueue#start()} 调用了{@link #start()}的时候就会执行*/
    @Override
    public void run() {
        // 明确定义该线程的优先级, 默认值是0, 这里设置为10, 优先级是比默认低的
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        while (true) {
            // 系统时间
            long startTimeMs = SystemClock.elapsedRealtime();
            Request<?> request;
            try {
                // Take a request from the queue.
                // 该队列的特性, 如果获取不到值就会卡在这里
                // TODO: 16/5/15 1. 为什么只在这里捕抓InterruptedException; 2. 该队列是线程安全的吗?
                request = mQueue.take();
            } catch (InterruptedException e) {
                // We may have been interrupted because it was time to quit.
                /** 线程被打断
                 * 1. 其他原因被打断, 忽略, 继续下一个循环
                 * 2. 被{@link #quit()}中的{@link #interrupt()}打断即退出该线程 */
                if (mQuit) {
                    return;
                }
                continue;
            }

            try {
                /** 标记该请求已经从网络队列中取出 */
                request.addMarker("network-queue-take");

                // If the request was cancelled already, do not perform the
                // network request.
                /**
                 * 取出来之后判断一次有没有被取消 */
                if (request.isCanceled()) {
                    /** 标记该网络队列被取消 */
                    request.finish("network-discard-cancelled");
                    continue;
                }
                // TODO: 16/5/15 什么作用?
                addTrafficStatsTag(request);

                // Perform the network request.
                /** 这里会发生网络请求, 耗时操作 */
                NetworkResponse networkResponse = mNetwork.performRequest(request);
                /** 没有异常即标记该请求顺利返回 */
                request.addMarker("network-http-complete");

                // If the server returned 304 AND we delivered a response already,
                // we're done -- don't deliver a second identical response.
                /** 有可能是缓存刷新的请求, 此时缓存请求已经返回了响应, 所以在这里就有可能出现
                 * {@link Request#hasHadResponseDelivered()}为true的情况
                 * {@link NetworkResponse#notModified}如果响应返回码是304时为true, 在响应返回之后封装进NetworkResponse的时候赋值 */
                if (networkResponse.notModified && request.hasHadResponseDelivered()) {
                    request.finish("not-modified");
                    continue;
                }

                // Parse the response here on the worker thread.
                /** 解析响应结果, 把{@link NetworkResponse}转化成{@link Response} */
                Response<?> response = request.parseNetworkResponse(networkResponse);
                /** 标记该请求响应解析完毕 */
                request.addMarker("network-parse-complete");

                // Write to cache if applicable.
                // TODO: Only update cache metadata instead of entire record for 304s.
                /**
                 * response.cacheEntry != null 注意是判断响应的缓存是否为空, 创建{@link Response}时必须传进来,
                 * 默认使用{@link com.android.volley.toolbox.HttpHeaderParser#parseCacheHeaders(NetworkResponse)}获取cacheEntry */
                if (request.shouldCache() && response.cacheEntry != null) {
                    // 写入缓存
                    mCache.put(request.getCacheKey(), response.cacheEntry);
                    /** 标记该请求的缓存写入完毕 */
                    request.addMarker("network-cache-written");
                }

                // Post the response back.
                // TODO: 16/5/15 为什么这里要标记? 这里应该是多余的, 因为在postResponse里面也会标记
                request.markDelivered();
                mDelivery.postResponse(request, response);
            } catch (VolleyError volleyError) {
                // TODO: 16/5/15 什么情况下抛出VolleyError?
                /**
                 * VolleyError都是从{@link com.android.volley.toolbox.BasicNetwork#performRequest(Request)}抛出来的
                 * 单独处理VolleyError */
                // 记录网络请求开始到异常退出的时间差
                volleyError.setNetworkTimeMs(SystemClock.elapsedRealtime() - startTimeMs);
                parseAndDeliverNetworkError(request, volleyError);
            } catch (Exception e) {
                VolleyLog.e(e, "Unhandled exception %s", e.toString());
                VolleyError volleyError = new VolleyError(e);
                volleyError.setNetworkTimeMs(SystemClock.elapsedRealtime() - startTimeMs);
                mDelivery.postError(request, volleyError);
            }
        }
    }

    private void parseAndDeliverNetworkError(Request<?> request, VolleyError error) {
        /** 留一个机会重定义网络错误 */
        error = request.parseNetworkError(error);
        // 分发错误
        mDelivery.postError(request, error);
    }
}
