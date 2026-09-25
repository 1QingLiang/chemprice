package com.datamarket.service;

import com.datamarket.entity.AuditLog;
import com.datamarket.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 审计日志服务：统一记录用户关键操作。
 * 使用方式：auditService.record("EXPORT_PRICE", "DATA", "EXPORT", null, before, after, 1, "INFO", "详情");
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogMapper auditLogMapper;

    /**
     * 记录一条审计日志（自动从上下文取当前用户、IP、请求URI）
     *
     * @param action      动作码（如 EXPORT_PRICE）
     * @param type        大类（AUTH/DATA/PERMISSION/ADMIN/BUSINESS）
     * @param targetType  目标对象类型（USER/PERMISSION/EXPORT/SYSTEM 等，可为 null）
     * @param targetId    目标对象ID（可为 null）
     * @param before      操作前值（JSON，可为 null）
     * @param after       操作后值（JSON，可为 null）
     * @param result      结果：1=成功 0=失败
     * @param level       INFO/WARN/ERROR
     * @param detail      人类可读描述
     */
    public void record(String action, String type, String targetType, String targetId,
                       String before, String after, int result, String level, String detail) {
        recordInternal(action, type, targetType, targetId, before, after, result, level, detail, null);
    }

    /**
     * 记录一条审计日志，并显式指定操作人用户名（用于注册等尚无登录上下文的场景）。
     */
    public void recordAs(String action, String type, String targetType, String targetId,
                         String before, String after, int result, String level, String detail,
                         String username) {
        recordInternal(action, type, targetType, targetId, before, after, result, level, detail, username);
    }

    private void recordInternal(String action, String type, String targetType, String targetId,
                                String before, String after, int result, String level, String detail,
                                String usernameOverride) {
        try {
            AuditLog logEntry = new AuditLog();
            logEntry.setAction(action);
            logEntry.setOperationType(type);
            logEntry.setTargetType(targetType);
            logEntry.setTargetId(targetId);
            logEntry.setBeforeValue(before);
            logEntry.setAfterValue(after);
            logEntry.setResult(result);
            logEntry.setLevel(level);
            logEntry.setDetail(detail);
            logEntry.setIp(getClientIp());
            logEntry.setRequestUri(getRequestUri());

            // 显式指定的操作人优先（注册等无登录上下文场景）；否则取当前登录用户
            if (usernameOverride != null && !usernameOverride.isBlank()) {
                logEntry.setUsername(usernameOverride);
            } else {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.getName() != null && !"anonymousUser".equals(auth.getName())) {
                    logEntry.setUsername(auth.getName());
                }
            }

            auditLogMapper.insert(logEntry);
        } catch (Exception e) {
            // 日志写入失败不影响业务主流程
            log.warn("审计日志写入失败: {}", e.getMessage());
        }
    }

    /**
     * 便捷方法：成功（INFO）日志
     */
    public void ok(String action, String type, String targetType, String targetId,
                   String before, String after, String detail) {
        record(action, type, targetType, targetId, before, after, 1, "INFO", detail);
    }

    /**
     * 便捷方法：成功（INFO）日志，显式指定操作人（注册等无登录上下文场景）
     */
    public void okAs(String action, String type, String targetType, String targetId,
                     String before, String after, String detail, String username) {
        recordAs(action, type, targetType, targetId, before, after, 1, "INFO", detail, username);
    }

    /**
     * 便捷方法：警告（WARN）日志，显式指定操作人（登录失败等无 SecurityContext 场景）
     */
    public void warnAs(String action, String type, String targetType, String targetId,
                       String before, String after, String detail, String username) {
        recordAs(action, type, targetType, targetId, before, after, 1, "WARN", detail, username);
    }

    /**
     * 便捷方法：失败（ERROR）日志，显式指定操作人
     */
    public void errorAs(String action, String type, String targetType, String targetId,
                        String before, String after, String detail, String username) {
        recordAs(action, type, targetType, targetId, before, after, 0, "ERROR", detail, username);
    }

    /**
     * 便捷方法：警告（WARN）日志
     */
    public void warn(String action, String type, String targetType, String targetId,
                     String before, String after, String detail) {
        record(action, type, targetType, targetId, before, after, 1, "WARN", detail);
    }

    /**
     * 便捷方法：失败（ERROR）日志
     */
    public void error(String action, String type, String targetType, String targetId,
                      String before, String after, String detail) {
        record(action, type, targetType, targetId, before, after, 0, "ERROR", detail);
    }

    private String getClientIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpServletRequest req = attrs.getRequest();
            String ip = req.getHeader("X-Forwarded-For");
            if (ip != null && !ip.isBlank()) {
                return ip.split(",")[0].trim();
            }
            return req.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }

    private String getRequestUri() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            return attrs.getRequest().getRequestURI();
        } catch (Exception e) {
            return null;
        }
    }
}
