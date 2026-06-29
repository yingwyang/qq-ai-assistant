package com.qqai.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.io.entity.StringEntity;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.io.IOException;

@Service
public class NapCatService {
    @Value("${napcat.api-url}")
    private String napcatApiUrl;

    @Value("${napcat.onebot-api-url:${napcat.api-url}}")
    private String onebotApiUrl;

    @Value("${napcat.token}")
    private String napcatToken;

    @Autowired
    private CloseableHttpClient httpClient;

    @Autowired
    private MediaDownloadService mediaDownloadService;

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
                    System.out.println("QR code response from " + endpoint + ": " + responseStr);
                    
                    // 如果返回的是 JSON，尝试解析
                    if (responseStr.startsWith("{")) {
                        JSONObject responseJson = JSON.parseObject(responseStr);
                        // 尝试不同的字段名
                        if (responseJson.containsKey("qrcodeurl")) {
                            return responseJson.getString("qrcodeurl");
                        } else if (responseJson.containsKey("qrCode")) {
                            return responseJson.getString("qrCode");
                        } else if (responseJson.containsKey("url")) {
                            return responseJson.getString("url");
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("QR endpoint " + endpoint + " failed: " + e.getMessage());
            }
        }
        
        // 如果 API 调用都失败，返回本地二维码图片路径
        return "local:" + getQrCodePath();
    }

    public boolean checkLoginStatus() throws Exception {
        System.out.println("Checking NapCat login status...");
        
        // 方法1: 首先检查 NapCat API 是否可访问（优先检查实际登录状态）
        System.out.println("Method 1: Checking NapCat API accessibility...");
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
                                System.out.println("✅ NapCat is logged in with QQ: " + qqNumber);
                                return true;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("Config check failed: " + e.getMessage());
            }
        }
        
        // 方法2: 检查是否有未过期的二维码（如果有最近生成的二维码，说明正在等待登录）
        System.out.println("Method 2: Checking QR code status...");
        try {
            String qrCodePath = getNapCatQrCodePath();
            File qrCodeFile = new File(qrCodePath);
            if (qrCodeFile.exists()) {
                long lastModified = qrCodeFile.lastModified();
                long now = System.currentTimeMillis();
                long fiveMinutes = 5 * 60 * 1000;
                if (now - lastModified < fiveMinutes) {
                    System.out.println("Method 2: QR code is recent, NapCat is waiting for login");
                    return false;
                }
            }
        } catch (Exception e) {
            System.out.println("QR code check failed: " + e.getMessage());
        }
        
        // 方法3: 尝试调用 NapCat API 检查登录状态（备用方案）
        System.out.println("Method 3: Trying API endpoints...");
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
                    System.out.println("Login status response from " + endpoint + ": " + responseStr);
                    
                    // 如果返回的是 JSON，尝试解析
                    if (responseStr.startsWith("{")) {
                        JSONObject responseJson = JSON.parseObject(responseStr);
                        
                        // 检查是否有错误
                        if (responseJson.containsKey("code") && responseJson.getIntValue("code") == -1) {
                            System.out.println("Auth failed for endpoint: " + endpoint);
                            continue;
                        }
                        
                        // 尝试不同的字段名
                        if (responseJson.containsKey("isLogin")) {
                            boolean isLogin = responseJson.getBoolean("isLogin");
                            System.out.println("✅ Method 2 SUCCESS: isLogin = " + isLogin);
                            return isLogin;
                        } else if (responseJson.containsKey("loggedIn")) {
                            boolean loggedIn = responseJson.getBoolean("loggedIn");
                            System.out.println("✅ Method 2 SUCCESS: loggedIn = " + loggedIn);
                            return loggedIn;
                        } else if (responseJson.containsKey("login")) {
                            boolean login = responseJson.getBoolean("login");
                            System.out.println("✅ Method 2 SUCCESS: login = " + login);
                            return login;
                        } else if (responseJson.containsKey("data")) {
                            // 有些 API 返回 data 字段
                            Object data = responseJson.get("data");
                            if (data instanceof JSONObject) {
                                JSONObject dataJson = (JSONObject) data;
                                if (dataJson.containsKey("isLogin")) {
                                    boolean isLogin = dataJson.getBoolean("isLogin");
                                    System.out.println("✅ Method 2 SUCCESS: data.isLogin = " + isLogin);
                                    return isLogin;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("Endpoint " + endpoint + " failed: " + e.getMessage());
            }
        }
        
        System.out.println("❌ All methods failed, returning false");
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
                System.out.println("NapCat API check status: " + statusCode);
                return statusCode == 200;
            }
        } catch (Exception e) {
            System.out.println("NapCat API accessibility check failed: " + e.getMessage());
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
            System.err.println("获取最近QQ号失败: " + e.getMessage());
            return null;
        }
    }

    private Process napcatProcess;

    public void startNapCat() throws Exception {
        startNapCat(false);
    }

    public void startNapCat(boolean autoLogin) throws Exception {
        try {
            // NapCat 启动脚本路径 - 使用相对路径
            String projectRoot = System.getProperty("user.dir");
            String napcatPath = projectRoot + File.separator + ".." + File.separator + ".." + File.separator + "napcat" + File.separator + "NapCat.Shell";
            File napcatDir = new File(napcatPath).getCanonicalFile();
            String launcherPath = napcatDir.getAbsolutePath() + File.separator + "launcher.bat";
            
            File launcherFile = new File(launcherPath);
            if (!launcherFile.exists()) {
                throw new IOException("NapCat launcher.bat not found at: " + launcherPath);
            }
            
            // 构建进程命令 - 使用后台启动，不显示弹窗
            String qq = autoLogin ? getLastQqNumber() : null;
            ProcessBuilder processBuilder;
            if (qq != null && !qq.isEmpty()) {
                processBuilder = new ProcessBuilder("cmd.exe", "/c", "start", "/b", launcherPath, qq);
                System.out.println("Starting NapCat with QQ: " + qq);
            } else {
                processBuilder = new ProcessBuilder("cmd.exe", "/c", "start", "/b", launcherPath);
                System.out.println("Starting NapCat without QQ number (QR code login)");
            }
            processBuilder.directory(napcatDir);
            
            // 启动进程
            napcatProcess = processBuilder.start();
            
            // 等待几秒让 NapCat 启动
            Thread.sleep(5000);
            
            System.out.println("NapCat started successfully from: " + napcatDir.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("Failed to start NapCat: " + e.getMessage());
            throw e;
        }
    }

    public void stopNapCat() throws Exception {
        try {
            if (napcatProcess != null && napcatProcess.isAlive()) {
                napcatProcess.destroy();
                System.out.println("NapCat stopped successfully");
            } else {
                // 如果进程不存在，尝试通过任务管理器结束 QQ 和 NapCat 相关进程
                Runtime.getRuntime().exec("taskkill /F /IM QQ.exe");
                Runtime.getRuntime().exec("taskkill /F /IM NapCatWinBootMain.exe");
                System.out.println("NapCat processes terminated");
            }
        } catch (Exception e) {
            System.err.println("Failed to stop NapCat: " + e.getMessage());
            throw e;
        }
    }

    public void subscribeToMessages() throws Exception {
        // 实现订阅消息的逻辑
        // 这里可以设置webhook或轮询机制来接收QQ消息
    }

    public String getQrCodePath() throws Exception {
        // 返回本地二维码图片的路径 - 使用相对路径
        String projectRoot = System.getProperty("user.dir");
        String qrCodePath = projectRoot + File.separator + ".." + File.separator + ".." + File.separator + "napcat" + File.separator + "NapCat.Shell" + File.separator + "cache" + File.separator + "qrcode.png";
        return new File(qrCodePath).getCanonicalFile().getAbsolutePath();
    }

    /**
     * 获取群成员列表
     */
    public JSONArray getGroupMemberList(String groupId) {
        return getGroupMemberList(groupId, false);
    }

    /**
     * 获取群成员列表（支持强制刷新缓存）
     */
    public JSONArray getGroupMemberList(String groupId, boolean noCache) {
        if (groupId == null || groupId.isBlank()) {
            return new JSONArray();
        }
        String baseUrl = onebotApiUrl != null && !onebotApiUrl.isBlank() ? onebotApiUrl : napcatApiUrl;
        CloseableHttpClient httpClient = this.httpClient;
        HttpPost httpPost = new HttpPost(baseUrl + "/get_group_member_list");
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("Authorization", "Bearer " + napcatToken);

        JSONObject body = new JSONObject();
        try {
            body.put("group_id", Long.parseLong(groupId));
        } catch (NumberFormatException e) {
            body.put("group_id", groupId);
        }
        body.put("no_cache", noCache);
        httpPost.setEntity(new StringEntity(body.toJSONString()));

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
            System.out.println("获取群" + groupId + "成员列表响应: " + (responseStr.length() > 500 ? responseStr.substring(0, 500) + "..." : responseStr));

            JSONObject json = JSON.parseObject(responseStr);
            if ("ok".equals(json.getString("status")) && json.containsKey("data")) {
                JSONArray data = json.getJSONArray("data");
                System.out.println("获取群" + groupId + "成员列表成功，共 " + data.size() + " 人");
                return data;
            } else {
                System.err.println("获取群成员列表返回非预期状态: status=" + json.getString("status") + ", retcode=" + json.getInteger("retcode"));
            }
        } catch (Exception e) {
            System.err.println("获取群成员列表失败 (groupId=" + groupId + "): " + e.getMessage());
            e.printStackTrace();
        }
        return new JSONArray();
    }

    /**
     * 获取单个群成员信息（用于列表查询失败或缺少特定成员时的兜底）
     */
    public JSONObject getGroupMemberInfo(String groupId, String userId) {
        if (groupId == null || groupId.isBlank() || userId == null || userId.isBlank()) {
            return null;
        }
        String baseUrl = onebotApiUrl != null && !onebotApiUrl.isBlank() ? onebotApiUrl : napcatApiUrl;
        CloseableHttpClient httpClient = this.httpClient;
        HttpPost httpPost = new HttpPost(baseUrl + "/get_group_member_info");
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("Authorization", "Bearer " + napcatToken);

        JSONObject body = new JSONObject();
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
        httpPost.setEntity(new StringEntity(body.toJSONString()));

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
            System.out.println("获取群" + groupId + "成员" + userId + "信息响应: " + (responseStr.length() > 300 ? responseStr.substring(0, 300) + "..." : responseStr));

            JSONObject json = JSON.parseObject(responseStr);
            if ("ok".equals(json.getString("status")) && json.containsKey("data")) {
                return json.getJSONObject("data");
            }
        } catch (Exception e) {
            System.err.println("获取群成员信息失败 (groupId=" + groupId + ", userId=" + userId + "): " + e.getMessage());
        }
        return null;
    }

    /**
     * 获取群列表
     */
    public JSONArray getGroupList() throws Exception {
        String baseUrl = onebotApiUrl != null && !onebotApiUrl.isBlank() ? onebotApiUrl : napcatApiUrl;
        CloseableHttpClient httpClient = this.httpClient;
        HttpPost httpPost = new HttpPost(baseUrl + "/get_group_list");
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("Authorization", "Bearer " + napcatToken);

        JSONObject body = new JSONObject();
        body.put("no_cache", false);
        httpPost.setEntity(new StringEntity(body.toJSONString()));

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
            System.out.println("获取群列表响应: " + responseStr);

            JSONObject json = JSON.parseObject(responseStr);
            if ("ok".equals(json.getString("status")) && json.containsKey("data")) {
                return json.getJSONArray("data");
            }
            return new JSONArray();
        }
    }

    /**
     * 获取合并转发消息详情
     */
    public JSONArray getForwardMsg(String forwardId) {
        if (forwardId == null || forwardId.isBlank()) {
            return new JSONArray();
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

                JSONObject body = new JSONObject();
                body.put(param[0], param[1]);
                httpPost.setEntity(new StringEntity(body.toJSONString()));

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
                    System.out.println("获取合并转发消息详情响应 (" + param[0] + "=" + forwardId + "): " +
                            (responseStr.length() > 300 ? responseStr.substring(0, 300) + "..." : responseStr));

                    JSONObject json = JSON.parseObject(responseStr);
                    if ("ok".equals(json.getString("status")) && json.containsKey("data")) {
                        JSONObject data = json.getJSONObject("data");
                        if (data != null && data.containsKey("messages")) {
                            JSONArray messages = data.getJSONArray("messages");
                            if (messages != null && !messages.isEmpty()) {
                                return normalizeForwardMessages(messages);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("获取合并转发消息详情失败 (" + param[0] + "=" + forwardId + "): " + e.getMessage());
            }
        }
        return new JSONArray();
    }

    /**
     * 从 NapCat 上报的 payload 的 message 数组中提取合并转发消息的子消息。
     * 当 NapCat 配置 parseMultMsg=true 时，forward 类型的 segment 的 data.content 中
     * 会包含解析后的子消息数组。
     */
    public JSONArray extractForwardMessagesFromPayload(JSONObject payload) {
        if (payload == null) {
            return null;
        }
        JSONArray messageArray = payload.getJSONArray("message");
        if (messageArray == null || messageArray.isEmpty()) {
            return null;
        }
        for (int i = 0; i < messageArray.size(); i++) {
            JSONObject segment = messageArray.getJSONObject(i);
            if (segment == null) {
                continue;
            }
            if ("forward".equals(segment.getString("type"))) {
                JSONObject data = segment.getJSONObject("data");
                if (data != null) {
                    JSONArray content = data.getJSONArray("content");
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
    public JSONArray normalizeForwardMessages(JSONArray rawMessages) {
        JSONArray result = new JSONArray();
        if (rawMessages == null) {
            return result;
        }
        for (int i = 0; i < rawMessages.size(); i++) {
            JSONObject raw = rawMessages.getJSONObject(i);
            if (raw == null) continue;

            JSONObject normalized = new JSONObject();
            // 消息ID
            Object msgIdObj = raw.get("message_id");
            if (msgIdObj == null) msgIdObj = raw.get("msgId");
            if (msgIdObj == null) msgIdObj = raw.get("real_id");
            normalized.put("id", msgIdObj != null ? String.valueOf(msgIdObj) : ("forward-msg-" + i));

            // 发送者信息
            JSONObject sender = raw.getJSONObject("sender");
            if (sender != null) {
                Object userIdObj = sender.get("user_id");
                if (userIdObj == null) userIdObj = sender.get("userId");
                normalized.put("userQq", userIdObj != null ? String.valueOf(userIdObj) : "");
                normalized.put("userNickname", sender.getString("nickname"));
            } else {
                normalized.put("userQq", "");
                normalized.put("userNickname", raw.getString("nickname"));
            }

            // 时间：NapCat 通常返回秒级时间戳，前端 JS Date 需要毫秒
            Long timeSeconds = raw.getLong("time");
            if (timeSeconds == null) timeSeconds = raw.getLong("timestamp");
            normalized.put("sendTime", timeSeconds != null ? timeSeconds * 1000 : null);
            normalized.put("timestamp", timeSeconds != null ? timeSeconds * 1000 : null);

            // 内容：优先 raw_message，其次从 message 数组构造
            String content = raw.getString("raw_message");
            // 嵌套合并转发的子消息可能位于 data.content 中
            JSONArray childMessageArray = raw.getJSONArray("message");
            if (childMessageArray == null || childMessageArray.isEmpty()) {
                JSONObject data = raw.getJSONObject("data");
                if (data != null) {
                    childMessageArray = data.getJSONArray("content");
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
                JSONArray nestedForwardMessages = null;
                JSONObject firstChild = childMessageArray.getJSONObject(0);
                // 子数组元素如果是 segment（有 type 字段），需要先提取 forward segment 的 data.content
                if (firstChild != null && firstChild.getString("type") != null) {
                    for (int j = 0; j < childMessageArray.size(); j++) {
                        JSONObject seg = childMessageArray.getJSONObject(j);
                        if (seg != null && "forward".equals(seg.getString("type"))) {
                            JSONObject segData = seg.getJSONObject("data");
                            if (segData != null) {
                                JSONArray segContent = segData.getJSONArray("content");
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
                    normalized.put("forwardMessages", nestedForwardMessages);
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
    public void downloadForwardMediaToLocal(JSONArray forwardMessages, String groupId) {
        if (forwardMessages == null || forwardMessages.isEmpty() || groupId == null || groupId.isBlank()) {
            return;
        }
        for (int i = 0; i < forwardMessages.size(); i++) {
            JSONObject msg = forwardMessages.getJSONObject(i);
            if (msg == null) continue;
            String type = msg.getString("messageType");
            String content = msg.getString("content");

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
                    System.err.println("下载合并转发子消息媒体失败 (" + type + "): " + e.getMessage());
                }
            }
            if (localUrl != null && !localUrl.isBlank()) {
                msg.put("localUrl", localUrl);
            }

            // 递归处理嵌套合并转发
            if ("FORWARD".equals(type)) {
                JSONArray nested = msg.getJSONArray("forwardMessages");
                if (nested != null && !nested.isEmpty()) {
                    downloadForwardMediaToLocal(nested, groupId);
                }
            }
        }
    }

    /**
     * 从 OneBot 11 message 数组拼接成 raw_message 风格字符串（简单兜底）。
     */
    private String buildRawMessageFromArray(JSONArray messageArray) {
        if (messageArray == null || messageArray.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messageArray.size(); i++) {
            JSONObject segment = messageArray.getJSONObject(i);
            if (segment == null) continue;
            String type = segment.getString("type");
            JSONObject data = segment.getJSONObject("data");
            if (data == null) continue;
            switch (type) {
                case "text":
                    sb.append(data.getString("text"));
                    break;
                case "at":
                    sb.append("[CQ:at,qq=").append(data.getString("qq")).append("]");
                    break;
                case "image":
                    sb.append("[CQ:image,file=").append(data.getString("file")).append(",url=").append(data.getString("url")).append("]");
                    break;
                case "video":
                    sb.append("[CQ:video,file=").append(data.getString("file")).append(",url=").append(data.getString("url")).append("]");
                    break;
                case "record":
                case "voice":
                    sb.append("[CQ:record,file=").append(data.getString("file")).append("]");
                    break;
                case "face":
                    sb.append("[CQ:face,id=").append(data.getString("id")).append("]");
                    break;
                case "forward":
                    sb.append("[CQ:forward,id=").append(data.getString("id")).append("]");
                    break;
                case "reply":
                    sb.append("[CQ:reply,id=").append(data.getString("id")).append("]");
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
    public JSONArray getGroupMessageHistory(long groupId, int count) throws Exception {
        String baseUrl = onebotApiUrl != null && !onebotApiUrl.isBlank() ? onebotApiUrl : napcatApiUrl;
        CloseableHttpClient httpClient = this.httpClient;
        HttpPost httpPost = new HttpPost(baseUrl + "/get_group_msg_history");
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("Authorization", "Bearer " + napcatToken);

        JSONObject body = new JSONObject();
        body.put("group_id", groupId);
        body.put("count", count);
        httpPost.setEntity(new StringEntity(body.toJSONString()));

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
            System.out.println("获取群" + groupId + "历史消息响应: " + (responseStr.length() > 200 ? responseStr.substring(0, 200) + "..." : responseStr));

            JSONObject json = JSON.parseObject(responseStr);
            if ("ok".equals(json.getString("status")) && json.containsKey("data")) {
                JSONObject data = json.getJSONObject("data");
                if (data.containsKey("messages")) {
                    return data.getJSONArray("messages");
                }
            }
            return new JSONArray();
        }
    }
}