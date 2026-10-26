package com.zoolcoder.tv.notes;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
}
