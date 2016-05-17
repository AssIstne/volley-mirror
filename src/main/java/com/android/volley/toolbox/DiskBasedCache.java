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

import android.content.Context;
import android.os.SystemClock;

import com.android.volley.Cache;
import com.android.volley.CacheDispatcher;
import com.android.volley.NetworkDispatcher;
import com.android.volley.Request;
import com.android.volley.VolleyLog;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 默认的缓存策略, 使用文件缓存, 保存在 app目录/缓存目录/volley下
 * Cache implementation that caches files directly onto the hard disk in the specified
 * directory. The default disk usage size is 5MB, but is configurable.
 */
public class DiskBasedCache implements Cache {

    /** Map of the Key, CacheHeader pairs */
    private final Map<String, CacheHeader> mEntries =
            new LinkedHashMap<String, CacheHeader>(16, .75f, true);

    /** Total amount of space currently used by the cache in bytes. */
    private long mTotalSize = 0;

    /** The root directory to use for the cache. */
    private final File mRootDirectory;

    /** The maximum size of the cache in bytes. */
    private final int mMaxCacheSizeInBytes;

    /**
     * 默认缓存总大小是5MB
     * Default maximum disk usage in bytes. */
    private static final int DEFAULT_DISK_USAGE_BYTES = 5 * 1024 * 1024;

    /** High water mark percentage for the cache */
    private static final float HYSTERESIS_FACTOR = 0.9f;

    /** Magic number for current version of cache file format. */
    private static final int CACHE_MAGIC = 0x20150306;

    /**
     * 可以选择目录, 和缓存大小
     * Constructs an instance of the DiskBasedCache at the specified directory.
     * @param rootDirectory The root directory of the cache.
     * @param maxCacheSizeInBytes The maximum size of the cache in bytes.
     */
    public DiskBasedCache(File rootDirectory, int maxCacheSizeInBytes) {
        mRootDirectory = rootDirectory;
        mMaxCacheSizeInBytes = maxCacheSizeInBytes;
    }

    /**
     * 默认缓存大小是5MB, 默认的目录是在{@link Volley#newRequestQueue(Context, HttpStack)}中设定的缓存目录下的volley文件夹
     * Constructs an instance of the DiskBasedCache at the specified directory using
     * the default maximum cache size of 5MB.
     * @param rootDirectory The root directory of the cache.
     */
    public DiskBasedCache(File rootDirectory) {
        this(rootDirectory, DEFAULT_DISK_USAGE_BYTES);
    }

    /**
     * 清理缓存, 实际上就是清理volley文件夹
     * Clears the cache. Deletes all cached files from disk.
     */
    @Override
    public synchronized void clear() {
        File[] files = mRootDirectory.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        mEntries.clear();
        mTotalSize = 0;
        VolleyLog.d("Cache cleared.");
    }

    /**
     * Returns the cache entry with the specified key if it exists, null otherwise.
     */
    @Override
    public synchronized Entry get(String key) {
        CacheHeader entry = mEntries.get(key);
        // if the entry does not exist, return.
        if (entry == null) {
            return null;
        }

        File file = getFileForKey(key);
        CountingInputStream cis = null;
        try {
            cis = new CountingInputStream(new BufferedInputStream(new FileInputStream(file)));
            CacheHeader.readHeader(cis); // eat header
            byte[] data = streamToBytes(cis, (int) (file.length() - cis.bytesRead));
            return entry.toCacheEntry(data);
        } catch (IOException e) {
            VolleyLog.d("%s: %s", file.getAbsolutePath(), e.toString());
            remove(key);
            return null;
        } finally {
            if (cis != null) {
                try {
                    cis.close();
                } catch (IOException ioe) {
                    return null;
                }
            }
        }
    }

    /**
     * 初始化缓存, 会在{@link CacheDispatcher#run()}中循环开始前调用
     * Initializes the DiskBasedCache by scanning for all files currently in the
     * specified root directory. Creates the root directory if necessary.
     */
    @Override
    public synchronized void initialize() {
        // 检查下缓存目录创建了没
        /**
         * 如果这里创建失败, 后面所有的缓存操作都会抛出异常了 */
        if (!mRootDirectory.exists()) {
            if (!mRootDirectory.mkdirs()) {
                VolleyLog.e("Unable to create cache dir %s", mRootDirectory.getAbsolutePath());
            }
            return;
        }
        /**
         * 加载缓存, 放到{@link #mEntries}, 记录缓存文件的大小 */
        File[] files = mRootDirectory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            BufferedInputStream fis = null;
            try {
                fis = new BufferedInputStream(new FileInputStream(file));
                CacheHeader entry = CacheHeader.readHeader(fis);
                entry.size = file.length();
                putEntry(entry.key, entry);
            } catch (IOException e) {
                if (file != null) {
                   file.delete();
                }
            } finally {
                try {
                    if (fis != null) {
                        fis.close();
                    }
                } catch (IOException ignored) { }
            }
        }
    }

    /**
     * Invalidates an entry in the cache.
     * @param key Cache key
     * @param fullExpire True to fully expire the entry, false to soft expire
     */
    @Override
    public synchronized void invalidate(String key, boolean fullExpire) {
        Entry entry = get(key);
        if (entry != null) {
            entry.softTtl = 0;
            if (fullExpire) {
                entry.ttl = 0;
            }
            put(key, entry);
        }

    }

    /**
     * 存入缓存, 文件名默认是仅仅根据url来确定的, 可以通过重写{@link Request#getCacheKey()}来修改key
     * Puts the entry with the specified key into the cache.
     * @param key 默认是来自{@link Request#getCacheKey()}, 默认是url, 主要在{@link NetworkDispatcher#run()}中被调用
     * @param entry 默认是来自{@link com.android.volley.Response#cacheEntry}
     */
    @Override
    public synchronized void put(String key, Entry entry) {
        // 如果超出最大值就清理一下缓存
        pruneIfNeeded(entry.data.length);
        // 根据key获取对应的缓存文件
        File file = getFileForKey(key);
        try {
            BufferedOutputStream fos = new BufferedOutputStream(new FileOutputStream(file));
            /**
             * 把Entry转换成CacheHeader, 方便写入文件 */
            CacheHeader e = new CacheHeader(key, entry);
            boolean success = e.writeHeader(fos);
            if (!success) {
                fos.close();
                VolleyLog.d("Failed to write header for %s", file.getAbsolutePath());
                throw new IOException();
            }
            fos.write(entry.data);
            fos.close();
            /**
             * 保存缓存实例 */
            putEntry(key, e);
            return;
        } catch (IOException e) {
        }
        boolean deleted = file.delete();
        if (!deleted) {
            VolleyLog.d("Could not clean up file %s", file.getAbsolutePath());
        }
    }

    /**
     * 删除缓存文件, 移除缓存实例
     * Removes the specified key from the cache if it exists.
     */
    @Override
    public synchronized void remove(String key) {
        boolean deleted = getFileForKey(key).delete();
        removeEntry(key);
        if (!deleted) {
            VolleyLog.d("Could not delete cache entry for key=%s, filename=%s",
                    key, getFilenameForKey(key));
        }
    }

    /**
     * 文件名是key的前半段的hashCode加上后半段的hashCode
     * Creates a pseudo-unique filename for the specified cache key.
     * @param key The key to generate a file name for.
     * @return A pseudo-unique filename.
     */
    private String getFilenameForKey(String key) {
        int firstHalfLength = key.length() / 2;
        String localFilename = String.valueOf(key.substring(0, firstHalfLength).hashCode());
        localFilename += String.valueOf(key.substring(firstHalfLength).hashCode());
        return localFilename;
    }

    /**
     * 根据key获取文件
     * Returns a file object for the given cache key.
     */
    public File getFileForKey(String key) {
        return new File(mRootDirectory, getFilenameForKey(key));
    }

    /**
     * 在写入文件前, 处理缓存空间, 保证缓存空间不超过最大值
     * Prunes the cache to fit the amount of bytes specified.
     * @param neededSpace The amount of bytes we are trying to fit into the cache.
     */
    private void pruneIfNeeded(int neededSpace) {
        // 没超过不用管
        if ((mTotalSize + neededSpace) < mMaxCacheSizeInBytes) {
            return;
        }
        if (VolleyLog.DEBUG) {
            VolleyLog.v("Pruning old cache entries.");
        }

        // 用来计算释放了多少空间, 做日志
        long before = mTotalSize;
        // 记录删掉几个文件
        int prunedFiles = 0;
        // 用来计算释放空间消耗了多少时间
        long startTime = SystemClock.elapsedRealtime();

        Iterator<Map.Entry<String, CacheHeader>> iterator = mEntries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CacheHeader> entry = iterator.next();
            CacheHeader e = entry.getValue();
            boolean deleted = getFileForKey(e.key).delete();
            if (deleted) {
                mTotalSize -= e.size;
            } else {
               VolleyLog.d("Could not delete cache entry for key=%s, filename=%s",
                       e.key, getFilenameForKey(e.key));
            }
            iterator.remove();
            prunedFiles++;
            /** 避免频繁删除文件, 一次性释放部分缓存空间, 默认是保留0.9倍最大值 */
            if ((mTotalSize + neededSpace) < mMaxCacheSizeInBytes * HYSTERESIS_FACTOR) {
                break;
            }
        }

        if (VolleyLog.DEBUG) {
            VolleyLog.v("pruned %d files, %d bytes, %d ms",
                    prunedFiles, (mTotalSize - before), SystemClock.elapsedRealtime() - startTime);
        }
    }

    /**
     * 把缓存实例保存到{@link #mEntries}, 并更新下缓存大小
     * Puts the entry with the specified key into the cache.
     * @param key The key to identify the entry by.
     * @param entry The entry to cache.
     */
    private void putEntry(String key, CacheHeader entry) {
        if (!mEntries.containsKey(key)) {
            mTotalSize += entry.size;
        } else {
            CacheHeader oldEntry = mEntries.get(key);
            mTotalSize += (entry.size - oldEntry.size);
        }
        mEntries.put(key, entry);
    }

    /**
     * Removes the entry identified by 'key' from the cache.
     */
    private void removeEntry(String key) {
        CacheHeader entry = mEntries.get(key);
        if (entry != null) {
            mTotalSize -= entry.size;
            mEntries.remove(key);
        }
    }

    /**
     * Reads the contents of an InputStream into a byte[].
     * */
    private static byte[] streamToBytes(InputStream in, int length) throws IOException {
        byte[] bytes = new byte[length];
        int count;
        int pos = 0;
        while (pos < length && ((count = in.read(bytes, pos, length - pos)) != -1)) {
            pos += count;
        }
        if (pos != length) {
            throw new IOException("Expected " + length + " bytes, read " + pos + " bytes");
        }
        return bytes;
    }

    /**
     * {@link com.android.volley.Cache.Entry}的转换类, 可以直接存入文件中
     * Handles holding onto the cache headers for an entry.
     */
    // Visible for testing.
    static class CacheHeader {
        /** The size of the data identified by this CacheHeader. (This is not
         * serialized to disk. */
        public long size;

        /** The key that identifies the cache entry. */
        public String key;

        /** ETag for cache coherence. */
        public String etag;

        /** Date of this response as reported by the server. */
        public long serverDate;

        /** The last modified date for the requested object. */
        public long lastModified;

        /** TTL for this record. */
        public long ttl;

        /** Soft TTL for this record. */
        public long softTtl;

        /** Headers from the response resulting in this cache entry. */
        public Map<String, String> responseHeaders;

        private CacheHeader() { }

        /**
         * Instantiates a new CacheHeader object
         * @param key The key that identifies the cache entry
         * @param entry The cache entry.
         */
        public CacheHeader(String key, Entry entry) {
            this.key = key;
            this.size = entry.data.length;
            this.etag = entry.etag;
            this.serverDate = entry.serverDate;
            this.lastModified = entry.lastModified;
            this.ttl = entry.ttl;
            this.softTtl = entry.softTtl;
            this.responseHeaders = entry.responseHeaders;
        }

        /**
         * Reads the header off of an InputStream and returns a CacheHeader object.
         * @param is The InputStream to read from.
         * @throws IOException
         */
        public static CacheHeader readHeader(InputStream is) throws IOException {
            CacheHeader entry = new CacheHeader();
            int magic = readInt(is);
            if (magic != CACHE_MAGIC) {
                // don't bother deleting, it'll get pruned eventually
                throw new IOException();
            }
            entry.key = readString(is);
            entry.etag = readString(is);
            if (entry.etag.equals("")) {
                entry.etag = null;
            }
            entry.serverDate = readLong(is);
            entry.lastModified = readLong(is);
            entry.ttl = readLong(is);
            entry.softTtl = readLong(is);
            entry.responseHeaders = readStringStringMap(is);

            return entry;
        }

        /**
         * Creates a cache entry for the specified data.
         */
        public Entry toCacheEntry(byte[] data) {
            Entry e = new Entry();
            e.data = data;
            e.etag = etag;
            e.serverDate = serverDate;
            e.lastModified = lastModified;
            e.ttl = ttl;
            e.softTtl = softTtl;
            e.responseHeaders = responseHeaders;
            return e;
        }


        /**
         * Writes the contents of this CacheHeader to the specified OutputStream.
         */
        public boolean writeHeader(OutputStream os) {
            try {
                writeInt(os, CACHE_MAGIC);
                writeString(os, key);
                writeString(os, etag == null ? "" : etag);
                writeLong(os, serverDate);
                writeLong(os, lastModified);
                writeLong(os, ttl);
                writeLong(os, softTtl);
                writeStringStringMap(responseHeaders, os);
                os.flush();
                return true;
            } catch (IOException e) {
                VolleyLog.d("%s", e.toString());
                return false;
            }
        }

    }

    private static class CountingInputStream extends FilterInputStream {
        private int bytesRead = 0;

        private CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int result = super.read();
            if (result != -1) {
                bytesRead++;
            }
            return result;
        }

        @Override
        public int read(byte[] buffer, int offset, int count) throws IOException {
            int result = super.read(buffer, offset, count);
            if (result != -1) {
                bytesRead += result;
            }
            return result;
        }
    }

    /*
     * Homebrewed simple serialization system used for reading and writing cache
     * headers on disk. Once upon a time, this used the standard Java
     * Object{Input,Output}Stream, but the default implementation relies heavily
     * on reflection (even for standard types) and generates a ton of garbage.
     */

    /**
     * Simple wrapper around {@link InputStream#read()} that throws EOFException
     * instead of returning -1.
     */
    private static int read(InputStream is) throws IOException {
        int b = is.read();
        if (b == -1) {
            throw new EOFException();
        }
        return b;
    }

    static void writeInt(OutputStream os, int n) throws IOException {
        os.write((n >> 0) & 0xff);
        os.write((n >> 8) & 0xff);
        os.write((n >> 16) & 0xff);
        os.write((n >> 24) & 0xff);
    }

    static int readInt(InputStream is) throws IOException {
        int n = 0;
        n |= (read(is) << 0);
        n |= (read(is) << 8);
        n |= (read(is) << 16);
        n |= (read(is) << 24);
        return n;
    }

    static void writeLong(OutputStream os, long n) throws IOException {
        os.write((byte)(n >>> 0));
        os.write((byte)(n >>> 8));
        os.write((byte)(n >>> 16));
        os.write((byte)(n >>> 24));
        os.write((byte)(n >>> 32));
        os.write((byte)(n >>> 40));
        os.write((byte)(n >>> 48));
        os.write((byte)(n >>> 56));
    }

    static long readLong(InputStream is) throws IOException {
        long n = 0;
        n |= ((read(is) & 0xFFL) << 0);
        n |= ((read(is) & 0xFFL) << 8);
        n |= ((read(is) & 0xFFL) << 16);
        n |= ((read(is) & 0xFFL) << 24);
        n |= ((read(is) & 0xFFL) << 32);
        n |= ((read(is) & 0xFFL) << 40);
        n |= ((read(is) & 0xFFL) << 48);
        n |= ((read(is) & 0xFFL) << 56);
        return n;
    }

    static void writeString(OutputStream os, String s) throws IOException {
        byte[] b = s.getBytes("UTF-8");
        writeLong(os, b.length);
        os.write(b, 0, b.length);
    }

    static String readString(InputStream is) throws IOException {
        int n = (int) readLong(is);
        byte[] b = streamToBytes(is, n);
        return new String(b, "UTF-8");
    }

    static void writeStringStringMap(Map<String, String> map, OutputStream os) throws IOException {
        if (map != null) {
            writeInt(os, map.size());
            for (Map.Entry<String, String> entry : map.entrySet()) {
                writeString(os, entry.getKey());
                writeString(os, entry.getValue());
            }
        } else {
            writeInt(os, 0);
        }
    }

    static Map<String, String> readStringStringMap(InputStream is) throws IOException {
        int size = readInt(is);
        Map<String, String> result = (size == 0)
                ? Collections.<String, String>emptyMap()
                : new HashMap<String, String>(size);
        for (int i = 0; i < size; i++) {
            String key = readString(is).intern();
            String value = readString(is).intern();
            result.put(key, value);
        }
        return result;
    }


}
