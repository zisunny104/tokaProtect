package dev.toka.protect.listener;

import org.junit.Test;
import static org.junit.Assert.*;

public class FlightListenerTest {
    @Test public void flightNeedsBothThePermissionAndAnAllowingPlace() {
        assertTrue(FlightListener.shouldFly(true, true));
        assertFalse(FlightListener.shouldFly(true, false));
        assertFalse(FlightListener.shouldFly(false, true));
        assertFalse(FlightListener.shouldFly(false, false));
    }
}
