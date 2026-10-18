package com.neuronest.backend.pro;

import com.neuronest.backend.config.ApiValidationException;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// The professional's social media links, seen only by the NeuroNest team to help verify identity.
// Stored as {"LINKEDIN": "https://..."}; platforms that are not sent are removed.
final class SocialLinks {

    static final int MAX_LINK = 200;

    // Platform key to the hosts its links may use (subdomains such as www. are allowed). WEBSITE has none:
    // any https host is accepted.
    static final Map<String, List<String>> PLATFORMS = platforms();

    private SocialLinks() {}

    private static Map<String, List<String>> platforms() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("LINKEDIN", List.of("linkedin.com"));
        map.put("INSTAGRAM", List.of("instagram.com"));
        map.put("FACEBOOK", List.of("facebook.com"));
        map.put("X", List.of("x.com", "twitter.com"));
        map.put("YOUTUBE", List.of("youtube.com", "youtu.be"));
        map.put("WEBSITE", List.of());
        return map;
    }

    /** Parses the socialLinks object from a request body. Null or an empty object clears all links. */
    static Map<String, String> parse(JsonNode value) {
        Map<String, String> links = new LinkedHashMap<>();
        if (value == null || value.isNull()) return links;
        if (!value.isObject()) {
            throw new ApiValidationException("socialLinks must be an object such as {\"LINKEDIN\": \"https://...\"}.");
        }
        for (Map.Entry<String, JsonNode> entry : value.properties()) {
            String platform = entry.getKey().trim().toUpperCase(Locale.ROOT);
            if (!PLATFORMS.containsKey(platform)) {
                throw new ApiValidationException("socialLinks can only contain " + String.join(", ", PLATFORMS.keySet())
                        + " (got '" + entry.getKey() + "').");
            }
            JsonNode link = entry.getValue();
            if (link == null || link.isNull()) continue;
            if (!link.isString()) throw new ApiValidationException("socialLinks." + platform + " must be text.");
            String trimmed = ProProfileService.trimToNull(link.asString());
            if (trimmed == null) continue;
            links.put(platform, check(platform, trimmed));
        }
        // Stored in a fixed platform order.
        Map<String, String> ordered = new LinkedHashMap<>();
        PLATFORMS.keySet().forEach(key -> { if (links.containsKey(key)) ordered.put(key, links.get(key)); });
        return ordered;
    }

    private static String check(String platform, String link) {
        String field = "socialLinks." + platform;
        if (link.length() > MAX_LINK) {
            throw new ApiValidationException(field + " must be at most " + MAX_LINK + " characters.");
        }
        if (!link.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new ApiValidationException(field + " must start with https://.");
        }
        String host = host(link);
        if (host == null) {
            throw new ApiValidationException(field + " must be a valid link.");
        }
        List<String> allowed = PLATFORMS.get(platform);
        if (!allowed.isEmpty() && allowed.stream().noneMatch(domain -> hostBelongsTo(host, domain))) {
            throw new ApiValidationException(field + " must be a link on " + String.join(" or ", allowed) + ".");
        }
        return link;
    }

    // The lower-case host of a well-formed https link without user info, or null.
    private static String host(String link) {
        try {
            URI uri = new URI(link);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || !host.contains(".")
                    || uri.getUserInfo() != null) {
                return null;
            }
            return host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static boolean hostBelongsTo(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }
}
