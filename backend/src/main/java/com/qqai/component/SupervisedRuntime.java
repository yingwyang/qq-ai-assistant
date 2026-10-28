package com.qqai.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/** 直接拉起子进程：可拿 PID/startInstant，日志重定向到 backend/logs/components/<id>.log。 */
@Component
public class SupervisedRuntime implements ComponentRuntime {

    private static final Logger log = LoggerFactory.getLogger(SupervisedRuntime.class);

    @Override
    public ComponentModel.Started start(ComponentModel.ComponentDescriptor d) throws Exception {
        if (d.getCommand() == null || d.getCommand().isEmpty()) {
            throw new IllegalStateException("组件 " + d.getId() + " 未配置启动命令");
        }
        File workdir = resolveWorkdir(d);
        ProcessBuilder pb = new ProcessBuilder(d.getCommand());
        pb.directory(workdir);
        if (d.getEnv() != null) {
            pb.environment().putAll(d.getEnv());
        }
        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("NUL")));
        Path logFile = Paths.get(System.getProperty("user.dir"), "logs", "components", d.getId() + ".log");
        Files.createDirectories(logFile.getParent());
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        Process p = pb.start();
        String startInstant = ProcessHandle.of(p.pid())
                .flatMap(h -> h.info().startInstant())
                .map(Instant::toString)
                .orElse(null);
        log.info("组件 {} 已启动 pid={} log={}", d.getId(), p.pid(), logFile);
        return new ComponentModel.Started(p.pid(), startInstant);
    }

    @Override
    public void stop(ComponentModel.ComponentDescriptor d, ComponentModel.ComponentRecord record,
                     boolean allowPortFallback) throws Exception {
        boolean killed = false;
        if (record != null && record.pid != null) {
            ProcessHandle handle = ProcessHandle.of(record.pid).orElse(null);
            if (handle != null && handle.isAlive() && startInstantMatches(handle, record.pidStartInstant)) {
                handle.destroy();
                if (!handle.onExit().get(5, TimeUnit.SECONDS).isAlive()) {
                    killed = true;
                } else {
                    handle.destroyForcibly();
                    killed = true;
                }
                // 兜底杀掉进程树（避免 Python/cmd 子进程残留）
                exec("taskkill", "/T", "/F", "/PID", String.valueOf(record.pid));
            }
        }
        if (!killed && allowPortFallback) {
            for (Integer port : d.getPorts()) {
                Long pid = findPidByPort(port);
                if (pid != null) {
                    exec("taskkill", "/T", "/F", "/PID", String.valueOf(pid));
                    log.warn("组件 {} 未持有进程，按端口 {} 结束 PID={}", d.getId(), port, pid);
                    killed = true;
                }
            }
        }
    }

    @Override
    public ComponentModel.ProbeResult probe(ComponentModel.ComponentDescriptor d) {
        return Probes.probe(d);
    }

    private File resolveWorkdir(ComponentModel.ComponentDescriptor d) throws Exception {
        if (d.getWorkdir() == null || d.getWorkdir().isBlank()) {
            return new File(System.getProperty("user.dir"));
        }
        File f = new File(d.getWorkdir());
        if (!f.isAbsolute()) {
            f = new File(System.getProperty("user.dir"), d.getWorkdir());
        }
        return f.getCanonicalFile();
    }

    private static boolean startInstantMatches(ProcessHandle handle, String expected) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        return handle.info().startInstant().map(Instant::toString).map(expected::equals).orElse(false);
    }

    static Long findPidByPort(int port) {
        try {
            Process p = new ProcessBuilder("cmd.exe", "/c",
                    "netstat -ano | findstr :" + port + " | findstr LISTENING").start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor(3, TimeUnit.SECONDS);
            for (String line : out.split("\r?\n")) {
                String[] parts = line.trim().split("\s+");
                if (parts.length >= 5) {
                    return Long.parseLong(parts[parts.length - 1]);
                }
            }
        } catch (Exception e) {
            log.debug("按端口 {} 查 PID 失败: {}", port, e.getMessage());
        }
        return null;
    }

    private static void exec(String... cmd) {
        try {
            new ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("执行 {} 失败: {}", String.join(" ", cmd), e.getMessage());
        }
    }
}
