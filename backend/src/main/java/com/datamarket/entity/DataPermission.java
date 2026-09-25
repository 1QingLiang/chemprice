package com.datamarket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("data_permission")
public class DataPermission {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Integer varietiesId;
    private String varietiesName;
    private Long grantedBy;
    private LocalDateTime createdAt;
}