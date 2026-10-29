package com.qqai.capability.tts;

import com.qqai.capability.HealthStatus;

import java.util.List;
import java.util.Map;

/** TTS 能力契约：合成、音色列表、当前音色与切换。 */
public interface TtsProvider {

    String synthesize(String text, Long userId, String character) throws Exception;

    List<Map<String, String>> listCharacters();

    String currentCharacter(Long userId);

    void switchCharacter(Long userId, String character) throws Exception;

    HealthStatus health();
}
