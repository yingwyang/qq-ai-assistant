package com.qqai.service;

import com.qqai.entity.UserSettings;
import com.qqai.repository.UserSettingsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

@Service
public class UserSettingsService {

    @Autowired
    private UserSettingsRepository userSettingsRepository;

    public Optional<UserSettings> findByUserId(String userId) {
        return userSettingsRepository.findByUserId(userId);
    }

    public UserSettings save(UserSettings settings) {
        return userSettingsRepository.save(settings);
    }

    public UserSettings saveSettings(String userId, String botName,
                                     String astrbotApiKey, String llmApiKey, String llmBaseUrl,
                                     String llmModel, String llmModels, String providers) {
        if (userId == null || userId.isEmpty()) {
            userId = "default";
        }
        UserSettings settings = userSettingsRepository.findByUserId(userId)
                .orElse(new UserSettings());
        settings.setUserId(userId);
        if (botName != null) settings.setBotName(botName);

        // API Key 掩码保护：若收到掩码值(含****)则保留数据库旧值
        if (astrbotApiKey != null) {
            if (astrbotApiKey.contains("****") && settings.getAstrbotApiKey() != null
                    && !settings.getAstrbotApiKey().isEmpty()) {
                // 保留旧值，不覆盖
            } else {
                settings.setAstrbotApiKey(astrbotApiKey);
            }
        }
        if (llmApiKey != null) {
            if (llmApiKey.contains("****") && settings.getLlmApiKey() != null
                    && !settings.getLlmApiKey().isEmpty()) {
                // 保留旧值，不覆盖
            } else {
                settings.setLlmApiKey(llmApiKey);
            }
        }

        if (llmBaseUrl != null) settings.setLlmBaseUrl(llmBaseUrl);
        if (llmModel != null) settings.setLlmModel(llmModel);
        if (llmModels != null) settings.setLlmModels(llmModels);
        if (providers != null) {
            // providers 数组中 apiKey 掩码保护
            if (providers.contains("****") && settings.getProviders() != null
                    && !settings.getProviders().isEmpty()) {
                // 尝试合并：用旧 providers 中同名 provider 的 apiKey 替换掩码
                try {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    java.util.List<Map<String, Object>> newProviders = mapper.readValue(providers,
                            new com.fasterxml.jackson.core.type.TypeReference<java.util.List<Map<String, Object>>>() {});
                    java.util.List<Map<String, Object>> oldProviders = mapper.readValue(settings.getProviders(),
                            new com.fasterxml.jackson.core.type.TypeReference<java.util.List<Map<String, Object>>>() {});
                    for (Map<String, Object> np : newProviders) {
                        String apiKey = (String) np.get("apiKey");
                        if (apiKey != null && apiKey.contains("****")) {
                            String name = (String) np.get("name");
                            for (Map<String, Object> op : oldProviders) {
                                if (name != null && name.equals(op.get("name"))
                                        && op.get("apiKey") != null && !((String) op.get("apiKey")).contains("****")) {
                                    np.put("apiKey", op.get("apiKey"));
                                    break;
                                }
                            }
                        }
                    }
                    settings.setProviders(mapper.writeValueAsString(newProviders));
                } catch (Exception e) {
                    // 合并失败，保留旧值
                }
            } else {
                settings.setProviders(providers);
            }
        }
        return userSettingsRepository.save(settings);
    }
}
