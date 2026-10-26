package com.zoolcoder.tv.notes;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.InetAddress;

public class AddressFilterTest {
    private static boolean allowed(String literal) throws Exception {
        return AddressFilter.allowed(InetAddress.getByName(literal));
    }

    @Test
    public void homeNetworkAndThisTvAreAllowed() throws Exception {
        for (String ok : new String[] {"127.0.0.1", "::1", "10.0.0.5", "10.255.255.255", "172.16.0.1",
                "172.31.255.254", "192.168.1.50", "169.254.10.1", "fe80::1", "::ffff:192.168.1.7"}) {
            assertTrue(ok, allowed(ok));
        }
    }

    @Test
    public void everythingRoutableIsRefused() throws Exception {
        for (String no : new String[] {"8.8.8.8", "172.15.255.255", "172.32.0.1", "11.0.0.1", "192.169.0.1",
                "100.64.0.1", "1.1.1.1", "2001:4860:4860::8888", "0.0.0.0", "224.0.0.1", "::ffff:8.8.8.8"}) {
            assertFalse(no, allowed(no));
        }
    }

    @Test
    public void noAddressIsRefused() {
        assertFalse(AddressFilter.allowed(null));
    }
}
