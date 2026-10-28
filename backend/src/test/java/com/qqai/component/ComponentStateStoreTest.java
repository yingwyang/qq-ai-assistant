package com.qqai.component;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ComponentStateStoreTest {

    private static ComponentProperties props(Path dir) {
        ComponentProperties p = new ComponentProperties();
        p.setStateFile(dir.resolve("components-state.json").toString());
        return p;
    }

    @Test
    @DisplayName("状态写入后可由新实例读回")
    void roundTrip(@TempDir Path dir) {
        ComponentStateStore store = new ComponentStateStore(props(dir));
        store.load();
        ComponentModel.ComponentRecord rec = store.get("astrbot");
        rec.state = "RUNNING";
        rec.pid = 42L;
        rec.owned = true;
        store.put("astrbot", rec);

        ComponentStateStore reloaded = new ComponentStateStore(props(dir));
        reloaded.load();
        ComponentModel.ComponentRecord back = reloaded.get("astrbot");
        assertEquals("RUNNING", back.state);
        assertEquals(42L, back.pid);
        assertTrue(back.owned);
    }

    @Test
    @DisplayName("损坏文件：备份为 .corrupt-* 并降级为空状态")
    void corruptFileRecovered(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("components-state.json"), "{not-json");
        ComponentStateStore store = new ComponentStateStore(props(dir));
        store.load();

        assertEquals("STOPPED", store.get("astrbot").state);
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().contains(".corrupt-")));
        }
    }
}
