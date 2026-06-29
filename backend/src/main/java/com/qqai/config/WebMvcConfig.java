package com.qqai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

/**
 * Web MVC 配置 - 配置静态资源访问
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${file.storage.local-path:./uploads/images}")
    private String localImagePath;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 获取绝对路径
        File imageDir = new File(localImagePath);
        String absolutePath = imageDir.getAbsolutePath();
        
        // 确保目录存在
        if (!imageDir.exists()) {
            imageDir.mkdirs();
        }
        
        // 配置静态资源映射 - /images/** 映射到本地图片存储目录
        registry.addResourceHandler("/images/**")
                .addResourceLocations("file:" + absolutePath + "/")
                .setCachePeriod(3600); // 缓存1小时
        
        System.out.println("静态资源映射配置: /images/** -> file:" + absolutePath + "/");
        
        // 配置头像上传目录映射（groups 与 users 子目录自动包含在该映射下）
        File avatarDir = new File("uploads/avatars");
        String avatarPath = avatarDir.getAbsolutePath();
        if (!avatarDir.exists()) {
            avatarDir.mkdirs();
        }
        // 预先创建隔离子目录
        new File(avatarDir, "groups").mkdirs();
        new File(avatarDir, "users").mkdirs();
        registry.addResourceHandler("/uploads/avatars/**")
                .addResourceLocations("file:" + avatarPath + "/")
                .setCachePeriod(3600);

        System.out.println("静态资源映射配置: /uploads/avatars/** -> file:" + avatarPath + "/");

        // 配置整个 uploads 目录映射（媒体文件管理预览需要）
        File uploadsDir = new File("uploads");
        String uploadsPath = uploadsDir.getAbsolutePath();
        if (!uploadsDir.exists()) {
            uploadsDir.mkdirs();
        }
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadsPath + "/")
                .setCachePeriod(3600);

        System.out.println("静态资源映射配置: /uploads/** -> file:" + uploadsPath + "/");
    }
}
