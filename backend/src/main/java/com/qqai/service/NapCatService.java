package com.qqai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
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

    @Value("${napcat.token}")
    private String napcatToken;

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
        CloseableHttpClient httpClient = HttpClients.createDefault();
        
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
        
        httpClient.close();
        
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
        CloseableHttpClient httpClient = HttpClients.createDefault();
        
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
        
        httpClient.close();
        
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
            CloseableHttpClient httpClient = HttpClients.createDefault();
            // 尝试访问 NapCat 的 WebUI 端点，使用 URL 参数传递 token
            HttpGet httpGet = new HttpGet(napcatApiUrl + "/api/QQLogin/CheckLoginStatus?token=" + napcatToken);
            
            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                int statusCode = response.getCode();
                httpClient.close();
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
            
            // 构建进程命令
            String qq = autoLogin ? getLastQqNumber() : null;
            ProcessBuilder processBuilder;
            if (qq != null && !qq.isEmpty()) {
                processBuilder = new ProcessBuilder("cmd.exe", "/c", "start", "cmd.exe", "/k", launcherPath, qq);
                System.out.println("Starting NapCat with QQ: " + qq);
            } else {
                processBuilder = new ProcessBuilder("cmd.exe", "/c", "start", "cmd.exe", "/k", launcherPath);
                System.out.println("Starting NapCat without QQ number (QR code login)");
            }
            processBuilder.directory(napcatDir);
            processBuilder.inheritIO();
            
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
     * 获取群列表
     */
    public JSONArray getGroupList() throws Exception {
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost(napcatApiUrl + "/api/get_group_list");
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
        } finally {
            httpClient.close();
        }
    }

    /**
     * 获取群历史消息
     */
    public JSONArray getGroupMessageHistory(long groupId, int count) throws Exception {
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost(napcatApiUrl + "/api/get_group_msg_history");
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
        } finally {
            httpClient.close();
        }
    }
}