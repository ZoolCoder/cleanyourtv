package com.zoolcoder.tv.health;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class AddressFilterTest {
    private static final List<AddressFilter.Subnet> HOME = Arrays.asList(
            subnet("192.168.1.50", 24), subnet("fe80::1234", 64));

    private static AddressFilter.Subnet subnet(String literal, int prefix) {
        try {
            return new AddressFilter.Subnet(InetAddress.getByName(literal), prefix);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static boolean allowed(String literal, List<AddressFilter.Subnet> home) throws Exception {
        return AddressFilter.allowed(InetAddress.getByName(literal), () -> home);
    }

    @Test
    public void theTvsOwnSubnetAndThisTvAreAllowed() throws Exception {
        for (String ok : new String[] {"127.0.0.1", "::1", "192.168.1.1", "192.168.1.7", "192.168.1.254",
                "::ffff:192.168.1.7", "fe80::abcd"}) {
            assertTrue(ok, allowed(ok, HOME));
        }
    }

    @Test
    public void otherPrivateNetworksAreRefused() throws Exception {
        for (String no : new String[] {"192.168.2.7", "192.168.0.7", "10.0.0.5", "172.16.0.1", "169.254.10.1",
                "fe80:0:0:1::1", "::ffff:10.0.0.5"}) {
            assertFalse(no, allowed(no, HOME));
        }
    }

    @Test
    public void everythingRoutableIsRefused() throws Exception {
        for (String no : new String[] {"8.8.8.8", "100.64.0.1", "1.1.1.1", "2001:4860:4860::8888", "0.0.0.0",
                "224.0.0.1", "::ffff:8.8.8.8"}) {
            assertFalse(no, allowed(no, HOME));
        }
    }

    @Test
    public void aRoutableSubnetLetsNobodyIn() throws Exception {
        // A TV handed a public address: its subnet is still not a home network.
        assertFalse(allowed("8.8.8.9", Collections.singletonList(subnet("8.8.8.8", 24))));
    }

    @Test
    public void prefixesThatSplitAByte() throws Exception {
        List<AddressFilter.Subnet> home = Collections.singletonList(subnet("10.1.20.9", 20));
        assertTrue(allowed("10.1.16.1", home));
        assertTrue(allowed("10.1.31.254", home));
        assertFalse(allowed("10.1.32.1", home));
        assertFalse(allowed("10.1.15.255", home));
    }

    @Test
    public void noSubnetLetsOnlyThisTvIn() throws Exception {
        assertTrue(allowed("127.0.0.1", Collections.emptyList()));
        assertFalse(allowed("192.168.1.7", Collections.emptyList()));
    }

    @Test
    public void noAddressIsRefused() {
        assertFalse(AddressFilter.allowed(null, () -> HOME));
    }

    @Test
    public void aMappedIpv6PeerIsJudgedAsIpv4() throws Exception {
        byte[] mapped = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, (byte) 192, (byte) 168, 1, 7};
        assertTrue(AddressFilter.allowed(Inet6Address.getByAddress(null, mapped, -1), () -> HOME));
        mapped[14] = 2;
        assertFalse(AddressFilter.allowed(Inet6Address.getByAddress(null, mapped, -1), () -> HOME));
    }

    @Test
    public void outOfRangePrefixesAreHeldToTheAddress() throws Exception {
        // A negative prefix acts as /0 and a too-long one as a single address; private-only still holds.
        assertTrue(allowed("192.168.9.9", Collections.singletonList(subnet("192.168.1.50", -4))));
        assertFalse(allowed("8.8.8.8", Collections.singletonList(subnet("192.168.1.50", -4))));
        assertTrue(allowed("192.168.1.50", Collections.singletonList(subnet("192.168.1.50", 99))));
        assertFalse(allowed("192.168.1.51", Collections.singletonList(subnet("192.168.1.50", 99))));
        assertTrue(allowed("fe80::abcd", Collections.singletonList(subnet("fe80::abcd", 128))));
        assertFalse(allowed("fe80::abce", Collections.singletonList(subnet("fe80::abcd", 128))));
    }

    @Test
    public void subnetsAreNotListedForAPeerRefusedAnyway() {
        assertFalse(AddressFilter.allowed(publicPeer(), () -> {
            throw new AssertionError("listed the interfaces for a public peer");
        }));
    }

    private static InetAddress publicPeer() {
        try {
            return InetAddress.getByName("8.8.8.8");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
