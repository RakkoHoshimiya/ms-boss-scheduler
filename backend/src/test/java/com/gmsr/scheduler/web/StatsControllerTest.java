package com.gmsr.scheduler.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.HttpStatus;

class StatsControllerTest {

    @Test
    void reportsHealthyOnlyAfterStartupAcceptsRequests() {
        ApplicationAvailability availability = mock(ApplicationAvailability.class);
        when(availability.getReadinessState())
                .thenReturn(ReadinessState.REFUSING_TRAFFIC, ReadinessState.ACCEPTING_TRAFFIC);
        StatsController controller = new StatsController(availability);

        var starting = controller.health();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, starting.getStatusCode());
        assertEquals("DOWN", starting.getBody().get("status"));

        var ready = controller.health();
        assertEquals(HttpStatus.OK, ready.getStatusCode());
        assertEquals("UP", ready.getBody().get("status"));
        assertEquals("gmsr-scheduler", ready.getBody().get("service"));
    }
}
