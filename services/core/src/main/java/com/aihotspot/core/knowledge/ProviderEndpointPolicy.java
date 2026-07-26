package com.aihotspot.core.knowledge;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ProviderEndpointPolicy {
    private final boolean production;

    public ProviderEndpointPolicy(@Value("${ai-hotspot.environment}") String environment) {
        this.production = "production".equalsIgnoreCase(environment);
    }

    public String validate(String value, boolean userOwned) {
        String normalized = value == null ? "" : value.strip().replaceAll("/+$", "");
        try {
            URI uri = URI.create(normalized);
            String host = uri.getHost();
            boolean localDevelopment = !production && !userOwned
                    && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host));
            if (host == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null
                    || (!"https".equalsIgnoreCase(uri.getScheme()) && !localDevelopment)) {
                throw new IllegalArgumentException();
            }
            String lowerHost = host.toLowerCase(Locale.ROOT);
            if (!localDevelopment && (lowerHost.equals("localhost") || lowerHost.endsWith(".localhost")
                    || lowerHost.endsWith(".local") || lowerHost.endsWith(".internal"))) {
                throw new IllegalArgumentException();
            }
            if (!localDevelopment) {
                InetAddress[] addresses = InetAddress.getAllByName(host);
                if (addresses.length == 0) throw new IllegalArgumentException();
                for (InetAddress address : addresses) {
                    if (!isPublic(address)) throw new IllegalArgumentException();
                }
            }
            return normalized;
        } catch (Exception error) {
            throw new IllegalArgumentException("服务地址必须是可解析的公网 HTTPS URL");
        }
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int a = Byte.toUnsignedInt(bytes[0]);
            int b = Byte.toUnsignedInt(bytes[1]);
            if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
            if (a == 100 && b >= 64 && b <= 127) return false;
            if (a == 169 && b == 254) return false;
            if (a == 172 && b >= 16 && b <= 31) return false;
            if (a == 192 && (b == 0 || b == 168)) return false;
            if (a == 198 && (b == 18 || b == 19 || b == 51)) return false;
            if (a == 203 && b == 0) return false;
            return true;
        }
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            if ((first & 0xfe) == 0xfc) return false;
            return !(first == 0x20 && second == 0x01
                    && Byte.toUnsignedInt(bytes[2]) == 0x0d
                    && Byte.toUnsignedInt(bytes[3]) == 0xb8);
        }
        return false;
    }
}
