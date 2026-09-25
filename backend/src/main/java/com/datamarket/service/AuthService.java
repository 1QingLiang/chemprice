package com.datamarket.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datamarket.dto.LoginRequest;
import com.datamarket.dto.LoginResponse;
import com.datamarket.entity.DataPermission;
import com.datamarket.entity.SysUser;
import com.datamarket.mapper.SysUserMapper;
import com.datamarket.mapper.DataPermissionMapper;
import com.datamarket.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final SysUserMapper userMapper;
    private final DataPermissionMapper permissionMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;

    /** 连续登录失败阈值 */
    @Value("${auth.fail-threshold:5}")
    private int failThreshold;

    /** 冻结时长（分钟） */
    @Value("${auth.lock-minutes:30}")
    private int lockMinutes;

    public LoginResponse login(LoginRequest request) {
        String account = request.getUsername() == null ? "" : request.getUsername().trim();
        if (account.isEmpty()) {
            throw new RuntimeException("请输入用户名或邮箱");
        }

        // 支持用户名或邮箱登录：含 @ 按邮箱识别，否则按用户名
        // 邮箱历史无唯一索引，加 LIMIT 1 防重复邮箱抛错
        SysUser user = account.contains("@")
            ? userMapper.selectOne(new QueryWrapper<SysUser>().eq("email", account).last("LIMIT 1"))
            : userMapper.selectOne(new QueryWrapper<SysUser>().eq("username", account).last("LIMIT 1"));

        if (user == null) {
            auditService.warnAs("LOGIN_FAIL", "AUTH", "USER", null, null, null,
                "登录失败：账号不存在（尝试: " + account + "）", account);
            throw new RuntimeException("用户名/邮箱或密码错误");
        }

        // 1. 冻结检查：lock_until 未过期则拒绝登录
        if (user.getLockUntil() != null && user.getLockUntil().isAfter(LocalDateTime.now())) {
            long remainMinutes = java.time.Duration.between(LocalDateTime.now(), user.getLockUntil()).toMinutes() + 1;
            auditService.warnAs("LOGIN_LOCKED", "AUTH", "USER", String.valueOf(user.getId()), null, null,
                "登录被拒：账号冻结中，剩余约 " + remainMinutes + " 分钟", user.getUsername());
            throw new RuntimeException("账号已冻结，请 " + remainMinutes + " 分钟后重试");
        }

        // 2. 密码校验
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            auditService.warnAs("LOGIN_FAIL", "AUTH", "USER", String.valueOf(user.getId()), null, null,
                "登录失败：密码错误（用户: " + user.getUsername() + "）", user.getUsername());
            handleLoginFail(user.getId());
            throw new RuntimeException("用户名或密码错误");
        }

        // 3. 账号状态
        if (user.getStatus() != 1) {
            auditService.warnAs("LOGIN_FAIL", "AUTH", "USER", String.valueOf(user.getId()), null, null,
                "登录失败：账号已禁用（用户: " + user.getUsername() + "）", user.getUsername());
            throw new RuntimeException("用户已被禁用");
        }

        // 4. 登录成功：清零失败计数与冻结状态，并记录最后登录时间/IP（原子 UPDATE）
        //    注意 updated_at = updated_at：显式赋当前值可抑制 ON UPDATE CURRENT_TIMESTAMP，
        //    否则每次登录都会把「信息更新时间」刷成当前时间，管理员会误以为资料被改过。
        jdbcTemplate.update(
            "UPDATE sys_user SET login_fail_count = 0, lock_until = NULL, " +
            "last_login_at = NOW(), last_login_ip = ?, updated_at = updated_at WHERE id = ?",
            clientIp(), user.getId());

        auditService.okAs("LOGIN_SUCCESS", "AUTH", "USER", String.valueOf(user.getId()), null, null,
            "登录成功（用户: " + user.getUsername() + "）", user.getUsername());
        String token = jwtUtil.generateToken(user.getUsername(), user.getRole());
        return new LoginResponse(token, user.getUsername(), user.getNickname(), user.getRole(), user.getExportPermission());
    }

    /**
     * 取客户端真实 IP。站点走 Nginx 反代，X-Forwarded-For 的第一段才是真实来源。
     */
    private String clientIp() {
        try {
            ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
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

    /**
     * 登录失败处理：原子递增失败计数，达到阈值则冻结账号。
     * 使用 UPDATE ... SET login_fail_count = login_fail_count + 1 保证并发安全，
     * 不经过 Java 读改写，避免并发请求绕过计数。
     */
    private void handleLoginFail(Long userId) {
        jdbcTemplate.update(
            "UPDATE sys_user SET login_fail_count = login_fail_count + 1 WHERE id = ?", userId);

        Integer count = jdbcTemplate.queryForObject(
            "SELECT login_fail_count FROM sys_user WHERE id = ?", Integer.class, userId);
        if (count != null && count >= failThreshold) {
            // 达到阈值：冻结 lock-minutes 分钟，并重置计数（解冻后重新计数）
            LocalDateTime lockUntil = LocalDateTime.now().plusMinutes(lockMinutes);
            jdbcTemplate.update(
                "UPDATE sys_user SET lock_until = ?, login_fail_count = 0 WHERE id = ?", lockUntil, userId);
            log.warn("账号 {} 连续登录失败 {} 次，已冻结至 {}", userId, count, lockUntil);
            throw new RuntimeException("登录失败次数过多，账号已冻结 " + lockMinutes + " 分钟");
        }
    }

    public Map<String, Object> getCurrentUser(String username) {
        SysUser user = userMapper.selectOne(
                new QueryWrapper<SysUser>().eq("username", username));

        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        result.put("nickname", user.getNickname());
        result.put("role", user.getRole());
        result.put("exportPermission", user.getExportPermission());

        // 获取用户授权的商品列表
        List<DataPermission> permissions = permissionMapper.selectList(
                new QueryWrapper<DataPermission>().eq("user_id", user.getId()));

        List<Integer> varietiesIds = permissions.stream()
                .map(DataPermission::getVarietiesId)
                .collect(Collectors.toList());

        result.put("varietiesIds", varietiesIds);

        return result;
    }
}