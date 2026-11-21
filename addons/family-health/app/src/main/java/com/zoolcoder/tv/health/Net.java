package com.zoolcoder.tv.health;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** The TV's own home-network IPv4 addresses, found by listing the interfaces: no Wi-Fi permission needed. */
final class Net {
    private Net() {
    }

    static List<String> homeAddresses() {
        List<String> found = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        found.add(address.getHostAddress());
                    }
                }
            }
        } catch (SocketException ignored) {
            // No interfaces to list: the screen says there is no home network address.
        }
        return found;
    }

    /** Interfaces that are not the home network: VPNs, Wi-Fi Direct and Miracast, the TV's own hotspot, USB tethering, mobile data. */
    private static final String[] NOT_HOME = {"tun", "tap", "wg", "ppp", "ipsec", "p2p", "ap", "swlan", "softap", "rndis", "usb", "rmnet", "ccmni"};
    private static final long CACHE_MILLIS = 5_000L;
    private static final Object LOCK = new Object();
    private static InetAddress cachedFor;
    private static long cachedAt;
    private static List<AddressFilter.Subnet> cached = Collections.emptyList();

    /**
     * The subnets of the interface a phone's connection arrived on, when that interface is the home Wi-Fi or
     * Ethernet: a peer on a VPN, a Wi-Fi Direct group or the TV's hotspot finds none. Kept for 5 seconds, so a
     * flood of connections does not list the interfaces for each one.
     */
    static List<AddressFilter.Subnet> subnetsOf(InetAddress local) {
        long now = System.nanoTime() / 1_000_000L;
        synchronized (LOCK) {
            if (local != null && local.equals(cachedFor) && now - cachedAt < CACHE_MILLIS) return cached;
        }
        List<AddressFilter.Subnet> found = read(local);
        synchronized (LOCK) {
            cachedFor = local;
            cachedAt = now;
            cached = found;
        }
        return found;
    }

    private static List<AddressFilter.Subnet> read(InetAddress local) {
        List<AddressFilter.Subnet> found = new ArrayList<>();
        try {
            NetworkInterface nif = local == null ? null : NetworkInterface.getByInetAddress(local);
            if (nif == null || !nif.isUp() || nif.isLoopback() || !home(nif.getName())) return found;
            for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                InetAddress address = ia.getAddress();
                if (address == null) continue;
                boolean v4 = address instanceof Inet4Address;
                // An IPv4 prefix shorter than /8 is unknown, not a network: it would let in every private address.
                if (v4 && address.isSiteLocalAddress() && ia.getNetworkPrefixLength() >= 8) {
                    found.add(new AddressFilter.Subnet(address, ia.getNetworkPrefixLength()));
                } else if (!v4 && address.isLinkLocalAddress()) {
                    found.add(new AddressFilter.Subnet(address, ia.getNetworkPrefixLength()));
                }
            }
        } catch (SocketException ignored) {
            // No interface: no subnet, so only this TV itself is let in.
        }
        return found;
    }

    static boolean home(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        for (String prefix : NOT_HOME) if (n.startsWith(prefix)) return false;
        return true;
    }
}
