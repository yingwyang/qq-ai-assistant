package com.qqai.adapter.tts;

import com.qqai.capability.HealthStatus;
import com.qqai.capability.tts.TtsProvider;
import com.qqai.service.GptSovitsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;

/** GPT-SoVITS 的 TtsProvider 实现：只做委托与健康探测。 */
@Service
public class GptSovitsTtsAdapter implements TtsProvider {

    private final GptSovitsService gptSovitsService;

    @Value("${gpt-sovits.api-url:http://127.0.0.1:8000}")
    private String apiUrl;

    public GptSovitsTtsAdapter(GptSovitsService gptSovitsService) {
        this.gptSovitsService = gptSovitsService;
    }

    @Override
    public String synthesize(String text, Long userId, String character) throws Exception {
        return gptSovitsService.generateVoice(text, userId, character);
    }

    @Override
    public List<Map<String, String>> listCharacters() {
        return gptSovitsService.listCharacters();
    }

    @Override
    public String currentCharacter(Long userId) {
        return gptSovitsService.getUserCharacter(userId);
    }

    @Override
    public void switchCharacter(Long userId, String character) throws Exception {
        gptSovitsService.setCharacterForUser(userId, character);
    }

    @Override
    public HealthStatus health() {
        try {
            java.net.URI uri = java.net.URI.create(apiUrl);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(uri.getHost(),
                        uri.getPort() > 0 ? uri.getPort() : 8000), 1000);
            }
            return new HealthStatus(true, "GPT-SoVITS " + apiUrl + " 可连接");
        } catch (Exception e) {
            return new HealthStatus(false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
