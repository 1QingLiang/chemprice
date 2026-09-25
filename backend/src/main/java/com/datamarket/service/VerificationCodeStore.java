package com.datamarket.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class VerificationCodeStore {

    private static final long EXPIRY_MS = 5 * 60 * 1000; // 5 minutes
    private static final int MAX_ATTEMPTS = 3; // 允许最多3次尝试

    private final ConcurrentHashMap<String, CodeEntry> codes = new ConcurrentHashMap<>();

    public void store(String email, String code, String purpose) {
        codes.put(email, new CodeEntry(code, purpose, System.currentTimeMillis()));
    }

    public boolean verify(String email, String code, String purpose) {
        CodeEntry entry = codes.get(email);
        if (entry == null) return false;

        // 检查过期
        if (System.currentTimeMillis() - entry.createdAt > EXPIRY_MS) {
            codes.remove(email);
            return false;
        }

        // 检查尝试次数
        if (entry.attemptCount >= MAX_ATTEMPTS) {
            codes.remove(email);
            return false;
        }

        // 验证码、目的匹配
        if (!entry.code.equals(code) || !entry.purpose.equals(purpose)) {
            entry.attemptCount++;
            return false;
        }

        // 验证成功，移除验证码
        codes.remove(email);
        return true;
    }

    public boolean hasPending(String email) {
        return codes.containsKey(email);
    }

    private static class CodeEntry {
        String code;
        String purpose;
        long createdAt;
        int attemptCount;

        CodeEntry(String code, String purpose, long createdAt) {
            this.code = code;
            this.purpose = purpose;
            this.createdAt = createdAt;
            this.attemptCount = 0;
        }
    }
}
