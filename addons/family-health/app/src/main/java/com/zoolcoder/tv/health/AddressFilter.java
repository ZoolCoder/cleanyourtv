package com.zoolcoder.tv.health;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/** Which peers may talk to the server: this TV itself and the subnet the TV is on, nobody routable. */
final class AddressFilter {
    private AddressFilter() {
    }

    /** One network the TV is on: its address with the host bits cleared, and the prefix length. */
    static final class Subnet {
        private final byte[] network;
        private final int prefix;

        Subnet(InetAddress address, int prefix) {
            byte[] bytes = address.getAddress();
            this.prefix = Math.max(0, Math.min(prefix, bytes.length * 8));
            network = mask(bytes, this.prefix);
        }

        boolean contains(InetAddress peer) {
            byte[] bytes = peer.getAddress();
            return bytes.length == network.length && Arrays.equals(mask(bytes, prefix), network);
        }

        private static byte[] mask(byte[] bytes, int prefix) {
            byte[] out = bytes.clone();
            for (int i = 0; i < out.length; i++) {
                int keep = Math.max(0, Math.min(8, prefix - i * 8));
                out[i] &= (byte) (0xFF << (8 - keep));
            }
            return out;
        }
    }

    /**
     * Loopback, or a site-local or link-local address inside one of the TV's own subnets: a phone on a guest
     * network, another VLAN or a VPN range is refused even though its address is private. The TV's subnets
     * are only listed for a peer that passed the cheap checks.
     */
    static boolean allowed(InetAddress peer, Supplier<List<Subnet>> home) {
        if (peer == null || peer.isAnyLocalAddress() || peer.isMulticastAddress()) return false;
        if (peer.isLoopbackAddress()) return true;
        InetAddress p = unmapped(peer);
        if (!p.isSiteLocalAddress() && !p.isLinkLocalAddress()) return false;
        for (Subnet subnet : home.get()) {
            if (subnet.contains(p)) return true;
        }
        return false;
    }

    /** ::ffff:a.b.c.d, as a dual-stack socket may report an IPv4 phone, back to a.b.c.d. */
    private static InetAddress unmapped(InetAddress peer) {
        if (!(peer instanceof Inet6Address)) return peer;
        byte[] b = peer.getAddress();
        for (int i = 0; i < 10; i++) if (b[i] != 0) return peer;
        if (b[10] != (byte) 0xFF || b[11] != (byte) 0xFF) return peer;
        try {
            return InetAddress.getByAddress(Arrays.copyOfRange(b, 12, 16));
        } catch (UnknownHostException e) {
            return peer;
        }
    }
}
