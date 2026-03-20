package com.qqai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.StringEntity;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.io.IOException;

@Service
public class AstrBotService {
    @Value("${astrbot.api-url}")
    private String astrBotApiUrl;

    @Value("${astrbot.token}")
    private String astrBotToken;

    public String summarizeMessage(String content) throws Exception {
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost(astrBotApiUrl + "/api/v1/chat");

        // 设置请求头
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("Authorization", "Bearer " + astrBotToken);

        // 构建请求体
        JSONObject requestBody = new JSONObject();
        requestBody.put("message", "请总结以下内容：" + content);
        requestBody.put("model", "gpt-3.5-turbo");
        requestBody.put("temperature", 0.7);

        StringEntity entity = new StringEntity(requestBody.toString());
        httpPost.setEntity(entity);

        // 执行请求
        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.getEntity().getContent())
            );
            StringBuilder responseContent = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                responseContent.append(line);
            }

            JSONObject responseJson = JSON.parseObject(responseContent.toString());
            return responseJson.getString("response");
        } finally {
            httpClient.close();
        }
    }

    private Process astrbotProcess;

    public void startAstrBot() throws Exception {
        try {
            // AstrBot 安装路径 - 使用相对路径，位于项目根目录下的 astrbot 文件夹
            String projectRoot = System.getProperty("user.dir");
            String astrbotPath = projectRoot + File.separator + ".." + File.separator + ".." + File.separator + "astrbot";
            File astrbotDir = new File(astrbotPath).getCanonicalFile();
            
            if (!astrbotDir.exists()) {
                throw new IOException("AstrBot directory not found at: " + astrbotDir.getAbsolutePath());
            }
            
            // 使用 astrbot run 命令启动，在 AstrBot 目录下执行
            ProcessBuilder processBuilder = new ProcessBuilder("cmd.exe", "/c", "start", "cmd.exe", "/k", "astrbot", "run");
            processBuilder.directory(astrbotDir);
            processBuilder.inheritIO();
            
            // 启动进程
            astrbotProcess = processBuilder.start();
            
            // 等待几秒让 AstrBot 启动
            Thread.sleep(3000);
            
            System.out.println("AstrBot started successfully from: " + astrbotDir.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("Failed to start AstrBot: " + e.getMessage());
            throw e;
        }
    }

    public void stopAstrBot() throws Exception {
        try {
            if (astrbotProcess != null && astrbotProcess.isAlive()) {
                astrbotProcess.destroy();
                System.out.println("AstrBot stopped successfully");
            } else {
                // 尝试通过任务管理器结束 AstrBot 相关进程
                Runtime.getRuntime().exec("taskkill /F /IM python.exe");
                System.out.println("AstrBot processes terminated");
            }
        } catch (Exception e) {
            System.err.println("Failed to stop AstrBot: " + e.getMessage());
            throw e;
        }
    }
}