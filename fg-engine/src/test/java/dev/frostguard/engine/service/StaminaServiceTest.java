package dev.frostguard.engine.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StaminaServiceTest {

    @Test
    void setStaminaCapsValuesAboveTheGameLimit() {
        StaminaService service = StaminaService.getServices();

        service.setStamina(1001L, 250);

        assertEquals(200, service.getCurrentStamina(1001L));
    }

    @Test
    void addStaminaDoesNotExceedTheGameLimit() {
        StaminaService service = StaminaService.getServices();

        service.setStamina(1002L, 190);
        service.addStamina(1002L, 25);

        assertEquals(200, service.getCurrentStamina(1002L));
    }
}
