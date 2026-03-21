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
    }
}
