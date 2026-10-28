package io.github.jerryt92.j2agent.controller;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ChatControllerBackgroundTest {
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"background\":null}", "{\"background\":false}",
            "{\"background\":\"true\"}", "{\"background\":1}", "{\"background\":{}}"})
    void onlyBooleanTrueEnablesBackground(String json) {
        assertEquals(Boolean.FALSE, ChatController.parseWsRequest(json).getBackground());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"background\":true}", "{\"background\":true,\"contextId\":\"ctx\"}"})
    void explicitTrueEnablesBackground(String json) {
        assertEquals(Boolean.TRUE, ChatController.parseWsRequest(json).getBackground());
    }
}
