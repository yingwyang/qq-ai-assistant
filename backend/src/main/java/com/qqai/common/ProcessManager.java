package com.qqai.common;

import org.apache.commons.exec.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

@Component
public class ProcessManager {

    private static final Logger log = LoggerFactory.getLogger(ProcessManager.class);

    /**
     * 检测端口是否被占用
     */
    public boolean isPortOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 通过进程名终止进程（使用 PowerShell Stop-Process，权限兼容性更好）
     */
    public void killProcessByName(String name) {
        try {
            String escapedName = name.replace("'", "''");
            int exitCode = executePowerShellInternal(
                    "Stop-Process -Name '" + escapedName + "' -Force -ErrorAction SilentlyContinue",
                    5000
            );
            if (exitCode == 0) {
                log.info("进程 {} 已终止", name);
            }
        } catch (Exception e) {
            log.warn("终止进程 {} 失败（忽略）: {}", name, e.getMessage());
        }
    }

    /**
     * 通过端口找到 PID 并终止进程
     */
    public void killProcessByPort(int port, String label) {
        try {
            String output = executeAndGetOutput("netstat -ano | findstr :" + port, 3000);
            if (output == null || output.isEmpty()) {
                log.info("端口 {} 无进程监听，无需终止", port);
                return;
            }
            String[] lines = output.split("\\r?\\n");
            for (String line : lines) {
                if (line.contains("LISTENING")) {
                    String[] parts = line.trim().split("\\s+");
                    String pid = parts[parts.length - 1];
                    try {
                        int pidNum = Integer.parseInt(pid);
                        executePowerShellInternal(
                                "Stop-Process -Id " + pidNum + " -Force -ErrorAction SilentlyContinue",
                                5000
                        );
                        executeCmdInternal("taskkill /F /PID " + pidNum, 5000);
                        log.info("{} 端口 {} 上的进程 PID={} 已终止", label, port, pid);
                    } catch (NumberFormatException e) {
                        log.warn("无法解析 PID: {}", pid);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("通过端口终止 {} 进程失败: {}", label, e.getMessage());
        }
    }

    /**
     * 执行 PowerShell 命令并返回退出码
     */
    public int executePowerShell(String command, long timeoutMs) {
        return executePowerShellInternal(command, timeoutMs);
    }

    /**
     * 执行 cmd 命令并返回退出码
     */
    public int executeCmd(String command, long timeoutMs) {
        return executeCmdInternal(command, timeoutMs);
    }

    /**
     * 安全销毁进程引用
     */
    public void destroyProcess(Process process) {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            log.info("进程引用已销毁");
        }
    }

    // ========== 内部方法 ==========

    private int executePowerShellInternal(String command, long timeoutMs) {
        try {
            String psCommand = "powershell.exe -NoProfile -Command " + command;
            CommandLine cmdLine = CommandLine.parse(psCommand);
            DefaultExecutor executor = new DefaultExecutor();
            executor.setExitValue(0);
            ExecuteWatchdog watchdog = new ExecuteWatchdog(timeoutMs);
            executor.setWatchdog(watchdog);
            // 抑制 PowerShell 的中文 stderr 乱码
            executor.setStreamHandler(new PumpStreamHandler(new ByteArrayOutputStream(), new ByteArrayOutputStream()));
            int exitCode = executor.execute(cmdLine);
            if (watchdog.killedProcess()) {
                log.warn("PowerShell 命令超时被终止: {}", command);
            }
            return exitCode;
        } catch (ExecuteException e) {
            log.debug("PowerShell 命令执行失败（可能正常）: {} - exitCode={}", command, e.getExitValue());
            return e.getExitValue();
        } catch (IOException e) {
            log.warn("PowerShell 命令异常: {} - {}", command, e.getMessage());
            return -1;
        }
    }

    private int executeCmdInternal(String command, long timeoutMs) {
        try {
            CommandLine cmdLine = CommandLine.parse("cmd.exe /c " + command);
            DefaultExecutor executor = new DefaultExecutor();
            executor.setExitValue(0);
            ExecuteWatchdog watchdog = new ExecuteWatchdog(timeoutMs);
            executor.setWatchdog(watchdog);
            // 抑制 cmd 命令的中文 stderr 乱码
            executor.setStreamHandler(new PumpStreamHandler(new ByteArrayOutputStream(), new ByteArrayOutputStream()));
            int exitCode = executor.execute(cmdLine);
            if (watchdog.killedProcess()) {
                log.warn("cmd 命令超时被终止: {}", command);
            }
            return exitCode;
        } catch (ExecuteException e) {
            log.debug("cmd 命令执行失败（可能正常）: {} - exitCode={}", command, e.getExitValue());
            return e.getExitValue();
        } catch (IOException e) {
            log.warn("cmd 命令异常: {} - {}", command, e.getMessage());
            return -1;
        }
    }

    private String executeAndGetOutput(String command, long timeoutMs) {
        try {
            CommandLine cmdLine = CommandLine.parse("cmd.exe /c " + command);
            DefaultExecutor executor = new DefaultExecutor();
            executor.setExitValue(1); // 允许 findstr 返回非零
            ExecuteWatchdog watchdog = new ExecuteWatchdog(timeoutMs);
            executor.setWatchdog(watchdog);

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            PumpStreamHandler streamHandler = new PumpStreamHandler(stdout);
            executor.setStreamHandler(streamHandler);

            DefaultExecuteResultHandler resultHandler = new DefaultExecuteResultHandler();
            executor.execute(cmdLine, resultHandler);
            resultHandler.waitFor();

            return stdout.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
