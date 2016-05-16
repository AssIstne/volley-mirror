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

import android.content.Context;

import com.android.volley.toolbox.HttpStack;

import java.util.Collections;
import java.util.Map;

/**
 * 缓存策略接口, 在{@link com.android.volley.toolbox.Volley#newRequestQueue(Context, HttpStack)}中创建传入
 * {@link RequestQueue}中, 默认使用的是{@link com.android.volley.toolbox.DiskBasedCache}
 * An interface for a cache keyed by a String with a byte array as data.
 */
public interface Cache {
    /**
     * Retrieves an entry from the cache.
     * @param key Cache key
     * @return An {@link Entry} or null in the event of a cache miss
     */
    public Entry get(String key);

    /**
     * Adds or replaces an entry to the cache.
     * @param key Cache key
     * @param entry Data to store and metadata for cache coherency, TTL, etc.
     */
    public void put(String key, Entry entry);

    /**
     * 会在{@link CacheDispatcher#run()}中, 循环开始前调用
     * Performs any potentially long-running actions needed to initialize the cache;
     * will be called from a worker thread.
     */
    public void initialize();

    /**
     * Invalidates an entry in the cache.
     * @param key Cache key
     * @param fullExpire True to fully expire the entry, false to soft expire
     */
    public void invalidate(String key, boolean fullExpire);

    /**
     * Removes an entry from the cache.
     * @param key Cache key
     */
    public void remove(String key);

    /**
     * Empties the cache.
     */
    public void clear();

    /**
     * 返回响应{@link Response#success(Object, Entry)}需要该实例
     * 默认的实现中, 缓存实例的赋值是在{@link com.android.volley.toolbox.HttpHeaderParser#parseCacheHeaders(NetworkResponse)}
     * Data and metadata for an entry returned by the cache.
     */
    public static class Entry {
        /** The data returned from cache. */
        public byte[] data;

        /** ETag for cache coherency. */
        public String etag;

        /** Date of this response as reported by the server. */
        public long serverDate;

        /** The last modified date for the requested object. */
        public long lastModified;

        /**
         * 如果小于当前时间, 缓存不会被返回, 直接发起请求
         * TTL for this record. */
        public long ttl;

        /**

         * 如果小于当前时间, 虽然缓存会被返回但是会发起请求更新缓存
         * Soft TTL for this record. */
        public long softTtl;

        /** Immutable response headers as received from server; must be non-null. */
        public Map<String, String> responseHeaders = Collections.emptyMap();

        /**
         * 缓存过期了, 不应该被使用, 应该重新发起请求
         * True if the entry is expired. */
        public boolean isExpired() {
            return this.ttl < System.currentTimeMillis();
        }

        /**
         * 缓存需要刷新, 可以使用缓存, 但是应该发起请求刷新缓存
         * True if a refresh is needed from the original data source. */
        public boolean refreshNeeded() {
            return this.softTtl < System.currentTimeMillis();
        }
    }

}
