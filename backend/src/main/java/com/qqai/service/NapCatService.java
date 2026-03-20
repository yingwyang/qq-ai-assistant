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
        
        // 方法1: 优先通过检查 NapCat 配置文件来判断登录状态（成功率最高）
        // 登录成功后 NapCat 会创建 napcat_<qq号>.json 配置文件
        try {
            String configPath = getNapCatConfigPath();
            File configDir = new File(configPath);
            System.out.println("Method 1: Checking NapCat config directory: " + configPath);
            
            if (configDir.exists() && configDir.isDirectory()) {
                File[] configFiles = configDir.listFiles((dir, name) -> {
                    // 查找 napcat_<qq号>.json 格式的文件，排除 napcat_protocol_ 和 onebot11_
                    return name.startsWith("napcat_") 
                        && !name.startsWith("napcat_protocol_") 
                        && !name.startsWith("onebot11_")
                        && name.endsWith(".json");
                });
                
                System.out.println("Found " + (configFiles != null ? configFiles.length : 0) + " config files");
                
                if (configFiles != null && configFiles.length > 0) {
                    for (File configFile : configFiles) {
                        String fileName = configFile.getName();
                        
                        // 从文件名提取 QQ 号: napcat_<qq号>.json
                        String qqNumber = fileName.replace("napcat_", "").replace(".json", "");
                        
                        // 检查是否是纯数字（有效的 QQ 号）
                        if (qqNumber.matches("\\d+")) {
                            System.out.println("✅ Method 1 SUCCESS: Found valid QQ config: " + qqNumber);
                            return true;
                        }
                    }
                }
            } else {
                System.out.println("Config directory does not exist: " + configPath);
            }
        } catch (Exception e) {
            System.out.println("Config check failed: " + e.getMessage());
            e.printStackTrace();
        }
        
        // 方法2: 尝试调用 NapCat API 检查登录状态
        System.out.println("Method 2: Trying API endpoints...");
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
    
    private String getNapCatConfigPath() throws Exception {
        String projectRoot = System.getProperty("user.dir");
        String configPath = projectRoot + File.separator + ".." + File.separator + ".." + File.separator + "napcat" + File.separator + "NapCat.Shell" + File.separator + "config";
        return new File(configPath).getCanonicalFile().getAbsolutePath();
    }

    private Process napcatProcess;

    public void startNapCat() throws Exception {
        try {
            // NapCat 启动脚本路径 - 使用相对路径，位于项目根目录下的 napcat/NapCat.Shell 文件夹
            String projectRoot = System.getProperty("user.dir");
            String napcatPath = projectRoot + File.separator + ".." + File.separator + ".." + File.separator + "napcat" + File.separator + "NapCat.Shell";
            File napcatDir = new File(napcatPath).getCanonicalFile();
            String launcherPath = napcatDir.getAbsolutePath() + File.separator + "launcher.bat";
            
            File launcherFile = new File(launcherPath);
            if (!launcherFile.exists()) {
                throw new IOException("NapCat launcher.bat not found at: " + launcherPath);
            }
            
            // 构建进程
            ProcessBuilder processBuilder = new ProcessBuilder("cmd.exe", "/c", "start", "cmd.exe", "/k", launcherPath);
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
}