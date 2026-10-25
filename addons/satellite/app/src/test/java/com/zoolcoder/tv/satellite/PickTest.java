package com.zoolcoder.tv.satellite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PickTest {
    private static Pick.Input port(int n, boolean connected) {
        return new Pick.Input("com.tv/.Hdmi/HW" + n, null, "HDMI " + n, "", true, connected);
    }

    private static final List<Pick.Input> THREE = Arrays.asList(
            port(3, false), port(1, false), port(2, true),
            new Pick.Input("com.tv/.Av/HW9", null, "AV", "", false, true));

    @Test
    public void emptyRuleTakesTheConnectedHdmiPort() {
        assertEquals("com.tv/.Hdmi/HW2", Pick.choose(THREE, ""));
        assertEquals("com.tv/.Hdmi/HW2", Pick.choose(THREE, null));
    }

    @Test
    public void emptyRuleFallsBackToHdmi1WhenNothingIsConnected() {
        List<Pick.Input> none = Arrays.asList(port(3, false), port(1, false), port(2, false));
        assertEquals("com.tv/.Hdmi/HW1", Pick.choose(none, ""));
    }

    @Test
    public void emptyRuleNeverPicksAv() {
        assertNull(Pick.choose(Collections.singletonList(new Pick.Input("com.tv/.Av/HW9", null, "AV", "", false, true)), ""));
    }

    @Test
    public void aNumberALabelOrAnIdEndNamesThePort() {
        assertEquals("com.tv/.Hdmi/HW3", Pick.choose(THREE, "3"));
        assertEquals("com.tv/.Hdmi/HW3", Pick.choose(THREE, "hdmi 3"));
        assertEquals("com.tv/.Hdmi/HW3", Pick.choose(THREE, "HW3"));
        assertEquals("com.tv/.Av/HW9", Pick.choose(THREE, "AV"));
        assertNull(Pick.choose(THREE, "4"));
    }

    @Test
    public void theReceiversOwnNameWinsOverItsPort() {
        List<Pick.Input> withDevice = Arrays.asList(port(1, true), port(2, true),
                new Pick.Input("com.tv/.Hdmi/HW2/CEC1", "com.tv/.Hdmi/HW2", "Nilesat HD", "", true, true));
        assertEquals("com.tv/.Hdmi/HW2/CEC1", Pick.choose(withDevice, "nilesat hd"));
        // The port's own name from Settings > Inputs counts too.
        List<Pick.Input> named = Arrays.asList(port(1, true),
                new Pick.Input("com.tv/.Hdmi/HW2", null, "HDMI 2", "Receiver", true, true));
        assertEquals("com.tv/.Hdmi/HW2", Pick.choose(named, "receiver"));
    }

    @Test
    public void unnumberedLabelsSortLast() {
        assertEquals(2, Pick.port("HDMI 2"));
        assertEquals(Integer.MAX_VALUE, Pick.port("Component"));
    }
}
