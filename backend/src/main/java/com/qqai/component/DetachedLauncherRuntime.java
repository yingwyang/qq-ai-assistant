package com.qqai.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

/**
 * 提权/自拉起型启动器（NapCat）：launcher 自己 runas 拉起 QQ，父进程可能很快退出，
 * 因此只记录“本项目发起过”，停止沿用既有“进程名 + 端口”语义。
 */
@Component
public class DetachedLauncherRuntime implements ComponentRuntime {

    private static final Logger log = LoggerFactory.getLogger(DetachedLauncherRuntime.class);

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
        log.info("组件 {} 启动器已拉起 pid={}（launcher 可能自行提权后退出）", d.getId(), p.pid());
        return new ComponentModel.Started(p.pid(), startInstant);
    }

    @Override
    public void stop(ComponentModel.ComponentDescriptor d, ComponentModel.ComponentRecord record,
                     boolean allowPortFallback) throws Exception {
        // 既有语义：先结束守护进程，再结束 QQ，最后按端口兜底
        if (d.getProcessNames() != null) {
            for (String name : d.getProcessNames()) {
                exec("powershell", "-NoProfile", "-Command",
                        "Stop-Process -Name '" + name.replace("'", "''") + "' -Force -ErrorAction SilentlyContinue");
                exec("taskkill", "/F", "/T", "/IM", name + ".exe");
            }
        }
        for (Integer port : d.getPorts()) {
            Long pid = SupervisedRuntime.findPidByPort(port);
            if (pid != null) {
                exec("taskkill", "/T", "/F", "/PID", String.valueOf(pid));
                log.warn("组件 {} 按端口 {} 结束残留 PID={}", d.getId(), port, pid);
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

    private static void exec(String... cmd) {
        try {
            new ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("执行 {} 失败: {}", String.join(" ", cmd), e.getMessage());
        }
    }
}
