package com.exam_bank.auth_service.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class CreatePremiumPlanRequestJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

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

    @Test
    void shouldAllowLifetimePlanWhenDurationDaysIsZero() {
        CreatePremiumPlanRequest request = new CreatePremiumPlanRequest(
                "Lifetime Plan",
                java.math.BigDecimal.valueOf(199000),
                0,
                true,
                "Lifetime access",
                true);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void shouldRejectNonLifetimePlanWhenDurationDaysIsZero() {
        CreatePremiumPlanRequest request = new CreatePremiumPlanRequest(
                "Monthly Plan",
                java.math.BigDecimal.valueOf(99000),
                0,
                false,
                "Monthly access",
                true);

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("durationDaysValid");
    }
}
