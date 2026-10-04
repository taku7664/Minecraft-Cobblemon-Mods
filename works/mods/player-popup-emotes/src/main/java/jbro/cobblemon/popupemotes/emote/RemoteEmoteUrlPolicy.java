package jbro.cobblemon.popupemotes.emote;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public final class RemoteEmoteUrlPolicy {
    public static final int MAX_URL_LENGTH = 2048;

    private RemoteEmoteUrlPolicy() {
    }

    public static boolean acceptsSyntax(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_URL_LENGTH) {
            return false;
        }
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getFragment() != null) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            host = host.toLowerCase(Locale.ROOT);
            if ("localhost".equals(host) || host.endsWith(".localhost")) {
                return false;
            }
            if (looksLikeAddressLiteral(host)) {
                return isPublic(InetAddress.getByName(stripBrackets(host)));
            }
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    public static URI requireUri(String value) throws URISyntaxException {
        if (!acceptsSyntax(value)) {
            throw new URISyntaxException(String.valueOf(value), "URL is not allowed");
        }
        return new URI(value);
    }

    public static boolean isKnownWebPage(String value) {
        if (!acceptsSyntax(value)) {
            return false;
        }
        try {
            String host = new URI(value).getHost().toLowerCase(Locale.ROOT);
            return "imgur.com".equals(host) || "www.imgur.com".equals(host);
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    public static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress()
            || address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            return first != 0
                && first != 127
                && !(first == 100 && second >= 64 && second <= 127)
                && !(first == 198 && (second == 18 || second == 19))
                && first < 224;
        }
        if (address instanceof Inet6Address) {
            int first = bytes[0] & 0xFF;
            return (first & 0xFE) != 0xFC;
        }
        return false;
    }

    private static boolean looksLikeAddressLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("[0-9.]+");
    }

    private static String stripBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }
}
