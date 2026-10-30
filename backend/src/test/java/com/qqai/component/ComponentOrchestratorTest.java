package com.qqai.component;

import com.qqai.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ComponentOrchestratorTest {

    @TempDir
    Path tempDir;

    private ComponentProperties properties;
    private ComponentRegistry registry;
    private ComponentStateStore store;
    private FakeRuntime runtime;
    private ComponentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        ComponentModel.ComponentDescriptor d = new ComponentModel.ComponentDescriptor();
        d.setId("astrbot");
        d.setOrder(10);
        d.setCommand(List.of("cmd.exe", "/c", "echo", "astrbot"));
        d.setWorkdir(".");
        d.setStartupTimeoutMs(600);
        d.setAdoptExternal(false);

        properties = new ComponentProperties();
        properties.setStateFile(tempDir.resolve("state.json").toString());
        properties.setComponents(List.of(d));

        registry = new ComponentRegistry(properties);
        store = new ComponentStateStore(properties);
        store.load();
        runtime = new FakeRuntime();
        orchestrator = new ComponentOrchestrator(registry, store, new FakeFactory(runtime));
    }

    @Test
    @DisplayName("启动成功：进入 RUNNING，记录 pid，只拉起一次")
    void startSucceeds() {
        ComponentModel.ComponentView view = orchestrator.start("astrbot", false);

        assertEquals("RUNNING", view.state);
        assertTrue(view.running);
        assertEquals(1, runtime.starts.get());
        assertEquals(42L, store.get("astrbot").pid);
        assertTrue(store.get("astrbot").owned);
    }

    @Test
    @DisplayName("全局 enabled=false：start 直接 409，不拉起任何进程")
    void globallyDisabledRejectsStart() {
        properties.setEnabled(false);
        registry = new ComponentRegistry(properties);
        orchestrator = new ComponentOrchestrator(registry, store, new FakeFactory(runtime));

        assertTrue(registry.ordered().isEmpty());
        BizException ex = assertThrows(BizException.class, () -> orchestrator.start("astrbot", false));
        assertEquals(409, ex.getCode());
        assertEquals(0, runtime.starts.get());
    }

    @Test
    @DisplayName("已运行再启动：幂等返回，不重复拉起")
    void startIsIdempotent() {
        runtime.reachable = true;
        ComponentModel.ComponentRecord rec = store.get("astrbot");
        rec.state = "RUNNING";
        rec.owned = true;
        rec.pid = 42L;
        store.put("astrbot", rec);

        ComponentModel.ComponentView view = orchestrator.start("astrbot", false);

        assertEquals("RUNNING", view.state);
        assertEquals(0, runtime.starts.get());
    }

    @Test
    @DisplayName("端口被陌生进程占用且组件未跟踪：409，不误认领、不拉起")
    void portOccupiedByForeignProcessRejected() {
        runtime.reachable = true;

        BizException ex = assertThrows(BizException.class, () -> orchestrator.start("astrbot", false));

        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("端口"), ex.getMessage());
        assertEquals(0, runtime.starts.get());
        assertEquals("STOPPED", store.get("astrbot").state);
    }

    @Test
    @DisplayName("启动超时：FAILED 且带原因")
    void startTimeoutFails() {
        runtime.reachableAfterStart = false;

        BizException ex = assertThrows(BizException.class, () -> orchestrator.start("astrbot", false));

        assertEquals(502, ex.getCode());
        assertTrue(ex.getMessage().contains("启动超时"), ex.getMessage());
        assertEquals("FAILED", store.get("astrbot").state);
    }

    @Test
    @DisplayName("停止 owned 组件：调用 runtime.stop 并回到 STOPPED")
    void stopOwned() {
        runtime.reachable = true;
        ComponentModel.ComponentRecord rec = store.get("astrbot");
        rec.state = "RUNNING";
        rec.owned = true;
        rec.pid = 42L;
        store.put("astrbot", rec);

        ComponentModel.ComponentView view = orchestrator.stop("astrbot", false);

        assertEquals("STOPPED", view.state);
        assertEquals(1, runtime.stops.get());
    }

    @Test
    @DisplayName("外部启动的组件：默认拒绝停止，force 才允许")
    void stopExternalRequiresForce() {
        runtime.reachable = true;
        ComponentModel.ComponentRecord rec = store.get("astrbot");
        rec.state = "RUNNING";
        rec.owned = false;
        store.put("astrbot", rec);

        BizException ex = assertThrows(BizException.class, () -> orchestrator.stop("astrbot", false));
        assertEquals(409, ex.getCode());
        assertEquals(0, runtime.stops.get());

        ComponentModel.ComponentView view = orchestrator.stop("astrbot", true);
        assertEquals("STOPPED", view.state);
        assertEquals(1, runtime.stops.get());
    }

    @Test
    @DisplayName("连续 3 次探测失败进入 DEGRADED，恢复 1 次回到 RUNNING")
    void healthDegradesAndRecovers() {
        orchestrator.start("astrbot", false);

        runtime.reachable = false;
        orchestrator.healthCheckAll();
        orchestrator.healthCheckAll();
        assertEquals("RUNNING", store.get("astrbot").state);
        orchestrator.healthCheckAll();
        assertEquals("DEGRADED", store.get("astrbot").state);

        runtime.reachable = true;
        orchestrator.healthCheckAll();
        assertEquals("RUNNING", store.get("astrbot").state);
    }

    @Test
    @DisplayName("DEGRADED（探测不可达）时允许从界面重启")
    void degradedAllowsStartAction() {
        ComponentModel.ComponentRecord rec = store.get("astrbot");
        rec.state = "DEGRADED";
        rec.pid = 42L;
        store.put("astrbot", rec);
        runtime.reachable = false;

        ComponentModel.ComponentView view = orchestrator.list().get(0);

        assertEquals("DEGRADED", view.state);
        assertFalse(view.running);
        assertTrue(view.actions.get("start"));
        assertTrue(view.actions.get("stop"));
    }

    @Test
    @DisplayName("restart = stop + start")
    void restart() {
        runtime.reachable = true;
        ComponentModel.ComponentRecord rec = store.get("astrbot");
        rec.state = "RUNNING";
        rec.owned = true;
        rec.pid = 42L;
        store.put("astrbot", rec);

        ComponentModel.ComponentView view = orchestrator.restart("astrbot");

        assertEquals("RUNNING", view.state);
        assertEquals(1, runtime.stops.get());
        assertEquals(1, runtime.starts.get());
    }

    @Test
    @DisplayName("startAllIfNotRunning：只启动未运行的组件，失败不抛")
    void startAllSkipsRunning() {
        runtime.reachable = true;
        orchestrator.startAllIfNotRunning();
        assertEquals(0, runtime.starts.get());

        runtime.reachable = false;
        orchestrator.startAllIfNotRunning();
        assertEquals(1, runtime.starts.get());
    }

    static class FakeRuntime implements ComponentRuntime {
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        volatile boolean reachable = false;
        volatile boolean reachableAfterStart = true;

        @Override
        public ComponentModel.Started start(ComponentModel.ComponentDescriptor descriptor) {
            starts.incrementAndGet();
            if (reachableAfterStart) reachable = true;
            return new ComponentModel.Started(42L, "instant");
        }

        @Override
        public void stop(ComponentModel.ComponentDescriptor descriptor,
                         ComponentModel.ComponentRecord record, boolean allowPortFallback) {
            stops.incrementAndGet();
            reachable = false;
        }

        @Override
        public ComponentModel.ProbeResult probe(ComponentModel.ComponentDescriptor descriptor) {
            return new ComponentModel.ProbeResult(reachable, 1, reachable ? "ok" : "down");
        }
    }

    static class FakeFactory extends ComponentRuntimeFactory {
        private final ComponentRuntime runtime;

        FakeFactory(ComponentRuntime runtime) {
            super(new SupervisedRuntime(), new DetachedLauncherRuntime());
            this.runtime = runtime;
        }

        @Override
        public ComponentRuntime forDescriptor(ComponentModel.ComponentDescriptor descriptor) {
            return runtime;
        }
    }
}
