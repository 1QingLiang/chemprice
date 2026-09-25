package com.datamarket.security;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datamarket.entity.SysUser;
import com.datamarket.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class PermissionHelper {

    private final SysUserMapper userMapper;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 获取当前用户的授权 varietiesId 列表。
     * ADMIN 返回 null（代表全部可见），USER 返回授权列表。
     * 零授权用户返回空列表。
     */
    public List<Integer> getPermittedVarietiesIds() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return List.of();

        String username = auth.getName();
        SysUser user = userMapper.selectOne(
            new QueryWrapper<SysUser>().eq("username", username));
        if (user == null) return List.of();

        // ADMIN 可看全部
        if ("ADMIN".equals(user.getRole())) return null;

        // USER：查权限表（过滤已过期的）
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT varieties_id FROM data_permission WHERE user_id = ? " +
            "AND (expire_date IS NULL OR expire_date >= CURDATE())", user.getId());

        return rows.stream()
            .map(r -> Integer.parseInt(String.valueOf(r.get("varieties_id"))))
            .collect(Collectors.toList());
    }

    /**
     * 检查当前用户是否有权限查看指定 varietiesId
     */
    public boolean canAccess(Integer varietiesId) {
        List<Integer> permitted = getPermittedVarietiesIds();
        if (permitted == null) return true;  // ADMIN
        return permitted.contains(varietiesId);
    }

    /**
     * 获取当前登录用户的 ID
     */
    public Long getCurrentUserId() {
        SysUser user = getCurrentUser();
        return user != null ? user.getId() : null;
    }

    /**
     * 获取当前登录用户实体（未登录返回 null）。
     * 导出等能力需要读取 sys_user 上的开关（如 export_permission）。
     */
    public SysUser getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        String username = auth.getName();
        if (username == null || username.isBlank()) return null;
        return userMapper.selectOne(new QueryWrapper<SysUser>().eq("username", username));
    }
}
