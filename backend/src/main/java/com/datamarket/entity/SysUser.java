package com.datamarket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String password;
    private String nickname;
    private String email;
    private String role;
    private Integer status;
    private Integer exportPermission;
    private Integer loginFailCount;
    private LocalDateTime lockUntil;
    /** 最后登录时间（登录成功时写入） */
    private LocalDateTime lastLoginAt;
    /** 最后登录 IP */
    private String lastLoginIp;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 测试账号：1 是（「用户看板」等统计默认排除）| 0 否 */
    private Integer isTest;

    /** 开放 API：1=管理员已授权（ADMIN 天然可用） */
    private Integer openApiEnabled;
    /** 开放 API key 的默认日限额（管理员可调） */
    private Integer openApiDailyLimit;
}
