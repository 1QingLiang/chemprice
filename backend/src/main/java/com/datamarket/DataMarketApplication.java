package com.datamarket;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datamarket.entity.SysUser;
import com.datamarket.mapper.SysUserMapper;

@SpringBootApplication
@MapperScan("com.datamarket.mapper")
@EnableAsync
public class DataMarketApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataMarketApplication.class, args);
    }

    @Bean
    BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CommandLineRunner initAdmin(SysUserMapper userMapper, BCryptPasswordEncoder encoder) {
        return args -> {
            long count = userMapper.selectCount(new QueryWrapper<SysUser>().eq("username", "admin"));
            if (count == 0) {
                SysUser admin = new SysUser();
                admin.setUsername("admin");
                admin.setPassword(encoder.encode("admin123"));
                admin.setNickname("管理员");
                admin.setRole("ADMIN");
                admin.setStatus(1);
                userMapper.insert(admin);
                System.out.println("=== 初始管理员 admin/admin123 创建成功 ===");
            }
        };
    }
}
