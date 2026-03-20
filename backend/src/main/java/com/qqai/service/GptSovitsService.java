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
public class GptSovitsService {
    @Value("${gpt-sovits.api-url}")
    private String gptSovitsApiUrl;

    @Value("${gpt-sovits.token}")
    private String gptSovitsToken;

    public String generateVoice(String text, String speaker) throws Exception {
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost(gptSovitsApiUrl + "/api/generate");

        // 设置请求头
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("Authorization", "Bearer " + gptSovitsToken);

        // 构建请求体
        JSONObject requestBody = new JSONObject();
        requestBody.put("text", text);
        requestBody.put("speaker", speaker);
        requestBody.put("speed", 1.0);
        requestBody.put("pitch", 1.0);

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
            return responseJson.getString("audio_url");
        } finally {
            httpClient.close();
        }
    }

    private Process gptSovitsProcess;

    public void startGptSovits() throws Exception {
        try {
            // GPT-SoVITS 路径 - 使用相对路径，位于项目根目录下的 GPT-SoVITS-v2pro-20250604-nvidia50 文件夹
            String projectRoot = System.getProperty("user.dir");
            String gptSovitsPath = projectRoot + File.separator + ".." + File.separator + ".." + File.separator + "GPT-SoVITS-v2pro-20250604-nvidia50";
            File gptSovitsDir = new File(gptSovitsPath).getCanonicalFile();
            String pythonPath = gptSovitsDir.getAbsolutePath() + File.separator + "runtime" + File.separator + "python.exe";
            String apiScriptPath = gptSovitsDir.getAbsolutePath() + File.separator + "api_v2.py";
            
            File pythonFile = new File(pythonPath);
            if (!pythonFile.exists()) {
                throw new IOException("GPT-SoVITS python.exe not found at: " + pythonPath);
            }
            
            // 构建进程
            ProcessBuilder processBuilder = new ProcessBuilder(
                "cmd.exe", "/c", "start", "cmd.exe", "/k",
                pythonPath, "-I", apiScriptPath, "-a", "127.0.0.1", "-p", "8000"
            );
            processBuilder.directory(gptSovitsDir);
            processBuilder.inheritIO();
            
            // 启动进程
            gptSovitsProcess = processBuilder.start();
            
            // 等待几秒让 GPT-SoVITS 启动
            Thread.sleep(5000);
            
            System.out.println("GPT-SoVITS started successfully from: " + gptSovitsDir.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("Failed to start GPT-SoVITS: " + e.getMessage());
            throw e;
        }
    }

    public void stopGptSovits() throws Exception {
        try {
            if (gptSovitsProcess != null && gptSovitsProcess.isAlive()) {
                gptSovitsProcess.destroy();
                System.out.println("GPT-SoVITS stopped successfully");
            } else {
                // 尝试通过任务管理器结束 GPT-SoVITS 相关进程
                Runtime.getRuntime().exec("taskkill /F /IM python.exe");
                System.out.println("GPT-SoVITS processes terminated");
            }
        } catch (Exception e) {
            System.err.println("Failed to stop GPT-SoVITS: " + e.getMessage());
            throw e;
        }
    }
}