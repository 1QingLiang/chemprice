package com.datamarket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datamarket.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 查全部用户（含已禁用）。
     * 用户管理列表必须能看到禁用账号，否则管理员无法恢复它们 ——
     * 而全局 logic-delete-field: status 会让 BaseMapper.selectList 自动追加 AND status=1。
     */
    @Select("SELECT * FROM sys_user ORDER BY id ASC")
    List<SysUser> selectAllIncludingDisabled();

    /**
     * 按 id 查用户（含已禁用）。
     * 编辑 / 删除禁用账号时，BaseMapper.selectById 会因逻辑删除返回 null，导致误报“用户不存在”。
     */
    @Select("SELECT * FROM sys_user WHERE id = #{id}")
    SysUser selectByIdIncludingDisabled(@Param("id") Long id);

    /**
     * 按用户名查数量（原生 SQL，忽略逻辑删除过滤）。
     * 全局配置了 logic-delete-field: status，BaseMapper.selectCount 会自动追加 AND status=1，
     * 导致已删除(禁用)用户的用户名查不到，注册时撞唯一索引 uk_username。
     */
    @Select("SELECT COUNT(*) FROM sys_user WHERE username = #{username}")
    long countByUsernameRaw(@Param("username") String username);

    /**
     * 按邮箱查数量，用于注册邮箱唯一校验。
     * 只统计 status=1（启用）账号：停用(status=0)账号不占邮箱，允许重新注册。
     * 注意 username 有物理唯一索引 uk_username，不能照此放宽；email 无唯一索引，可复用。
     */
    @Select("SELECT COUNT(*) FROM sys_user WHERE email = #{email} AND status = 1")
    long countByEmailRaw(@Param("email") String email);
}
