package com.qqai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * GPT-SoVITS 角色模型配置
 * 从 application.yml 的 gpt-sovits.characters 加载训练好的音色模型列表
 */
@Configuration
@ConfigurationProperties(prefix = "gpt-sovits")
public class GptSovitsConfig {

    private String modelRoot;
    private String defaultCharacter;
    private List<CharacterConfig> characters = new ArrayList<>();

    public String getModelRoot() { return modelRoot; }
    public void setModelRoot(String modelRoot) { this.modelRoot = modelRoot; }

    public String getDefaultCharacter() { return defaultCharacter; }
    public void setDefaultCharacter(String defaultCharacter) { this.defaultCharacter = defaultCharacter; }

    public List<CharacterConfig> getCharacters() { return characters; }
    public void setCharacters(List<CharacterConfig> characters) { this.characters = characters; }

    /**
     * 根据角色名查找配置
     */
    public CharacterConfig findCharacter(String name) {
        if (name == null || name.isBlank()) return null;
        return characters.stream()
                .filter(c -> name.equals(c.getName()))
                .findFirst()
                .orElse(null);
    }

    public static class CharacterConfig {
        private String name;
        private String label;
        private String gptPath;
        private String sovitsPath;
        private String refAudio;
        private String promptText;
        private String promptLang = "ja";

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }

        public String getGptPath() { return gptPath; }
        public void setGptPath(String gptPath) { this.gptPath = gptPath; }

        public String getSovitsPath() { return sovitsPath; }
        public void setSovitsPath(String sovitsPath) { this.sovitsPath = sovitsPath; }

        public String getRefAudio() { return refAudio; }
        public void setRefAudio(String refAudio) { this.refAudio = refAudio; }

        public String getPromptText() { return promptText; }
        public void setPromptText(String promptText) { this.promptText = promptText; }

        public String getPromptLang() { return promptLang; }
        public void setPromptLang(String promptLang) { this.promptLang = promptLang; }
    }
}
