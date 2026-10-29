package com.qqai.component;

import com.qqai.exception.BizException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 组件编排：唯一负责启停、就绪等待、状态机与健康巡检。
 *
 * <p>设计边界：不承诺自动重启（NapCat 自动重启有顶号风险）；只保证“受管进程”的
 * 幂等启停、就绪判定、失败原因与降级状态。</p>
 */
@Service
public class ComponentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ComponentOrchestrator.class);

    private final ComponentRegistry registry;
    private final ComponentStateStore store;
    private final ComponentRuntimeFactory runtimeFactory;

    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<String, Integer> healthFailures = new ConcurrentHashMap<>();

    public ComponentOrchestrator(ComponentRegistry registry, ComponentStateStore store,
                                 ComponentRuntimeFactory runtimeFactory) {
        this.registry = registry;
        this.store = store;
        this.runtimeFactory = runtimeFactory;
    }

    @PostConstruct
    public void reconcile() {
        for (ComponentModel.ComponentDescriptor d : registry.ordered()) {
            try {
                ComponentModel.ComponentRecord rec = store.get(d.getId());
                ComponentModel.ProbeResult p = runtime(d).probe(d);
                String before = rec.state;
                if (p.reachable()) {
                    rec.state = ComponentModel.ComponentState.RUNNING.name();
                    rec.lastHealthAt = now();
                    rec.lastError = null;
                    if (!"RUNNING".equals(before) && !"DEGRADED".equals(before)) {
                        rec.owned = false;
                    }
                } else {
                    rec.state = ComponentModel.ComponentState.STOPPED.name();
                    rec.pid = null;
                    rec.pidStartInstant = null;
                    rec.lastError = null;
                }
                if (!before.equals(rec.state) || p.reachable()) {
                    store.put(d.getId(), rec);
                }
                log.info("组件对账: {} state={} owned={} probe={}", d.getId(), rec.state, rec.owned, p.detail());
            } catch (Exception e) {
                log.warn("组件对账失败 {}: {}", d.getId(), e.getMessage());
            }
        }
    }

    public List<ComponentModel.ComponentView> list() {
        List<ComponentModel.ComponentView> views = new ArrayList<>();
        for (ComponentModel.ComponentDescriptor d : registry.ordered()) {
            ComponentModel.ComponentRecord rec = store.get(d.getId());
            ComponentModel.ProbeResult p = runtime(d).probe(d);
            views.add(view(d, rec, p));
        }
        return views;
    }

    public ComponentModel.ComponentView start(String id, boolean force) {
        ComponentModel.ComponentDescriptor d = registry.get(id);
        ReentrantLock lock = lock(id);
        lock.lock();
        try {
            ComponentModel.ComponentRecord rec = store.get(id);
            if (ComponentModel.ComponentState.STARTING.name().equals(rec.state)) {
                throw new BizException(409, "组件正在启动: " + id);
            }
            ComponentModel.ProbeResult before = runtime(d).probe(d);
            if (before.reachable()) {
                // 只有我们自己已跟踪为 RUNNING/DEGRADED 时才幂等返回；
                // 否则端口被别的进程占着 → 409，不能把它误认成组件已启动（更不能后续被 stop 杀掉）。
                boolean tracked = ComponentModel.ComponentState.RUNNING.name().equals(rec.state)
                        || ComponentModel.ComponentState.DEGRADED.name().equals(rec.state);
                if (!tracked) {
                    throw new BizException(409,
                            "端口已被其他进程占用，无法启动 " + id + "（" + before.detail() + "）");
                }
                rec.state = ComponentModel.ComponentState.RUNNING.name();
                rec.lastHealthAt = now();
                if (force) {
                    rec.owned = true;
                }
                store.put(id, rec);
                return view(d, rec, before);
            }
            // 僵死实例：进程还活着但探测失败（如假死），先清掉再启动，避免双开
            if (rec.pid != null) {
                ProcessHandle stale = ProcessHandle.of(rec.pid).orElse(null);
                if (stale != null && stale.isAlive()) {
                    try {
                        runtime(d).stop(d, rec, true);
                    } catch (Exception e) {
                        log.warn("清理僵死进程 {} 失败: {}", rec.pid, e.getMessage());
                    }
                }
            }
            validate(d);
            rec.state = ComponentModel.ComponentState.STARTING.name();
            rec.startedAt = now();
            rec.lastError = null;
            rec.owned = true;
            rec.pid = null;
            rec.pidStartInstant = null;
            store.put(id, rec);
            try {
                ComponentModel.Started started = runtime(d).start(d);
                rec.pid = started.pid();
                rec.pidStartInstant = started.pidStartInstant();
                store.put(id, rec);
            } catch (Exception e) {
                rec.state = ComponentModel.ComponentState.FAILED.name();
                rec.lastError = "启动失败: " + e.getMessage();
                store.put(id, rec);
                throw new BizException(502, rec.lastError);
            }
            long deadline = System.currentTimeMillis() + d.getStartupTimeoutMs();
            ComponentModel.ProbeResult last = null;
            while (System.currentTimeMillis() < deadline) {
                last = runtime(d).probe(d);
                if (last.reachable()) {
                    rec.state = ComponentModel.ComponentState.RUNNING.name();
                    rec.lastHealthAt = now();
                    store.put(id, rec);
                    log.info("组件 {} 启动完成（pid={}）", id, rec.pid);
                    return view(d, rec, last);
                }
                sleep(500);
            }
            try {
                runtime(d).stop(d, rec, true);
            } catch (Exception e) {
                log.warn("启动超时后清理 {} 失败: {}", id, e.getMessage());
            }
            rec.state = ComponentModel.ComponentState.FAILED.name();
            rec.pid = null;
            rec.pidStartInstant = null;
            rec.lastError = "启动超时（" + d.getStartupTimeoutMs() + "ms）"
                    + (last == null ? "" : ": " + last.detail());
            store.put(id, rec);
            throw new BizException(502, rec.lastError);
        } finally {
            lock.unlock();
        }
    }

    public ComponentModel.ComponentView stop(String id, boolean force) {
        ComponentModel.ComponentDescriptor d = registry.get(id);
        ReentrantLock lock = lock(id);
        lock.lock();
        try {
            ComponentModel.ComponentRecord rec = store.get(id);
            if (ComponentModel.ComponentState.STOPPING.name().equals(rec.state)) {
                throw new BizException(409, "组件正在停止: " + id);
            }
            ComponentModel.ProbeResult probe = runtime(d).probe(d);
            // 只处理“我们跟踪中的组件”。端口可达不等于它是我们的进程：
            // 停止一个 STOPPED 组件时若端口被别的程序占着，绝不能把对方杀掉。
            boolean tracked = ComponentModel.ComponentState.RUNNING.name().equals(rec.state)
                    || ComponentModel.ComponentState.DEGRADED.name().equals(rec.state)
                    || ComponentModel.ComponentState.STARTING.name().equals(rec.state)
                    || ComponentModel.ComponentState.STOPPING.name().equals(rec.state);
            if (!tracked) {
                rec.state = ComponentModel.ComponentState.STOPPED.name();
                rec.pid = null;
                rec.pidStartInstant = null;
                store.put(id, rec);
                return view(d, rec, probe);
            }
            if (!rec.owned && !force && !d.isAdoptExternal()) {
                throw new BizException(409, "组件由外部启动（非本系统拉起），如需停止请传 force=true");
            }
            rec.state = ComponentModel.ComponentState.STOPPING.name();
            store.put(id, rec);
            try {
                runtime(d).stop(d, rec, force || d.isAdoptExternal());
            } catch (Exception e) {
                rec.state = ComponentModel.ComponentState.FAILED.name();
                rec.lastError = "停止失败: " + e.getMessage();
                store.put(id, rec);
                throw new BizException(502, rec.lastError);
            }
            long deadline = System.currentTimeMillis() + 10000;
            while (System.currentTimeMillis() < deadline && runtime(d).probe(d).reachable()) {
                sleep(300);
            }
            if (runtime(d).probe(d).reachable()) {
                rec.state = ComponentModel.ComponentState.FAILED.name();
                rec.lastError = "停止超时：端口仍可访问（进程可能未被结束）";
                store.put(id, rec);
                throw new BizException(502, rec.lastError);
            }
            rec.state = ComponentModel.ComponentState.STOPPED.name();
            rec.pid = null;
            rec.pidStartInstant = null;
            rec.lastError = null;
            rec.lastHealthAt = now();
            store.put(id, rec);
            log.info("组件 {} 已停止", id);
            return view(d, rec, runtime(d).probe(d));
        } finally {
            lock.unlock();
        }
    }

    public ComponentModel.ComponentView restart(String id) {
        stop(id, true);
        ComponentModel.ComponentDescriptor d = registry.get(id);
        if (runtime(d).probe(d).reachable()) {
            throw new BizException(409, "组件停止未完成，无法重启: " + id);
        }
        return start(id, true);
    }

    /** 登录后自动拉起：按 order 顺序，只启动未运行且已启用的组件。 */
    public void startAllIfNotRunning() {
        for (ComponentModel.ComponentDescriptor d : registry.ordered()) {
            try {
                if (runtime(d).probe(d).reachable()) {
                    continue;
                }
                start(d.getId(), false);
            } catch (Exception e) {
                log.warn("自动启动组件 {} 失败: {}", d.getId(), e.getMessage());
            }
        }
    }

    /** 定时巡检：连续 3 次失败进入 DEGRADED，恢复 1 次回到 RUNNING。 */
    public void healthCheckAll() {
        for (ComponentModel.ComponentDescriptor d : registry.ordered()) {
            try {
                ComponentModel.ComponentRecord rec = store.get(d.getId());
                ComponentModel.ProbeResult p = runtime(d).probe(d);
                String before = rec.state;
                if (p.reachable()) {
                    healthFailures.remove(d.getId());
                    rec.lastHealthAt = now();
                    // 只做 DEGRADED -> RUNNING 的恢复；STOPPED 时端口可达不等于组件被我们启动，
                    // 不自动认领（否则会把占用端口的其他程序当成组件，后续 stop 可能误杀）。
                    if (ComponentModel.ComponentState.DEGRADED.name().equals(rec.state)) {
                        rec.state = ComponentModel.ComponentState.RUNNING.name();
                    }
                } else {
                    int failures = healthFailures.merge(d.getId(), 1, Integer::sum);
                    if (ComponentModel.ComponentState.RUNNING.name().equals(rec.state) && failures >= 3) {
                        rec.state = ComponentModel.ComponentState.DEGRADED.name();
                        rec.lastError = "健康检查连续失败 " + failures + " 次: " + p.detail();
                    }
                }
                if (!before.equals(rec.state)) {
                    store.put(d.getId(), rec);
                    log.info("组件健康状态变化 {}: {} -> {}", d.getId(), before, rec.state);
                }
            } catch (Exception e) {
                log.debug("健康巡检异常 {}: {}", d.getId(), e.getMessage());
            }
        }
    }

    private ComponentModel.ComponentView view(ComponentModel.ComponentDescriptor d,
                                              ComponentModel.ComponentRecord rec,
                                              ComponentModel.ProbeResult probe) {
        ComponentModel.ComponentView v = new ComponentModel.ComponentView();
        v.id = d.getId();
        v.legacyKey = d.getLegacyKey();
        v.displayName = d.getDisplayName();
        v.capability = d.getCapability();
        boolean reachable = probe != null && probe.reachable();
        boolean tracked = ComponentModel.ComponentState.RUNNING.name().equals(rec.state)
                || ComponentModel.ComponentState.DEGRADED.name().equals(rec.state)
                || ComponentModel.ComponentState.STARTING.name().equals(rec.state)
                || ComponentModel.ComponentState.STOPPING.name().equals(rec.state);
        v.healthy = reachable && tracked;
        // 只有“我们跟踪中的组件”端口可达才算 running；STOPPED 时端口被别的程序占用不能被认领
        v.running = reachable && tracked;
        v.state = v.running ? ComponentModel.ComponentState.RUNNING.name()
                : (reachable ? ComponentModel.ComponentState.STOPPED.name() : rec.state);
        v.owned = rec.owned;
        v.pid = rec.pid;
        v.ports = d.getPorts();
        v.webUi = d.getWebUi();
        v.lastError = rec.lastError;
        v.startedAt = rec.startedAt;
        boolean starting = ComponentModel.ComponentState.STARTING.name().equals(rec.state);
        boolean stopping = ComponentModel.ComponentState.STOPPING.name().equals(rec.state);
        v.actions.put("start", !v.running && !starting && !stopping);
        // DEGRADED 也允许“停止”来清理僵死进程；但 STOPPED 且端口被陌生程序占用时不允许停止
        v.actions.put("stop", v.running || starting
                || ComponentModel.ComponentState.DEGRADED.name().equals(rec.state));
        return v;
    }

    private void validate(ComponentModel.ComponentDescriptor d) {
        if (d.getCommand() == null || d.getCommand().isEmpty()) {
            throw new BizException(400, "组件 " + d.getId() + " 未配置启动命令");
        }
        try {
            File dir = new File(d.getWorkdir() == null ? System.getProperty("user.dir") : d.getWorkdir());
            if (!dir.isAbsolute()) {
                dir = new File(System.getProperty("user.dir"), d.getWorkdir());
            }
            if (!dir.exists()) {
                throw new BizException(400, "组件 " + d.getId() + " 的工作目录不存在: " + dir.getCanonicalPath());
            }
        } catch (java.io.IOException e) {
            throw new BizException(400, "组件 " + d.getId() + " 工作目录无效: " + e.getMessage());
        }
    }

    private ComponentRuntime runtime(ComponentModel.ComponentDescriptor d) {
        return runtimeFactory.forDescriptor(d);
    }

    private ReentrantLock lock(String id) {
        return locks.computeIfAbsent(id, k -> new ReentrantLock());
    }

    private static void sleep(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String now() {
        return OffsetDateTime.now().toString();
    }
}
