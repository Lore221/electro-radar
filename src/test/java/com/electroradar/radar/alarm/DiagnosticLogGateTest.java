package com.electroradar.radar.alarm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DiagnosticLogGateTest {
    @Test void firstObservationIsImmediateAndUnchangedReportsArePeriodic() {
        var gate = new DiagnosticLogGate<String>(10, 20);
        assertTrue(gate.due("shell", "SUPPORTED", 100));
        gate.sent("shell", "SUPPORTED", 100);
        assertFalse(gate.due("shell", "SUPPORTED", 101));
        assertFalse(gate.due("shell", "SUPPORTED", 119));
        assertTrue(gate.due("shell", "SUPPORTED", 120));
    }

    @Test void changedReasonAndTimeRollbackAreReportedImmediately() {
        var gate = new DiagnosticLogGate<String>(10, 20);
        gate.sent("shell", "SUPPORTED", 100);
        assertTrue(gate.due("shell", "IN_GROUND", 101));
        assertTrue(gate.due("shell", "SUPPORTED", 0));
    }

    @Test void unsentReportsRemainDueAndMemoryIsBounded() {
        var gate = new DiagnosticLogGate<Integer>(2, 20);
        assertTrue(gate.due(1, "new", 0));
        assertTrue(gate.due(1, "new", 1));
        gate.sent(1, "new", 1);
        gate.sent(2, "new", 1);
        gate.sent(3, "new", 1);
        assertEquals(2, gate.size());
        assertTrue(gate.due(1, "new", 2));
        assertFalse(gate.due(3, "new", 2));
    }
}
