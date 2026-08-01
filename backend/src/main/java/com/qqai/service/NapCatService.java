package com.qqai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.io.entity.StringEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;

@Service
public class NapCatService {

    private static final Logger log = LoggerFactory.getLogger(NapCatService.class);

    @Value("${napcat.api-url}")
    private String napcatApiUrl;

    @Value("${napcat.onebot-api-url:${napcat.api-url}}")
    private String onebotApiUrl;

    @Value("${napcat.token}")
    private String napcatToken;

    @Value("${napcat.webui-url:http://127.0.0.1:6099}")
    private String napcatWebuiUrl;

    @Value("${napcat.webhook-token:}")
    private String napcatWebhookToken;

    @Value("${server.port:8081}")
    private int serverPort;

    @Autowired
    private CloseableHttpClient httpClient;

    @Autowired
    private MediaDownloadService mediaDownloadService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String credential;

    private String getCredential() throws Exception {
        // 简化认证流程，直接返回token作为credential
        return napcatToken;
    }
    
    private void setAuthHeader(HttpGet httpGet) {
        // NapCat 使用 Authorization: Bearer token 格式
        httpGet.setHeader("Authorization", "Bearer " + napcatToken);
        // 也尝试其他可能的认证格式
        httpGet.setHeader("token", napcatToken);
        httpGet.setHeader("x-token", napcatToken);
    }

    private String generateSHA256(String input) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes());
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    public String getLoginQrCode() throws Exception {
        CloseableHttpClient httpClient = this.httpClient;
        
        // 尝试多个可能的 API 端点
        String[] possibleEndpoints = {
            "/api/QQLogin/GetQQLoginQrcode",
            "/QQLogin/GetQQLoginQrcode",
            "/api/Auth/GetLoginQrCode",
            "/auth/qrcode"
        };
        
        for (String endpoint : possibleEndpoints) {
            try {
                HttpGet httpGet = new HttpGet(napcatApiUrl + endpoint);
                httpGet.setHeader("Content-Type", "application/json");
                setAuthHeader(httpGet);

                try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.getEntity().getContent())
                    );
                    StringBuilder responseContent = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        responseContent.append(line);
                    }

                    String responseStr = responseContent.toString();
                    log.debug("QR code response from {}: {}", endpoint, responseStr);
                    
                    // 如果返回的是 JSON，尝试解析
                    if (responseStr.startsWith("{")) {
                        JsonNode responseJson = objectMapper.readTree(responseStr);
                        // 尝试不同的字段名
                        if (responseJson.has("qrcodeurl")) {
                            return responseJson.get("qrcodeurl").asText();
                        } else if (responseJson.has("qrCode")) {
                            return responseJson.get("qrCode").asText();
                        } else if (responseJson.has("url")) {
                            return responseJson.get("url").asText();
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("QR endpoint {} failed: {}", endpoint, e.getMessage());
            }
        }
        
        // 如果 API 调用都失败，返回本地二维码图片路径
        return "local:" + getQrCodePath();
    }

    public boolean checkLoginStatus() throws Exception {
        log.info("Checking NapCat login status...");
        
        // 方法1: 首先检查 NapCat API 是否可访问（优先检查实际登录状态）
        log.info("Method 1: Checking NapCat API accessibility...");
        if (isNapCatApiAccessible()) {
            // API 可访问，再检查配置文件
            try {
                String configPath = getNapCatConfigPath();
                File configDir = new File(configPath);
                
                if (configDir.exists() && configDir.isDirectory()) {
                    File[] configFiles = configDir.listFiles((dir, name) -> {
                        return name.startsWith("napcat_") 
                            && !name.startsWith("napcat_protocol_") 
                            && !name.startsWith("onebot11_")
                            && name.endsWith(".json");
                    });
                    
                    if (configFiles != null && configFiles.length > 0) {
                        for (File configFile : configFiles) {
                            String fileName = configFile.getName();
                            String qqNumber = fileName.replace("napcat_", "").replace(".json", "");
                            if (qqNumber.matches("\\d+")) {
                                log.info("✅ NapCat is logged in with QQ: {}", qqNumber);
                                return true;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Config check failed: {}", e.getMessage());
            }
        }
        
        // 方法2: 检查是否有未过期的二维码（如果有最近生成的二维码，说明正在等待登录）
        log.info("Method 2: Checking QR code status...");
        try {
            String qrCodePath = getNapCatQrCodePath();
            File qrCodeFile = new File(qrCodePath);
            if (qrCodeFile.exists()) {
                long lastModified = qrCodeFile.lastModified();
                long now = System.currentTimeMillis();
                long fiveMinutes = 5 * 60 * 1000;
                if (now - lastModified < fiveMinutes) {
                    log.info("Method 2: QR code is recent, NapCat is waiting for login");
                    return false;
                }
            }
        } catch (Exception e) {
            log.debug("QR code check failed: {}", e.getMessage());
        }
        
        // 方法3: 尝试调用 NapCat API 检查登录状态（备用方案）
        log.info("Method 3: Trying API endpoints...");
        CloseableHttpClient httpClient = this.httpClient;
        
        // NapCat 使用 URL 参数传递 token: ?token=xxx
        String authParam = "?token=" + napcatToken;
        
        // 尝试多个可能的 API 端点
        String[] possibleEndpoints = {
            "/api/QQLogin/CheckLoginStatus",
            "/QQLogin/CheckLoginStatus", 
            "/api/Auth/CheckLoginStatus",
            "/auth/check"
        };
        
        for (String endpoint : possibleEndpoints) {
            try {
                // 在 URL 中添加 token 参数
                HttpGet httpGet = new HttpGet(napcatApiUrl + endpoint + authParam);
                httpGet.setHeader("Content-Type", "application/json");

                try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.getEntity().getContent())
                    );
                    StringBuilder responseContent = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        responseContent.append(line);
                    }

                    String responseStr = responseContent.toString();
                    log.debug("Login status response from {}: {}", endpoint, responseStr);
                    
                    // 如果返回的是 JSON，尝试解析
                    if (responseStr.startsWith("{")) {
                        JsonNode responseJson = objectMapper.readTree(responseStr);

                        // 检查是否有错误
                        if (responseJson.has("code") && responseJson.get("code").asInt() == -1) {
                            log.debug("Auth failed for endpoint: {}", endpoint);
                            continue;
                        }

                        // 尝试不同的字段名
                        if (responseJson.has("isLogin")) {
                            boolean isLogin = responseJson.get("isLogin").asBoolean();
                            log.info("✅ Method 2 SUCCESS: isLogin = {}", isLogin);
                            return isLogin;
                        } else if (responseJson.has("loggedIn")) {
                            boolean loggedIn = responseJson.get("loggedIn").asBoolean();
                            log.info("✅ Method 2 SUCCESS: loggedIn = {}", loggedIn);
                            return loggedIn;
                        } else if (responseJson.has("login")) {
                            boolean login = responseJson.get("login").asBoolean();
                            log.info("✅ Method 2 SUCCESS: login = {}", login);
                            return login;
                        } else if (responseJson.has("data")) {
                            // 有些 API 返回 data 字段
                            JsonNode data = responseJson.get("data");
                            if (data != null && data.isObject()) {
                                if (data.has("isLogin")) {
                                    boolean isLogin = data.get("isLogin").asBoolean();
                                    log.info("✅ Method 2 SUCCESS: data.isLogin = {}", isLogin);
                                    return isLogin;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Endpoint {} failed: {}", endpoint, e.getMessage());
            }
        }
        
        log.warn("❌ All methods failed, returning false");
        return false;
    }
    
    private String getNapCatQrCodePath() throws Exception {
        String projectRoot = System.getProperty("user.dir");
        String qrCodePath = projectRoot + File.separator + ".." + File.separator + ".." + File.separator + "napcat" + File.separator + "NapCat.Shell" + File.separator + "cache" + File.separator + "qrcode.png";
        return new File(qrCodePath).getCanonicalFile().getAbsolutePath();
    }
    
    private boolean isNapCatApiAccessible() {
        try {
            CloseableHttpClient httpClient = this.httpClient;
            // 尝试访问 NapCat 的 WebUI 端点，使用 URL 参数传递 token
            HttpGet httpGet = new HttpGet(napcatApiUrl + "/api/QQLogin/CheckLoginStatus?token=" + napcatToken);
            
            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                int statusCode = response.getCode();
                log.debug("NapCat API check status: {}", statusCode);
                return statusCode == 200;
            }
        } catch (Exception e) {
            log.debug("NapCat API accessibility check failed: {}", e.getMessage());
            return false;
        }
    }
    
    private String getNapCatConfigPath() throws Exception {
        String projectRoot = System.getProperty("user.dir");
        String configPath = projectRoot + File.separator + ".." + File.separator + ".." + File.separator + "napcat" + File.separator + "NapCat.Shell" + File.separator + "config";
        return new File(configPath).getCanonicalFile().getAbsolutePath();
    }

    private String getLastQqNumber() {
        try {
            String configPath = getNapCatConfigPath();
            File configDir = new File(configPath);
            if (!configDir.exists() || !configDir.isDirectory()) {
                return null;
            }
            File[] files = configDir.listFiles((dir, name) -> name.startsWith("napcat_") && name.endsWith(".json") && !name.startsWith("napcat_protocol_"));
            if (files == null || files.length == 0) {
                return null;
            }
            // 按修改时间排序，取最新的
            File latestFile = files[0];
            for (File file : files) {
                if (file.lastModified() > latestFile.lastModified()) {
                    latestFile = file;
                }
            }
            String fileName = latestFile.getName();
            // napcat_2488130337.json -> 2488130337
            String qq = fileName.replace("napcat_", "").replace(".json", "");
            return qq.matches("\\d+") ? qq : null;
        } catch (Exception e) {
            log.error("获取最近QQ号失败: {}", e.getMessage());
            return null;
        }
    }

    private Process napcatProcess;

    public void startNapCat() throws Exception {
        try {
            String projectRoot = System.getProperty("user.dir");
            String napcatPath = projectRoot + File.separator + ".." + File.separator + ".." + File.separator + "napcat" + File.separator + "NapCat.Shell";
            File napcatDir = new File(napcatPath).getCanonicalFile();
            String launcherPath = napcatDir.getAbsolutePath() + File.separator + "launcher.bat";
            
            File launcherFile = new File(launcherPath);
            if (!launcherFile.exists()) {
                throw new IOException("NapCat launcher.bat not found at: " + launcherPath);
            }
            
            ProcessBuilder processBuilder = new ProcessBuilder("cmd.exe", "/c", "start", "/b", launcherPath);
            processBuilder.directory(napcatDir);
            
            napcatProcess = processBuilder.start();
            
            Thread.sleep(5000);
            
            log.info("NapCat started successfully from: {}", napcatDir.getAbsolutePath());
        } catch (Exception e) {
            log.error("Failed to start NapCat: {}", e.getMessage());
            throw e;
        }
    }

    public void stopNapCat() throws Exception {
        try {
            if (napcatProcess != null && napcatProcess.isAlive()) {
                napcatProcess.destroy();
                log.info("NapCat stopped successfully");
            } else {
                // 如果进程不存在，尝试通过任务管理器结束 QQ 和 NapCat 相关进程
                Runtime.getRuntime().exec("taskkill /F /IM QQ.exe");
                Runtime.getRuntime().exec("taskkill /F /IM NapCatWinBootMain.exe");
                log.info("NapCat processes terminated");
            }
        } catch (Exception e) {
            log.error("Failed to stop NapCat: {}", e.getMessage());
            throw e;
        }
    }

    public void subscribeToMessages() throws Exception {
        // 实现订阅消息的逻辑
        // 这里可以设置webhook或轮询机制来接收QQ消息
    }

    public String getQrCodePath() throws Exception {
        String projectRoot = System.getProperty("user.dir");
        String qrCodePath = projectRoot + File.separator + ".." + File.separator + ".." + File.separator + "napcat" + File.separator + "NapCat.Shell" + File.separator + "cache" + File.separator + "qrcode.png";
        return new File(qrCodePath).getCanonicalFile().getAbsolutePath();
    }

    public List<String> getConfiguredQqNumbers() throws Exception {
        List<String> qqNumbers = new ArrayList<>();
        String configPath = getNapCatConfigPath();
        File configDir = new File(configPath);
        if (!configDir.exists() || !configDir.isDirectory()) {
            return qqNumbers;
        }
        File[] files = configDir.listFiles((dir, name) ->
                name.startsWith("napcat_") && name.endsWith(".json")
                        && !name.startsWith("napcat_protocol_")
                        && !name.startsWith("napcat.json"));
        if (files == null || files.length == 0) {
            return qqNumbers;
        }
        for (File file : files) {
            String qq = file.getName().replace("napcat_", "").replace(".json", "");
            if (qq.matches("\\d+")) {
                qqNumbers.add(qq);
            }
        }
        return qqNumbers;
    }

    public boolean isOneBotConfigured(String qqNumber) throws Exception {
        String configPath = getNapCatConfigPath();
        File onebotConfig = new File(configPath, "onebot11_" + qqNumber + ".json");
        return onebotConfig.exists();
    }

    public boolean autoConfigureOneBot(String qqNumber) throws Exception {
        String configPath = getNapCatConfigPath();
        File onebotConfig = new File(configPath, "onebot11_" + qqNumber + ".json");
        
        String webhookUrl = "http://localhost:" + serverPort + "/?access_token=" + napcatWebhookToken;
        String token = napcatToken != null && !napcatToken.isBlank() ? napcatToken : "";

        ObjectNode root;
        
        if (onebotConfig.exists()) {
            log.info("QQ {} 的 onebot11 配置已存在，检查是否需要更新", qqNumber);
            root = (ObjectNode) objectMapper.readTree(onebotConfig);
        } else {
            root = objectMapper.createObjectNode();
        }

        ObjectNode network;
        if (root.has("network")) {
            network = (ObjectNode) root.get("network");
        } else {
            network = objectMapper.createObjectNode();
            root.set("network", network);
        }
        
        if (!network.has("httpServers") || network.get("httpServers").isNull() || !network.get("httpServers").isArray()) {
            ArrayNode httpServers = objectMapper.createArrayNode();
            ObjectNode httpServer = objectMapper.createObjectNode();
            httpServer.put("enable", true);
            httpServer.put("name", "OneBotHTTP");
            httpServer.put("host", "0.0.0.0");
            httpServer.put("port", 6100);
            httpServer.put("enableCors", true);
            httpServer.put("enableWebsocket", true);
            httpServer.put("messagePostFormat", "array");
            httpServer.put("token", token);
            httpServer.put("debug", false);
            httpServers.add(httpServer);
            network.set("httpServers", httpServers);
        }

        if (!network.has("httpSseServers") || network.get("httpSseServers").isNull() || !network.get("httpSseServers").isArray()) {
            network.set("httpSseServers", objectMapper.createArrayNode());
        }

        ArrayNode httpClients;
        if (network.has("httpClients") && network.get("httpClients").isArray()) {
            httpClients = (ArrayNode) network.get("httpClients");
        } else {
            httpClients = objectMapper.createArrayNode();
            network.set("httpClients", httpClients);
        }

        boolean hasBackendClient = false;
        for (int i = 0; i < httpClients.size(); i++) {
            ObjectNode client = (ObjectNode) httpClients.get(i);
            if ("铃音QQ对话后端".equals(client.get("name").asText())) {
                hasBackendClient = true;
                client.put("enable", true);
                client.put("url", webhookUrl);
                client.put("reportSelfMessage", true);
                client.put("messagePostFormat", "array");
                client.put("token", napcatWebhookToken != null ? napcatWebhookToken : "");
                client.put("debug", false);
                break;
            }
        }

        if (!hasBackendClient) {
            ObjectNode httpClient = objectMapper.createObjectNode();
            httpClient.put("enable", true);
            httpClient.put("name", "铃音QQ对话后端");
            httpClient.put("url", webhookUrl);
            httpClient.put("reportSelfMessage", true);
            httpClient.put("messagePostFormat", "array");
            httpClient.put("token", napcatWebhookToken != null ? napcatWebhookToken : "");
            httpClient.put("debug", false);
            httpClients.add(httpClient);
        }

        if (!network.has("websocketServers") || network.get("websocketServers").isNull() || !network.get("websocketServers").isArray()) {
            network.set("websocketServers", objectMapper.createArrayNode());
        }
        if (!network.has("websocketClients") || network.get("websocketClients").isNull() || !network.get("websocketClients").isArray()) {
            network.set("websocketClients", objectMapper.createArrayNode());
        }
        if (!network.has("plugins") || network.get("plugins").isNull() || !network.get("plugins").isArray()) {
            network.set("plugins", objectMapper.createArrayNode());
        }

        if (!root.has("musicSignUrl")) {
            root.put("musicSignUrl", "");
        }
        if (!root.has("enableLocalFile2Url")) {
            root.put("enableLocalFile2Url", false);
        }
        if (!root.has("parseMultMsg")) {
            root.put("parseMultMsg", true);
        }
        if (!root.has("imageDownloadProxy")) {
            root.put("imageDownloadProxy", "");
        }

        if (!root.has("timeout")) {
            ObjectNode timeout = objectMapper.createObjectNode();
            timeout.put("baseTimeout", 10000);
            timeout.put("uploadSpeedKBps", 256);
            timeout.put("downloadSpeedKBps", 256);
            timeout.put("maxTimeout", 1800000);
            root.set("timeout", timeout);
        }

        File parentDir = onebotConfig.getParentFile();
        if (!parentDir.exists()) {
            parentDir.mkdirs();
        }

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(onebotConfig, root);
        log.info("已为 QQ {} 配置/更新 onebot11，webhook: {}", qqNumber, webhookUrl);
        return true;
    }

    public void checkAndAutoConfigureNewQq() throws Exception {
        List<String> configuredQqs = getConfiguredQqNumbers();
        log.info("检测到已配置的 QQ 数量: {}", configuredQqs.size());
        for (String qq : configuredQqs) {
            if (!isOneBotConfigured(qq)) {
                log.info("发现新登录的 QQ: {}，正在自动配置 onebot11...", qq);
                autoConfigureOneBot(qq);
            }
        }
    }

    /**
     * 获取 NapCat WebUI 的访问 URL。
     * token 从后端配置读取，避免硬编码在前端。
     */
    public String getWebUiUrl() {
        String baseUrl = napcatWebuiUrl != null ? napcatWebuiUrl.trim() : "http://127.0.0.1:6099";
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String token = napcatToken != null ? napcatToken.trim() : "";
        if (!token.isEmpty()) {
            return baseUrl + "/webui?token=" + token;
        }
        return baseUrl + "/webui";
    }

    /**
     * 获取群成员列表
     */
    public ArrayNode getGroupMemberList(String groupId) {
        return getGroupMemberList(groupId, false);
    }

    /**
     * 获取群成员列表（支持强制刷新缓存）
     */
    public ArrayNode getGroupMemberList(String groupId, boolean noCache) {
        if (groupId == null || groupId.isBlank()) {
            return objectMapper.createArrayNode();
        }
        String baseUrl = onebotApiUrl != null && !onebotApiUrl.isBlank() ? onebotApiUrl : napcatApiUrl;
        CloseableHttpClient httpClient = this.httpClient;
        HttpPost httpPost = new HttpPost(baseUrl + "/get_group_member_list");
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("Authorization", "Bearer " + napcatToken);

        ObjectNode body = objectMapper.createObjectNode();
        try {
            body.put("group_id", Long.parseLong(groupId));
        } catch (NumberFormatException e) {
            body.put("group_id", groupId);
        }
        body.put("no_cache", noCache);
        httpPost.setEntity(new StringEntity(body.toString()));

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.getEntity().getContent())
            );
            StringBuilder responseContent = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                responseContent.append(line);
            }
            String responseStr = responseContent.toString();
            log.debug("获取群{}成员列表响应: {}", groupId, responseStr.length() > 500 ? responseStr.substring(0, 500) + "..." : responseStr);

            JsonNode json = objectMapper.readTree(responseStr);
            if ("ok".equals(json.get("status").asText()) && json.has("data")) {
                ArrayNode data = (ArrayNode) json.get("data");
                log.info("获取群{}成员列表成功，共 {} 人", groupId, data.size());
                return data;
            } else {
                log.warn("获取群成员列表返回非预期状态: status={}, retcode={}", json.has("status") ? json.get("status").asText() : "null", json.has("retcode") ? json.get("retcode").asInt() : "null");
            }
        } catch (Exception e) {
            log.error("获取群成员列表失败 (groupId={}): {}", groupId, e.getMessage());
            e.printStackTrace();
        }
        return objectMapper.createArrayNode();
    }

    /**
     * 获取单个群成员信息（用于列表查询失败或缺少特定成员时的兜底）
     */
    public ObjectNode getGroupMemberInfo(String groupId, String userId) {
        if (groupId == null || groupId.isBlank() || userId == null || userId.isBlank()) {
            return null;
        }
        String baseUrl = onebotApiUrl != null && !onebotApiUrl.isBlank() ? onebotApiUrl : napcatApiUrl;
        CloseableHttpClient httpClient = this.httpClient;
        HttpPost httpPost = new HttpPost(baseUrl + "/get_group_member_info");
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("Authorization", "Bearer " + napcatToken);

        ObjectNode body = objectMapper.createObjectNode();
        try {
            body.put("group_id", Long.parseLong(groupId));
        } catch (NumberFormatException e) {
            body.put("group_id", groupId);
        }
        try {
            body.put("user_id", Long.parseLong(userId));
        } catch (NumberFormatException e) {
            body.put("user_id", userId);
        }
        body.put("no_cache", false);
        httpPost.setEntity(new StringEntity(body.toString()));

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.getEntity().getContent())
            );
            StringBuilder responseContent = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                responseContent.append(line);
            }
            String responseStr = responseContent.toString();
            log.debug("获取群{}成员{}信息响应: {}", groupId, userId, responseStr.length() > 300 ? responseStr.substring(0, 300) + "..." : responseStr);

            JsonNode json = objectMapper.readTree(responseStr);
            if ("ok".equals(json.get("status").asText()) && json.has("data")) {
                return (ObjectNode) json.get("data");
            }
        } catch (Exception e) {
            log.error("获取群成员信息失败 (groupId={}, userId={}): {}", groupId, userId, e.getMessage());
        }
        return null;
    }

    /**
     * 获取群列表
     */
    public ArrayNode getGroupList() throws Exception {
        String baseUrl = onebotApiUrl != null && !onebotApiUrl.isBlank() ? onebotApiUrl : napcatApiUrl;
        CloseableHttpClient httpClient = this.httpClient;
        HttpPost httpPost = new HttpPost(baseUrl + "/get_group_list");
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("Authorization", "Bearer " + napcatToken);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("no_cache", false);
        httpPost.setEntity(new StringEntity(body.toString()));

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.getEntity().getContent())
            );
            StringBuilder responseContent = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                responseContent.append(line);
            }
            String responseStr = responseContent.toString();
            log.debug("获取群列表响应: {}", responseStr);

            JsonNode json = objectMapper.readTree(responseStr);
            if ("ok".equals(json.get("status").asText()) && json.has("data")) {
                return (ArrayNode) json.get("data");
            }
            return objectMapper.createArrayNode();
        }
    }

    /**
     * 获取合并转发消息详情
     */
    public ArrayNode getForwardMsg(String forwardId) {
        if (forwardId == null || forwardId.isBlank()) {
            return objectMapper.createArrayNode();
        }
        String baseUrl = onebotApiUrl != null && !onebotApiUrl.isBlank() ? onebotApiUrl : napcatApiUrl;
        CloseableHttpClient httpClient = this.httpClient;

        // OneBot 11 标准参数是 message_id，部分实现也支持 id
        String[][] paramKeys = {{"message_id", forwardId}, {"id", forwardId}};
        for (String[] param : paramKeys) {
            try {
                HttpPost httpPost = new HttpPost(baseUrl + "/get_forward_msg");
                httpPost.setHeader("Content-Type", "application/json");
                httpPost.setHeader("Authorization", "Bearer " + napcatToken);

                ObjectNode body = objectMapper.createObjectNode();
                body.put(param[0], param[1]);
                httpPost.setEntity(new StringEntity(body.toString()));

                try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.getEntity().getContent())
                    );
                    StringBuilder responseContent = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        responseContent.append(line);
                    }
                    String responseStr = responseContent.toString();
                    log.debug("获取合并转发消息详情响应 ({}={}): {}", param[0], forwardId,
                            responseStr.length() > 500 ? responseStr.substring(0, 500) + "..." : responseStr);

                    JsonNode json = objectMapper.readTree(responseStr);
                    if ("ok".equals(json.get("status").asText()) && json.has("data")) {
                        ObjectNode data = (ObjectNode) json.get("data");
                        if (data != null && data.has("messages")) {
                            ArrayNode messages = (ArrayNode) data.get("messages");
                            if (messages != null && !messages.isEmpty()) {
                                return normalizeForwardMessages(messages);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("获取合并转发消息详情失败 ({}={}): {}", param[0], forwardId, e.getMessage());
            }
        }
        return objectMapper.createArrayNode();
    }

    /**
     * 从 NapCat 上报的 payload 的 message 数组中提取合并转发消息的子消息。
     * 当 NapCat 配置 parseMultMsg=true 时，forward 类型的 segment 的 data.content 中
     * 会包含解析后的子消息数组。
     */
    public ArrayNode extractForwardMessagesFromPayload(ObjectNode payload) {
        if (payload == null) {
            return null;
        }
        ArrayNode messageArray = (ArrayNode) payload.get("message");
        if (messageArray == null || messageArray.isEmpty()) {
            return null;
        }
        for (int i = 0; i < messageArray.size(); i++) {
            ObjectNode segment = (ObjectNode) messageArray.get(i);
            if (segment == null) {
                continue;
            }
            if ("forward".equals(segment.get("type").asText())) {
                ObjectNode data = (ObjectNode) segment.get("data");
                if (data != null) {
                    ArrayNode content = (ArrayNode) data.get("content");
                    if (content != null && !content.isEmpty()) {
                        return normalizeForwardMessages(content);
                    }
                }
            }
        }
        return null;
    }

    /**
     * 将 NapCat 返回的合并转发消息数组转换为前端期望的统一格式。
     * 统一字段：id, userQq, userNickname, sendTime(毫秒时间戳), content, messageType。
     */
    public ArrayNode normalizeForwardMessages(ArrayNode rawMessages) {
        ArrayNode result = objectMapper.createArrayNode();
        if (rawMessages == null) {
            return result;
        }
        for (int i = 0; i < rawMessages.size(); i++) {
            ObjectNode raw = (ObjectNode) rawMessages.get(i);
            if (raw == null) continue;

            ObjectNode normalized = objectMapper.createObjectNode();
            // 消息ID
            JsonNode msgIdObj = raw.get("message_id");
            if (msgIdObj == null) msgIdObj = raw.get("msgId");
            if (msgIdObj == null) msgIdObj = raw.get("real_id");
            normalized.put("id", msgIdObj != null ? msgIdObj.asText() : ("forward-msg-" + i));

            // 发送者信息
            ObjectNode sender = (ObjectNode) raw.get("sender");
            if (sender != null) {
                JsonNode userIdObj = sender.get("user_id");
                if (userIdObj == null) userIdObj = sender.get("userId");
                normalized.put("userQq", userIdObj != null ? userIdObj.asText() : "");
                normalized.put("userNickname", sender.has("nickname") ? sender.get("nickname").asText() : "");
            } else {
                normalized.put("userQq", "");
                normalized.put("userNickname", raw.has("nickname") ? raw.get("nickname").asText() : "");
            }

            // 时间：NapCat 通常返回秒级时间戳，前端 JS Date 需要毫秒
            long timeSeconds = raw.has("time") ? raw.get("time").asLong() : 0;
            if (timeSeconds == 0 && raw.has("timestamp")) timeSeconds = raw.get("timestamp").asLong();
            if (timeSeconds != 0) {
                normalized.put("sendTime", timeSeconds * 1000);
                normalized.put("timestamp", timeSeconds * 1000);
            }

            // 内容：优先 raw_message，其次从 message 数组构造
            String content = raw.has("raw_message") ? raw.get("raw_message").asText() : null;
            // 嵌套合并转发的子消息可能位于 data.content 中
            ArrayNode childMessageArray = (ArrayNode) raw.get("message");
            if (childMessageArray == null || childMessageArray.isEmpty()) {
                ObjectNode data = (ObjectNode) raw.get("data");
                if (data != null) {
                    childMessageArray = (ArrayNode) data.get("content");
                }
            }
            if (content == null || content.isEmpty()) {
                content = buildRawMessageFromArray(childMessageArray);
            }
            normalized.put("content", content != null ? content : "");
            String messageType = resolveForwardMessageType(content);
            normalized.put("messageType", messageType);

            // 嵌套合并转发：从 message 数组或 data.content 中递归解析子消息
            if ("FORWARD".equals(messageType) && childMessageArray != null && !childMessageArray.isEmpty()) {
                ArrayNode nestedForwardMessages = null;
                ObjectNode firstChild = (ObjectNode) childMessageArray.get(0);
                // 子数组元素如果是 segment（有 type 字段），需要先提取 forward segment 的 data.content
                if (firstChild != null && firstChild.has("type")) {
                    for (int j = 0; j < childMessageArray.size(); j++) {
                        ObjectNode seg = (ObjectNode) childMessageArray.get(j);
                        if (seg != null && "forward".equals(seg.get("type").asText())) {
                            ObjectNode segData = (ObjectNode) seg.get("data");
                            if (segData != null) {
                                ArrayNode segContent = (ArrayNode) segData.get("content");
                                if (segContent != null && !segContent.isEmpty()) {
                                    nestedForwardMessages = normalizeForwardMessages(segContent);
                                    break;
                                }
                            }
                        }
                    }
                } else {
                    // 子数组元素已经是完整 message 对象
                    nestedForwardMessages = normalizeForwardMessages(childMessageArray);
                }
                if (nestedForwardMessages != null && !nestedForwardMessages.isEmpty()) {
                    normalized.set("forwardMessages", nestedForwardMessages);
                }
            }

            result.add(normalized);
        }
        return result;
    }

    /**
     * 下载合并转发子消息中的远程媒体到本地，并在子消息对象中补充 localUrl 字段。
     * 递归处理嵌套的合并转发消息。
     */
    public void downloadForwardMediaToLocal(ArrayNode forwardMessages, String groupId) {
        if (forwardMessages == null || forwardMessages.isEmpty() || groupId == null || groupId.isBlank()) {
            return;
        }
        for (int i = 0; i < forwardMessages.size(); i++) {
            ObjectNode msg = (ObjectNode) forwardMessages.get(i);
            if (msg == null) continue;
            String type = msg.has("messageType") ? msg.get("messageType").asText() : "";
            String content = msg.has("content") ? msg.get("content").asText() : "";

            String localUrl = null;
            if (content != null && !content.isBlank()) {
                try {
                    switch (type) {
                        case "IMAGE":
                            localUrl = mediaDownloadService.downloadMediaToLocal(content, groupId, "images", ".jpg");
                            break;
                        case "VIDEO":
                            localUrl = mediaDownloadService.downloadMediaToLocal(content, groupId, "video", ".mp4");
                            break;
                        case "VOICE":
                        case "AUDIO":
                        case "RECORD":
                            localUrl = mediaDownloadService.downloadMediaToLocal(content, groupId, "voice", ".amr");
                            break;
                        default:
                            break;
                    }
                } catch (Exception e) {
                    log.error("下载合并转发子消息媒体失败 ({}): {}", type, e.getMessage());
                }
            }
            if (localUrl != null && !localUrl.isBlank()) {
                msg.put("localUrl", localUrl);
            }

            // 递归处理嵌套合并转发
            if ("FORWARD".equals(type)) {
                ArrayNode nested = (ArrayNode) msg.get("forwardMessages");
                if (nested != null && !nested.isEmpty()) {
                    downloadForwardMediaToLocal(nested, groupId);
                }
            }
        }
    }

    /**
     * 从 OneBot 11 message 数组拼接成 raw_message 风格字符串（简单兜底）。
     */
    private String buildRawMessageFromArray(ArrayNode messageArray) {
        if (messageArray == null || messageArray.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messageArray.size(); i++) {
            ObjectNode segment = (ObjectNode) messageArray.get(i);
            if (segment == null) continue;
            String type = segment.has("type") ? segment.get("type").asText() : "";
            ObjectNode data = (ObjectNode) segment.get("data");
            if (data == null) continue;
            switch (type) {
                case "text":
                    sb.append(data.has("text") ? data.get("text").asText() : "");
                    break;
                case "at":
                    sb.append("[CQ:at,qq=").append(data.has("qq") ? data.get("qq").asText() : "").append("]");
                    break;
                case "image":
                    sb.append("[CQ:image,file=").append(data.has("file") ? data.get("file").asText() : "").append(",url=").append(data.has("url") ? data.get("url").asText() : "").append("]");
                    break;
                case "video":
                    sb.append("[CQ:video,file=").append(data.has("file") ? data.get("file").asText() : "").append(",url=").append(data.has("url") ? data.get("url").asText() : "").append("]");
                    break;
                case "record":
                case "voice":
                    sb.append("[CQ:record,file=").append(data.has("file") ? data.get("file").asText() : "").append("]");
                    break;
                case "face":
                    sb.append("[CQ:face,id=").append(data.has("id") ? data.get("id").asText() : "").append("]");
                    break;
                case "forward":
                    sb.append("[CQ:forward,id=").append(data.has("id") ? data.get("id").asText() : "").append("]");
                    break;
                case "reply":
                    sb.append("[CQ:reply,id=").append(data.has("id") ? data.get("id").asText() : "").append("]");
                    break;
                default:
                    sb.append("[").append(type).append("]");
                    break;
            }
        }
        return sb.toString();
    }

    /**
     * 根据内容中的 CQ 码判断消息类型，供合并转发子消息使用。
     */
    private String resolveForwardMessageType(String content) {
        if (content == null || content.isEmpty()) return "TEXT";
        if (content.contains("[CQ:image")) return "IMAGE";
        if (content.contains("[CQ:video")) return "VIDEO";
        if (content.contains("[CQ:record") || content.contains("[CQ:voice")) return "VOICE";
        if (content.contains("[CQ:forward")) return "FORWARD";
        if (content.contains("[CQ:reply")) return "REPLY";
        if (content.contains("[CQ:face")) return "FACE";
        return "TEXT";
    }

    /**
     * 获取群历史消息
     */
    public ArrayNode getGroupMessageHistory(long groupId, int count) throws Exception {
        String baseUrl = onebotApiUrl != null && !onebotApiUrl.isBlank() ? onebotApiUrl : napcatApiUrl;
        CloseableHttpClient httpClient = this.httpClient;
        HttpPost httpPost = new HttpPost(baseUrl + "/get_group_msg_history");
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("Authorization", "Bearer " + napcatToken);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("group_id", groupId);
        body.put("count", count);
        httpPost.setEntity(new StringEntity(body.toString()));

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.getEntity().getContent())
            );
            StringBuilder responseContent = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                responseContent.append(line);
            }
            String responseStr = responseContent.toString();
            log.debug("获取群{}历史消息响应: {}", groupId, responseStr.length() > 200 ? responseStr.substring(0, 200) + "..." : responseStr);

            JsonNode json = objectMapper.readTree(responseStr);
            if ("ok".equals(json.get("status").asText()) && json.has("data")) {
                ObjectNode data = (ObjectNode) json.get("data");
                if (data.has("messages")) {
                    return (ArrayNode) data.get("messages");
                }
            }
            return objectMapper.createArrayNode();
        }
    }

    /**
     * 发送私聊消息到指定QQ号
     * @param qqNumber 目标QQ号
     * @param message 消息内容
     * @return 是否发送成功
     */
    public boolean sendPrivateMessage(String qqNumber, String message) {
        if (qqNumber == null || qqNumber.isBlank() || message == null || message.isBlank()) {
            log.warn("发送私信参数为空: qqNumber={}, message={}", qqNumber, message);
            return false;
        }

        CloseableHttpClient httpClient = this.httpClient;
        HttpPost httpPost = new HttpPost(napcatApiUrl + "/send_private_msg?token=" + napcatToken);
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("Authorization", "Bearer " + napcatToken);
        httpPost.setHeader("token", napcatToken);

        ObjectNode body = objectMapper.createObjectNode();
        try {
            body.put("user_id", Long.parseLong(qqNumber));
        } catch (NumberFormatException e) {
            body.put("user_id", qqNumber);
        }

        body.put("message", message);

        try {
            httpPost.setEntity(new StringEntity(body.toString(), java.nio.charset.StandardCharsets.UTF_8));
            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.getEntity().getContent())
                );
                StringBuilder responseContent = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    responseContent.append(line);
                }
                String responseStr = responseContent.toString();
                log.info("发送私信到QQ{}响应: {}", qqNumber, responseStr);

                JsonNode json = objectMapper.readTree(responseStr);
                boolean success = "ok".equals(json.get("status").asText());
                if (success) {
                    log.info("私信发送成功到QQ {}", qqNumber);
                } else {
                    log.warn("私信发送失败到QQ {}: {}", qqNumber, responseStr);
                }
                return success;
            }
        } catch (Exception e) {
            log.error("发送私信到QQ {} 异常: {}", qqNumber, e.getMessage());
            return false;
        }
    }
}
