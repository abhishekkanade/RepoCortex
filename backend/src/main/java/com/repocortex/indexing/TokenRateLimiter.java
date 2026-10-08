package com.repocortex.indexing;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

// Sliding one-minute token budget shared by all embedding calls (free tiers limit tokens per minute).
class TokenRateLimiter {

    private static final long WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final int tokensPerMinute;
    // {timestamp, tokens} of calls inside the current window
    private final Deque<long[]> used = new ArrayDeque<>();
    private long usedTokens;

    // tokensPerMinute <= 0 means no limit
    TokenRateLimiter(int tokensPerMinute) {
        this.tokensPerMinute = tokensPerMinute;
    }

    boolean isLimited() {
        return tokensPerMinute > 0;
    }

    int tokensPerMinute() {
        return tokensPerMinute;
    }

    // blocks until the tokens fit in the last minute's budget
    synchronized void acquire(int tokens) throws InterruptedException {
        if (!isLimited()) {
            return;
        }
        int needed = Math.min(tokens, tokensPerMinute);
        while (true) {
            long now = System.nanoTime();
            while (!used.isEmpty() && now - used.peekFirst()[0] >= WINDOW_NANOS) {
                usedTokens -= used.pollFirst()[1];
            }
            if (usedTokens + needed <= tokensPerMinute) {
                used.addLast(new long[]{now, needed});
                usedTokens += needed;
                return;
            }
            // wait until the oldest call leaves the window
            TimeUnit.NANOSECONDS.timedWait(this, WINDOW_NANOS - (now - used.peekFirst()[0]));
        }
    }

    // rough count for code: about 3 characters per token, rounded up to stay on the safe side
    static int estimateTokens(String text) {
        return text.length() / 3 + 1;
    }
}
