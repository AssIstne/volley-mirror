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

import android.os.Process;

import java.util.concurrent.BlockingQueue;

/**
 * 处理缓存队列{@link #mCacheQueue}中的请求
 * Provides a thread for performing cache triage on a queue of requests.
 *
 * Requests added to the specified cache queue are resolved from cache.
 * Any deliverable response is posted back to the caller via a
 * {@link ResponseDelivery}.  Cache misses and responses that require
 * refresh are enqueued on the specified network queue for processing
 * by a {@link NetworkDispatcher}.
 */
public class CacheDispatcher extends Thread {

    private static final boolean DEBUG = VolleyLog.DEBUG;

    /**
     * 缓存队列, 不会自己创建, 来源于{@link RequestQueue#start()}
     * The queue of requests coming in for triage. */
    private final BlockingQueue<Request<?>> mCacheQueue;

    /**
     * 网络队列, 不会自己创建, 来源于{@link RequestQueue#start()}, 与{@link NetworkDispatcher}共享
     * 因为获取缓存失败的时候就需要放到网络队列中让网络处理器{@link NetworkDispatcher}处理
     * The queue of requests going out to the network. */
    private final BlockingQueue<Request<?>> mNetworkQueue;

    /**
     * 缓存机制, 不会自己创建, 来源于{@link RequestQueue#start()}, 与{@link NetworkDispatcher}共享
     * The cache to read from. */
    private final Cache mCache;

    /**
     * 分发机制, 不会自己创建, 来源于{@link RequestQueue#start()}, 与{@link NetworkDispatcher}共享
     * For posting responses. */
    private final ResponseDelivery mDelivery;

    /**
     * 当线程异常的时候, 决定是继续循环还是退出线程
     * Used for telling us to die. */
    private volatile boolean mQuit = false;

    /**
     * Creates a new cache triage dispatcher thread.  You must call {@link #start()}
     * in order to begin processing.
     *
     * @param cacheQueue Queue of incoming requests for triage
     * @param networkQueue Queue to post requests that require network to
     * @param cache Cache interface to use for resolution
     * @param delivery Delivery interface to use for posting responses
     */
    public CacheDispatcher(
            BlockingQueue<Request<?>> cacheQueue, BlockingQueue<Request<?>> networkQueue,
            Cache cache, ResponseDelivery delivery) {
        mCacheQueue = cacheQueue;
        mNetworkQueue = networkQueue;
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

    /**
     * 当{@link RequestQueue#start()} 调用了{@link #start()}的时候就会执行*/
    @Override
    public void run() {
        if (DEBUG) VolleyLog.v("start new dispatcher");
        // 明确定义该线程的优先级, 默认值是0, 这里设置为10, 优先级是比默认低的
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        // Make a blocking call to initialize the cache.
        /**
         * 给一个机会给缓存初始化, 可以执行耗时操作*/
        mCache.initialize();

        while (true) {
            try {
                // Get a request from the cache triage queue, blocking until
                // at least one is available.
                // 该队列的特性, 如果获取不到值就会卡在这里
                final Request<?> request = mCacheQueue.take();
                /** 标记该请求从缓存队列中取出来了 */
                request.addMarker("cache-queue-take");

                // If the request has been canceled, don't bother dispatching it.
                /**
                 * 取出来之后判断一次有没有被取消 */
                if (request.isCanceled()) {
                    /** 标记该请求被取消了 */
                    request.finish("cache-discard-canceled");
                    continue;
                }

                // Attempt to retrieve this item from cache.
                /** 直接从缓存里面取 */
                Cache.Entry entry = mCache.get(request.getCacheKey());
                if (entry == null) {
                    /** 标记该请求的缓存不存在 */
                    request.addMarker("cache-miss");
                    // Cache miss; send off to the network dispatcher.
                    /** 放到网络队列中让{@link NetworkDispatcher}处理 */
                    mNetworkQueue.put(request);
                    continue;
                }

                // If it is completely expired, just send it to the network.
                if (entry.isExpired()) {
                    /** 标记该请求缓存存在, 但是已经失效了 */
                    request.addMarker("cache-hit-expired");
                    // 虽然失效还是还是放进请求里面
                    request.setCacheEntry(entry);
                    /** 放到网络队列中让{@link NetworkDispatcher}处理 */
                    mNetworkQueue.put(request);
                    continue;
                }

                // We have a cache hit; parse its data for delivery back to the request.
                /** 缓存存在同时也没过期, 标记找到了该请求对应的缓存 */
                request.addMarker("cache-hit");
                /** 缓存即响应返回内容, 解析响应返回内容 */
                Response<?> response = request.parseNetworkResponse(
                        new NetworkResponse(entry.data, entry.responseHeaders));
                /** 标记该请求响应解析完毕 */
                request.addMarker("cache-hit-parsed");
                /**
                 * 判断是不是需要刷新缓存
                 * {@link com.android.volley.Cache.Entry#softTtl}是由响应的头信息确定的
                 *  */
                if (!entry.refreshNeeded()) {
                    // Completely unexpired cache hit. Just deliver the response.
                    /** 利用分发器分发响应, 就是调用回调接口 */
                    mDelivery.postResponse(request, response);
                } else {
                    // Soft-expired cache hit. We can deliver the cached response,
                    // but we need to also send the request to the network for
                    // refreshing.
                    /** 标记该请求的缓存需要刷新 */
                    request.addMarker("cache-hit-refresh-needed");
                    /**
                     * 设置缓存实例是为了当服务器返回304(提示客户端应该使用缓存, 响应没有返回数据)时, 可以直接从Request获取到缓存实例,
                     * 会在{@link com.android.volley.toolbox.BasicNetwork#performRequest(Request)}中用到 */
                    request.setCacheEntry(entry);

                    // Mark the response as intermediate.
                    // 标记下该响应是缓存响应, 接下来会被刷新, 所以是个临时响应
                    response.intermediate = true;

                    // Post the intermediate response back to the user and have
                    // the delivery then forward the request along to the network.
                    /** 需要刷新就分发的同时进行网络请求 */
                    mDelivery.postResponse(request, response, new Runnable() {
                        @Override
                        public void run() {
                            try {
                                mNetworkQueue.put(request);
                            } catch (InterruptedException e) {
                                // Not much we can do about this.
                            }
                        }
                    });
                }

            } catch (InterruptedException e) {
                /** 线程被打断
                 * 1. 其他原因被打断, 忽略, 继续下一个循环
                 * 2. 被{@link #quit()}中的{@link #interrupt()}打断即退出该线程 */
                // We may have been interrupted because it was time to quit.
                if (mQuit) {
                    return;
                }
                continue;
            }
        }
    }
}
