package com.qqai.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

@Service
public class AstrBotService {

    private static final Logger log = LoggerFactory.getLogger(AstrBotService.class);

    @Value("${astrbot.api-url}")
    private String astrBotApiUrl;

    @Value("${astrbot.token}")
    private String astrBotToken;

    @Autowired
    private CloseableHttpClient httpClient;

    private Process astrbotProcess;

    public String summarizeMessage(String content) throws Exception {
        HttpPost httpPost = new HttpPost(astrBotApiUrl + "/api/v1/chat");
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("Authorization", "Bearer " + astrBotToken);

        JSONObject requestBody = new JSONObject();
        requestBody.put("message", "请总结以下内容：" + content);
        requestBody.put("model", "gpt-3.5-turbo");
        requestBody.put("temperature", 0.7);

        httpPost.setEntity(new StringEntity(requestBody.toString()));

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
        }
    }

    public void startAstrBot() throws Exception {
        String projectRoot = System.getProperty("user.dir");
        String astrbotPath = projectRoot + File.separator + ".." + File.separator + ".." + File.separator + "astrbot";
        File astrbotDir = new File(astrbotPath).getCanonicalFile();

        if (!astrbotDir.exists()) {
            throw new IOException("AstrBot directory not found at: " + astrbotDir.getAbsolutePath());
        }

        ProcessBuilder processBuilder = new ProcessBuilder("cmd.exe", "/c", "start", "cmd.exe", "/k", "astrbot", "run");
        processBuilder.directory(astrbotDir);
        processBuilder.inheritIO();

        astrbotProcess = processBuilder.start();
        Thread.sleep(3000);
        log.info("AstrBot started from: {}", astrbotDir.getAbsolutePath());
    }

    public void stopAstrBot() throws Exception {
        if (astrbotProcess != null && astrbotProcess.isAlive()) {
            astrbotProcess.destroyForcibly();
            astrbotProcess = null;
            log.info("AstrBot stopped");
            return;
        }
        log.warn("AstrBot process not found, skip stop");
    }
}
