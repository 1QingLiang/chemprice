package com.datamarket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("commodity")
public class Commodity {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer varietiesId;
    private String name;
    private String category;
    private String unit;
    private Boolean hasMarket;
    private Boolean hasEnterprise;
    private Boolean hasIntl;
    private Integer marketCount;
    private Integer enterpriseCount;
    private Integer intlCount;
    private LocalDate earliestDate;
    private LocalDate latestDate;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
