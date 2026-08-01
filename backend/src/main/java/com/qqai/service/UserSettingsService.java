package com.qqai.service;

import com.qqai.entity.UserSettings;
import com.qqai.repository.UserSettingsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
        if (astrbotApiKey != null) settings.setAstrbotApiKey(astrbotApiKey);
        if (llmApiKey != null) settings.setLlmApiKey(llmApiKey);
        if (llmBaseUrl != null) settings.setLlmBaseUrl(llmBaseUrl);
        if (llmModel != null) settings.setLlmModel(llmModel);
        if (llmModels != null) settings.setLlmModels(llmModels);
        if (providers != null) settings.setProviders(providers);
        return userSettingsRepository.save(settings);
    }
}
