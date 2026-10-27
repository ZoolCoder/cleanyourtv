package com.zoolcoder.tv.calendar;

import java.net.InetAddress;

/** Which peers may talk to the server: this TV itself and the home network, nobody routable. */
final class AddressFilter {
    private AddressFilter() {
    }

    /** Loopback, site-local (10/8, 172.16/12, 192.168/16) and link-local (169.254/16, fe80::/10) only. */
    static boolean allowed(InetAddress peer) {
        return peer != null && !peer.isAnyLocalAddress() && !peer.isMulticastAddress()
                && (peer.isLoopbackAddress() || peer.isSiteLocalAddress() || peer.isLinkLocalAddress());
    }
}
