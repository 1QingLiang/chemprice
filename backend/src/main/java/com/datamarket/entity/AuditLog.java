package com.datamarket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("audit_log")
public class AuditLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String username;
    private String action;
    private String operationType;
    private String targetType;
    private String targetId;
    private String beforeValue;
    private String afterValue;
    private Integer result;
    private String level;
    private String requestUri;
    private String detail;
    private String ip;
    private LocalDateTime createdAt;
}
