package com.datamarket.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datamarket.common.Result;
import com.datamarket.dto.LoginRequest;
import com.datamarket.dto.LoginResponse;
import com.datamarket.entity.SysUser;
import com.datamarket.mapper.SysUserMapper;
import com.datamarket.common.Result;
import com.datamarket.service.AuthService;
import com.datamarket.service.DemandService;
import com.datamarket.service.AuditService;
import com.datamarket.service.EmailService;
import com.datamarket.service.RegisterIpLimiter;
import com.datamarket.service.VerificationCodeStore;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Random;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final SysUserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final VerificationCodeStore codeStore;
    private final JdbcTemplate jdbcTemplate;
    private final RegisterIpLimiter registerIpLimiter;
    private final AuditService auditService;

    private static final Pattern PHONE_PATTERN =
            Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    /** 用户名规则：仅允许 2-30 位字母/数字/下划线，禁止中文、空格与特殊符号 */
    private static final Pattern USERNAME_PATTERN =
        Pattern.compile("^[A-Za-z0-9_]{2,30}$");

    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return Result.ok(response);
    }

    @GetMapping("/me")
    public Result<Map<String, Object>> getCurrentUser(Authentication authentication) {
        String username = authentication.getName();
        Map<String, Object> userInfo = authService.getCurrentUser(username);
        return Result.ok(userInfo);
    }

    @PostMapping("/register")
    public Result<Map<String, Object>> register(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String username = body.get("username");
        String password = body.get("password");
        String nickname = body.get("nickname");
        String email = body.get("email");
        String code = body.get("code");

        if (username == null || username.isBlank()) return Result.error("用户名不能为空");
        // 用户名规则校验：2-30 位字母/数字/下划线，禁止中文
        if (!USERNAME_PATTERN.matcher(username.trim()).matches()) {
            return Result.error("用户名仅支持 2-30 位字母、数字或下划线，不能包含中文或特殊字符");
        }
        username = username.trim();
        if (password == null || password.length() < 6) return Result.error("密码至少6位");

        // 原生 SQL 查重（忽略逻辑删除过滤，避免已删除用户名撞唯一索引 uk_username）
        if (userMapper.countByUsernameRaw(username) > 0) return Result.error("用户名已存在");

        // ===== 注册统一走邮箱：必须使用邮箱 + 验证码 =====
        if (email == null || email.isBlank()) return Result.error("请输入邮箱");
        if (!EMAIL_PATTERN.matcher(email).matches()) return Result.error("邮箱格式不正确");
        if (code == null || code.isBlank()) return Result.error("请输入邮箱验证码");

        // 邮箱查重：只统计启用(status=1)账号，停用账号不占邮箱可重新注册
        if (userMapper.countByEmailRaw(email) > 0) return Result.error("该邮箱已注册，可直接登录");

        // 服务端校验邮箱验证码（防绕过前端直接调接口注册）
        if (!codeStore.hasPending(email)) {
            return Result.error("请先获取邮箱验证码");
        }
        if (!codeStore.verify(email, code, "register")) {
            return Result.error("验证码错误或已过期，请重新获取");
        }

        // 注册 IP 限流：同一 IP 窗口内注册过多则拒绝
        String ip = clientIp(request);
        if (!registerIpLimiter.tryAcquire(ip)) {
            return Result.error("该IP在" + registerIpLimiter.getWindowHours() + "小时内注册账号过多（上限"
                + registerIpLimiter.getMaxPerWindow() + "个），请稍后再试");
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(nickname != null && !nickname.isBlank() ? nickname : username);
        user.setEmail(email);
        user.setRole("USER");
        user.setStatus(1);
        userMapper.insert(user);

        grantDefaultPermission(user.getId());

        // 注册审计：此时用户尚未登录（无 SecurityContext），显式指定操作人
        auditService.okAs("REGISTER_SUCCESS", "AUTH", "USER", String.valueOf(user.getId()),
            null, null, "注册成功（用户名: " + user.getUsername() + "，邮箱: " + email + "）", user.getUsername());

        Map<String, Object> result = new HashMap<>();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        return Result.ok(result);
    }

    /** 新注册用户默认开通全部在售商品查看权限（永久） */
    private void grantDefaultPermission(Long userId) {
        jdbcTemplate.update(
            "INSERT INTO data_permission (user_id, varieties_id, varieties_name, expire_date) " +
            "SELECT ?, varieties_id, name, NULL FROM commodity WHERE status = 1 " +
            "ON DUPLICATE KEY UPDATE varieties_name = VALUES(varieties_name), expire_date = VALUES(expire_date)",
            userId);
    }

    @PostMapping("/reset-password")
    public Result<String> resetPassword(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        if (username == null || username.isBlank()) return Result.error("请输入用户名");

        SysUser user = userMapper.selectOne(new QueryWrapper<SysUser>().eq("username", username));
        if (user == null) return Result.error("用户不存在");

        user.setPassword(passwordEncoder.encode("123456"));
        userMapper.updateById(user);
        return Result.ok("密码已重置为 123456");
    }

    // ========== 邮箱验证码 ==========

    // ========== 账号：绑定手机号（无短信校验，仅格式校验） ==========

    /** 当前账号概览（手机号脱敏 + 认证状态） */
    @GetMapping("/account")
    public Result<Map<String, Object>> account() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        SysUser u = userMapper.selectOne(
                new QueryWrapper<SysUser>().eq("username", auth.getName()));
        if (u == null) return Result.error(401, "未登录");
        Map<String, Object> ext;
        try {
            ext = jdbcTemplate.queryForMap(
                    "SELECT phone, supplier_status, IFNULL(supplier_company,'') AS supplier_company, " +
                    "IFNULL(realname_status,0) AS realname_status " +
                    "FROM sys_user WHERE id = ?", u.getId());
        } catch (Exception e) {
            ext = new HashMap<>();
        }
        String phone = ext.get("phone") == null ? "" : String.valueOf(ext.get("phone"));

        Map<String, Object> m = new HashMap<>();
        m.put("username", u.getUsername());
        m.put("nickname", u.getNickname());
        m.put("email", u.getEmail());
        m.put("role", u.getRole());
        m.put("phoneBound", !phone.isBlank());
        m.put("phoneMasked", DemandService.maskPhone(phone));
        m.put("phone", phone);   // 本人的完整手机号，仅自己可见
        Object rn = ext.get("realname_status");
        m.put("realnameStatus", rn instanceof Number n ? n.intValue() : 0);
        Object st = ext.get("supplier_status");
        m.put("supplierStatus", st instanceof Number n ? n.intValue() : 0);
        m.put("supplierCompany", ext.get("supplier_company"));
        return Result.ok(m);
    }

    /** 绑定 / 更换手机号 */
    @PostMapping("/phone")
    public Result<Map<String, Object>> bindPhone(@RequestBody Map<String, String> body) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        SysUser u = userMapper.selectOne(
                new QueryWrapper<SysUser>().eq("username", auth.getName()));
        if (u == null) return Result.error(401, "未登录");
        String phone = body.getOrDefault("phone", "").trim();
        if (!PHONE_PATTERN.matcher(phone).matches()) {
            return Result.error("请填写正确的 11 位手机号");
        }
        // 绑定后不可修改（供需联系的信任基础）；如有特殊情况由管理员处理
        List<Map<String, Object>> cur = jdbcTemplate.queryForList(
                "SELECT phone FROM sys_user WHERE id = ?", u.getId());
        String curPhone = cur.isEmpty() || cur.get(0).get("phone") == null
                ? "" : String.valueOf(cur.get(0).get("phone")).trim();
        if (!curPhone.isBlank()) {
            return Result.error(403, "手机号已绑定，不可修改；如有特殊情况请联系管理员");
        }
        jdbcTemplate.update("UPDATE sys_user SET phone = ? WHERE id = ?", phone, u.getId());
        try {
            auditService.record("BIND_PHONE", "AUTH", "USER", String.valueOf(u.getId()),
                    null, null, 1, "INFO", "绑定/更换手机号");
        } catch (Exception ignore) { }
        Map<String, Object> out = new HashMap<>();
        out.put("phoneMasked", DemandService.maskPhone(phone));
        return Result.ok(out);
    }

    @PostMapping("/send-code")
    public Result<String> sendCode(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String purpose = body.getOrDefault("purpose", "register");
        if (email == null || email.isBlank()) return Result.error("请输入邮箱");
        if (!EMAIL_PATTERN.matcher(email).matches()) return Result.error("邮箱格式不正确");

        boolean isRegister = "register".equals(purpose);
        long emailCount = userMapper.countByEmailRaw(email);
        if (isRegister && emailCount > 0) {
            return Result.error("该邮箱已注册，可直接登录");
        }
        if (!isRegister && emailCount == 0) {
            return Result.error("该邮箱未注册，请先注册账号");
        }

        // 生成6位验证码
        String code = String.format("%06d", new Random().nextInt(999999));
        codeStore.store(email, code, purpose);

        String purposeText = isRegister ? "注册 ChemPrice 账号" : "找回密码";
        emailService.sendVerificationCode(email, code, purposeText);

        return Result.ok("验证码已发送到 " + email);
    }

    @PostMapping("/verify-code")
    public Result<String> verifyCode(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String code = body.get("code");
        String purpose = body.getOrDefault("purpose", "register");

        if (email == null || code == null) return Result.error("参数不完整");
        
        // 先检查是否有待验证的验证码
        if (!codeStore.hasPending(email)) {
            return Result.error("请先获取验证码");
        }
        
        boolean success = codeStore.verify(email, code, purpose);
        if (success) {
            return Result.ok("验证成功");
        }
        return Result.error("验证码错误，请检查后重新输入（连续错误3次将失效）");
    }

    @PostMapping("/reset-password-by-email")
    public Result<String> resetByEmail(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String code = body.get("code");
        String newPassword = body.get("newPassword");

        if (email == null || code == null || newPassword == null) return Result.error("参数不完整");
        if (newPassword.length() < 6) return Result.error("密码至少6位");

        // 验证码校验（错误提示与注册保持一致）
        if (!codeStore.hasPending(email)) {
            return Result.error("请先获取验证码");
        }
        if (!codeStore.verify(email, code, "forgot")) {
            return Result.error("验证码错误，请检查后重新输入（连续错误3次将失效）");
        }

        SysUser user = userMapper.selectOne(new QueryWrapper<SysUser>().eq("email", email));
        if (user == null) return Result.error("未找到该邮箱对应的用户");

        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
        return Result.ok("密码重置成功");
    }

    /** 从反代请求头中取真实客户端 IP（nginx 已设置 X-Forwarded-For / X-Real-IP） */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}