package com.traceability.api.web.ratelimit;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

/**
 * ADR-053 D3: la IP del cliente. Es la de la conexión, salvo que la conexión venga de un proxy de confianza
 * configurado ({@code traceability.rate-limit.client-ip.trusted-proxies}: IP exactas o rangos CIDR, por ejemplo el
 * {@code cloudflared} del mismo equipo en {@code 127.0.0.1}). Solo entonces se toma la cabecera configurada
 * ({@code CF-Connecting-IP} por defecto), y solo si trae una única IP literal válida. {@code X-Forwarded-For} nunca se
 * lee.
 */
public final class ClientIpResolver {

    private record Cidr(byte[] network, int prefix) {
        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = 0xFF << (8 - rest) & 0xFF;
            return (address[full] & mask) == (network[full] & mask);
        }
    }

    private final List<Cidr> trustedProxies;
    private final String header;

    public ClientIpResolver(List<String> trustedProxies, String header) {
        List<Cidr> parsed = new ArrayList<>();
        for (String raw : trustedProxies == null ? List.<String>of() : trustedProxies) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            parsed.add(parseCidr(entry));
        }
        if (!parsed.isEmpty() && (header == null || header.isBlank())) {
            throw new RateLimitConfigurationException(
                    "traceability.rate-limit.client-ip.header is required when trusted proxies are configured");
        }
        this.trustedProxies = List.copyOf(parsed);
        this.header = header;
    }

    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (trustedProxies.isEmpty() || !isTrusted(remote)) {
            return remote;
        }
        String forwarded = request.getHeader(header);
        if (forwarded == null) {
            return remote;
        }
        String candidate = forwarded.trim();
        byte[] literal = literalAddress(candidate);
        return literal == null ? remote : candidate;
    }

    private boolean isTrusted(String remote) {
        byte[] address = literalAddress(remote);
        if (address == null) {
            return false;
        }
        for (Cidr cidr : trustedProxies) {
            if (cidr.contains(address)) {
                return true;
            }
        }
        return false;
    }

    private static Cidr parseCidr(String entry) {
        String[] parts = entry.split("/", -1);
        byte[] network = parts.length <= 2 ? literalAddress(parts[0]) : null;
        if (network == null) {
            throw new RateLimitConfigurationException("invalid trusted proxy (literal IP or CIDR expected): " + entry);
        }
        int prefix = network.length * 8;
        if (parts.length == 2) {
            try {
                prefix = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new RateLimitConfigurationException("invalid CIDR prefix: " + entry);
            }
            if (prefix < 0 || prefix > network.length * 8) {
                throw new RateLimitConfigurationException("invalid CIDR prefix: " + entry);
            }
        }
        return new Cidr(network, prefix);
    }

    /** Bytes de una IP literal (v4 o v6), o {@code null}: nunca resuelve nombres por DNS. */
    static byte[] literalAddress(String value) {
        if (value == null || value.isEmpty() || value.length() > 45 || value.contains(",") || value.contains(" ")) {
            return null;
        }
        boolean v4 = value.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean v6 = value.contains(":") && value.matches("[0-9A-Fa-f:.]+");
        if (!v4 && !v6) {
            return null;
        }
        if (v4) {
            for (String octet : value.split("\\.")) {
                if (Integer.parseInt(octet) > 255) {
                    return null;
                }
            }
        }
        try {
            return InetAddress.getByName(value).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
