package com.savenest.metadata;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Locale;

/**
 * Decides which destinations the fetcher may connect to. The strict policy blocks loopback, private,
 * link-local (incl. cloud metadata 169.254.169.254), CGNAT, reserved, documentation, multicast and
 * IPv6 ULA/link-local/mapped/NAT64-embedded private targets, plus internal hostnames.
 */
public interface AddressPolicy {

    boolean isAllowedAddress(InetAddress address);

    boolean isAllowedHostname(String host);

    AddressPolicy STRICT = new AddressPolicy() {
        @Override
        public boolean isAllowedAddress(InetAddress a) {
            return !isBlocked(a);
        }

        @Override
        public boolean isAllowedHostname(String host) {
            String h = host.toLowerCase(Locale.ROOT);
            if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
            return !(h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local")
                    || h.endsWith(".internal") || h.endsWith(".localdomain") || h.endsWith(".home.arpa")
                    || h.equals("metadata") || !h.contains("."));
        }
    };

    static boolean isBlocked(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            return isBlockedV4(b);
        }
        if (a instanceof Inet6Address) {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            if ((b0 & 0xfe) == 0xfc) return true;                       // fc00::/7 unique local
            if (b0 == 0xfe && (b1 & 0xc0) == 0x80) return true;         // fe80::/10 link-local
            if (b0 == 0xfe && (b1 & 0xc0) == 0xc0) return true;         // fec0::/10 site-local (deprecated)
            if (b0 == 0x20 && b1 == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) return true; // 2001:db8::/32
            if (b0 == 0x20 && b1 == 0x02) {                             // 2002::/16 6to4: check embedded v4
                return isBlockedV4(new byte[]{b[2], b[3], b[4], b[5]});
            }
            boolean first10Zero = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) { first10Zero = false; break; }
            if (first10Zero && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {   // ::ffff:a.b.c.d mapped
                return isBlockedV4(new byte[]{b[12], b[13], b[14], b[15]});
            }
            boolean first12Zero = first10Zero && b[10] == 0 && b[11] == 0;
            if (first12Zero) return true;                               // ::/96 incl. ::, ::1, IPv4-compatible
            if (b0 == 0x00 && b1 == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b) {  // 64:ff9b::/96 NAT64
                return isBlockedV4(new byte[]{b[12], b[13], b[14], b[15]});
            }
            if (b0 == 0x01 && b1 == 0x00 && b[2] == 0 && b[3] == 0) return true;      // 100::/64 discard
            return false;
        }
        return true;
    }

    private static boolean isBlockedV4(byte[] b) {
        int o0 = b[0] & 0xff, o1 = b[1] & 0xff, o2 = b[2] & 0xff;
        return o0 == 0                                   // 0.0.0.0/8
                || o0 == 10                              // 10/8
                || o0 == 127                             // loopback
                || (o0 == 100 && (o1 & 0xc0) == 64)      // 100.64/10 CGNAT
                || (o0 == 169 && o1 == 254)              // link-local, cloud metadata
                || (o0 == 172 && (o1 & 0xf0) == 16)      // 172.16/12
                || (o0 == 192 && o1 == 0 && o2 == 0)     // 192.0.0/24 IETF
                || (o0 == 192 && o1 == 0 && o2 == 2)     // TEST-NET-1
                || (o0 == 192 && o1 == 88 && o2 == 99)   // 6to4 relay
                || (o0 == 192 && o1 == 168)              // 192.168/16
                || (o0 == 198 && (o1 & 0xfe) == 18)      // 198.18/15 benchmarking
                || (o0 == 198 && o1 == 51 && o2 == 100)  // TEST-NET-2
                || (o0 == 203 && o1 == 0 && o2 == 113)   // TEST-NET-3
                || o0 >= 224;                            // multicast, reserved, broadcast
    }
}
