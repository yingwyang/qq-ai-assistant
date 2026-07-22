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

    public UserSettings saveSettings(String userId, String botName, String botAvatar, String userAvatar) {
        if (userId == null || userId.isEmpty()) {
            userId = "default";
        }
        UserSettings settings = userSettingsRepository.findByUserId(userId)
                .orElse(new UserSettings());
        settings.setUserId(userId);
        if (botName != null) settings.setBotName(botName);
        if (botAvatar != null) settings.setBotAvatar(botAvatar);
        if (userAvatar != null) settings.setUserAvatar(userAvatar);
        return userSettingsRepository.save(settings);
    }
}
