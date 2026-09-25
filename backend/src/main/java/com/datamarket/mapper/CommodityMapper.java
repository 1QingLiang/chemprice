package com.datamarket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datamarket.entity.Commodity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CommodityMapper extends BaseMapper<Commodity> {
}
