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

/**
 * 默认的重试策略, 会在请求返回服务器内部错误5xx并且当前请求{@link Request}启动了重试策略的时候使用
 * Default retry policy for requests.
 */
public class DefaultRetryPolicy implements RetryPolicy {
    /**
     * 超时毫秒数, 默认是2.5s
     * The current timeout in milliseconds. */
    private int mCurrentTimeoutMs;

    /**
     * 记录当前重试次数
     * The current retry count. */
    private int mCurrentRetryCount;

    /**
     * 最大重试次数, 默认是1次
     * The maximum number of attempts. */
    private final int mMaxNumRetries;

    /**
     * 下一次重试的超时时间是当前的几倍? 默认是1
     * The backoff multiplier for the policy. */
    private final float mBackoffMultiplier;

    /** The default socket timeout in milliseconds */
    public static final int DEFAULT_TIMEOUT_MS = 2500;

    /** The default number of retries */
    public static final int DEFAULT_MAX_RETRIES = 1;

    /** The default backoff multiplier */
    public static final float DEFAULT_BACKOFF_MULT = 1f;

    /**
     * Constructs a new retry policy using the default timeouts.
     */
    public DefaultRetryPolicy() {
        this(DEFAULT_TIMEOUT_MS, DEFAULT_MAX_RETRIES, DEFAULT_BACKOFF_MULT);
    }

    /**
     * Constructs a new retry policy.
     * @param initialTimeoutMs The initial timeout for the policy.
     * @param maxNumRetries The maximum number of retries.
     * @param backoffMultiplier Backoff multiplier for the policy.
     */
    public DefaultRetryPolicy(int initialTimeoutMs, int maxNumRetries, float backoffMultiplier) {
        mCurrentTimeoutMs = initialTimeoutMs;
        mMaxNumRetries = maxNumRetries;
        mBackoffMultiplier = backoffMultiplier;
    }

    /**
     * Returns the current timeout.
     */
    @Override
    public int getCurrentTimeout() {
        return mCurrentTimeoutMs;
    }

    /**
     * Returns the current retry count.
     */
    @Override
    public int getCurrentRetryCount() {
        return mCurrentRetryCount;
    }

    /**
     * Returns the backoff multiplier for the policy.
     */
    public float getBackoffMultiplier() {
        return mBackoffMultiplier;
    }

    /**
     * 延迟超时时间, 会影响{@link Request#getTimeoutMs()}, 进而影响在实际发起请求时的超时时间
     * 如果超过最大重试次数就抛出异常
     * todo 1. 谁处理这个异常? 2. 传进来的异常时谁传进来的?
     * 1. 抛出的异常会在{@link com.android.volley.toolbox.BasicNetwork#attemptRetryOnException(String, Request, VolleyError)}中处理
     * 2. 传进来的异常来自{@link com.android.volley.toolbox.BasicNetwork#attemptRetryOnException(String, Request, VolleyError)},
     * 是在{@link com.android.volley.toolbox.BasicNetwork#performRequest(Request)}中根据网络请求的异常来创建的
     * Prepares for the next retry by applying a backoff to the timeout.
     * @param error The error code of the last attempt.
     */
    @Override
    public void retry(VolleyError error) throws VolleyError {
        mCurrentRetryCount++;
        mCurrentTimeoutMs += (mCurrentTimeoutMs * mBackoffMultiplier);
        if (!hasAttemptRemaining()) {
            throw error;
        }
    }

    /**
     * Returns true if this policy has attempts remaining, false otherwise.
     */
    protected boolean hasAttemptRemaining() {
        return mCurrentRetryCount <= mMaxNumRetries;
    }
}
