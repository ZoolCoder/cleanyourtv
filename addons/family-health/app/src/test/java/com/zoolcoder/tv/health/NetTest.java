package com.zoolcoder.tv.health;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NetTest {
    @Test
    public void wifiAndEthernetAreHome() {
        assertTrue(Net.home("wlan0"));
        assertTrue(Net.home("eth0"));
    }

    @Test
    public void vpnsDirectGroupsAndHotspotsAreNot() {
        for (String name : new String[] {"tun0", "wg0", "ppp0", "p2p-wlan0-0", "p2p0", "ap0", "swlan0", "rndis0", "rmnet_data0", "ipsec0"}) {
            assertFalse(name, Net.home(name));
        }
        assertFalse(Net.home(null));
    }
}
