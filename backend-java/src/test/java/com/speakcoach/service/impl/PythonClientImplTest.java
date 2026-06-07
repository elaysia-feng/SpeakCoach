package com.speakcoach.service.impl;

import com.speakcoach.service.PythonClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class PythonClientImplTest {

    @Test
    void preservesStructuredJsonFieldsFromPythonResponse() {
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body("""
                                {
                                  "ai_reply": "hello",
                                  "audio_url": "/api/audio/1/s/turn_1.wav",
                                  "corrections": [{"original":"a","corrected":"an"}],
                                  "shadow_answer": "shadow",
                                  "ability_score": {"grammar":72,"vocabulary":70},
                                  "pronunciation_score": 81,
                                  "strategy": "normal_follow_up",
                                  "summary": {"next_focus":"grammar"}
                                }
                                """)
                        .build()))
                .build();

        PythonClient client = new PythonClientImpl(webClient);
        PythonClient.TurnResult result = client.turn(1L, "s", 1, "hi", "daily_chat");

        assertThat(result.corrections()).isEqualTo("[{\"original\":\"a\",\"corrected\":\"an\"}]");
        assertThat(result.abilityScore()).isEqualTo("{\"grammar\":72,\"vocabulary\":70}");
        assertThat(result.pronunciationScore()).isEqualTo("81");
        assertThat(result.summary()).isEqualTo("{\"next_focus\":\"grammar\"}");
    }
}
