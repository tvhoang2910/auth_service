package com.exam_bank.auth_service.dto.request;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class CreatePremiumPlanRequestJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldDeserializeCreatePremiumPlanRequestWithoutSlugAndFeatures() throws Exception {
        String json = """
                {
                  "name": "Premium Plus",
                  "price": 99000,
                  "durationDays": 30,
                  "lifetime": false,
                  "description": "test",
                  "active": true
                }
                """;

        CreatePremiumPlanRequest request = objectMapper.readValue(json, CreatePremiumPlanRequest.class);

        assertThat(request.name()).isEqualTo("Premium Plus");
        assertThat(request.description()).isEqualTo("test");
        assertThat(request.active()).isTrue();
    }
}
