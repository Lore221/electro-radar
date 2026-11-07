package com.electroradar.radar.linker;
import com.electroradar.radar.network.NetworkKind;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LinkerSelectionTest {
    @Test void repeatedAlarmClicksAlternateButOtherDeviceStartsAgain() {
        var first = LinkerSelection.empty().click("alarm-a", true, false);
        assertEquals(NetworkKind.RADAR, first.kind());
        var second = first.click("alarm-a", true, false);
        assertEquals(NetworkKind.INTERCEPTION, second.kind());
        assertEquals(NetworkKind.RADAR, second.click("alarm-a", true, false).kind());
        assertEquals(NetworkKind.RADAR, second.click("radar", false, false).click("alarm-a", true, false).kind());
        assertEquals(NetworkKind.RADAR, LinkerSelection.empty().click("alarm-a", true, false).kind());
    }
    @Test void disconnectingAnotherDeviceResetsAlarmCycle() {
        var first = LinkerSelection.empty().click("alarm-a", true, false);
        var disconnected = first.click("radar-b", false, true);
        assertEquals(NetworkKind.RADAR, disconnected.click("alarm-a", true, false).kind());
        assertEquals(NetworkKind.RADAR, first.click("alarm-a", true, true).click("alarm-a", true, false).kind());
    }
}
