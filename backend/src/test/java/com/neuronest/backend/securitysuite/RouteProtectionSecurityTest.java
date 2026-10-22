package com.neuronest.backend.securitysuite;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Route enumeration: every mapped route under /api/admin, /api/pro, /api/bookings and /api/me is called
 * with no token and must answer 401. The routes come from Spring's own request mappings, so a newly
 * added route is checked automatically. A failure means a route in one of these areas answers without a
 * sign-in. Only the routes in PUBLIC_BY_DESIGN are skipped.
 */
class RouteProtectionSecurityTest extends SecuritySuiteBase {

    private static final List<String> PROTECTED_AREAS = List.of("/api/admin", "/api/pro", "/api/bookings", "/api/me");

    // Public on purpose. Keep this list short and explain every entry.
    private static final Set<String> PUBLIC_BY_DESIGN = Set.of(
            // The admin sign-in itself: it takes a username and password and returns a token.
            "POST /api/admin/login");

    // A well-formed body for routes that validate it before their own sign-in check (the /api/me routes
    // check the user inside the controller). Every other route gets "{}".
    private static final Map<String, String> EXAMPLE_BODIES = Map.of(
            "PUT /api/me/memory-settings", "{\"enabled\":true}");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    /** "METHOD /pattern" for every route in the protected areas. */
    private Map<String, String> protectedRoutes() {
        Map<String, String> routes = new TreeMap<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                if (PROTECTED_AREAS.stream().noneMatch(area -> pattern.equals(area) || pattern.startsWith(area + "/"))) {
                    continue;
                }
                for (RequestMethod method : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                    routes.put(method.name() + " " + pattern, pattern);
                }
            }
        }
        return routes;
    }

    @Test
    void everyProtectedRouteAnswers401WithoutAToken() throws Exception {
        Map<String, String> routes = protectedRoutes();
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (var route : routes.entrySet()) {
            if (PUBLIC_BY_DESIGN.contains(route.getKey())) continue;
            String method = route.getKey().substring(0, route.getKey().indexOf(' '));
            // Any id works: the request must be refused before it is looked up.
            String path = route.getValue().replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
            MockHttpServletRequestBuilder call = request(HttpMethod.valueOf(method), path);
            if (!method.equals("GET") && !method.equals("DELETE")) {
                call.contentType(MediaType.APPLICATION_JSON).content(EXAMPLE_BODIES.getOrDefault(route.getKey(), "{}"));
            }
            MvcResult result = mvc.perform(call).andReturn();
            int status = result.getResponse().getStatus();
            if (status != 401) problems.add(route.getKey() + " answered " + status);
            checked++;
        }
        assertThat(problems).isEmpty();
        // The enumeration really covered every area.
        assertThat(checked).isGreaterThanOrEqualTo(40);
        for (String area : PROTECTED_AREAS) {
            assertThat(routes.values()).as(area).anyMatch(pattern -> pattern.startsWith(area));
        }
    }

    @Test
    void theAllowListOnlyNamesRoutesThatExist() {
        assertThat(protectedRoutes().keySet()).containsAll(PUBLIC_BY_DESIGN);
    }
}
