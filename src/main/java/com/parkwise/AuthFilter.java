package com.parkwise;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Set;

/* Every API call needs a signed-in user, apart from the three that get someone signed in. Requests sent by other
   websites are refused outright. */
@Component
public class AuthFilter extends OncePerRequestFilter {
    static final String USER = "parkwiseUser";
    private static final Set<String> OPEN = Set.of("/api/auth/status", "/api/auth/login", "/api/auth/setup");
    private final JdbcTemplate db;

    public AuthFilter(JdbcTemplate db) { this.db = db; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        // the servlet path has already had ';' parameters and '..' removed, so they cannot be used to step round this check
        String path = request.getServletPath();
        if (!path.toLowerCase().startsWith("/api")) { chain.doFilter(request, response); return; }
        if (!"GET".equals(request.getMethod()) && !sameOrigin(request)) { deny(response, 403, "Requests from other websites are not allowed."); return; }
        if (!OPEN.contains(path)) {
            Map<String, Object> user = AuthController.sessionUser(db, request.getSession(false));
            if (user == null) { deny(response, 401, "Please sign in."); return; }
            request.setAttribute(USER, user);
        }
        chain.doFilter(request, response);
    }

    private boolean sameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null) return true;
        try { return request.getHeader("Host").equals(URI.create(origin).getAuthority()); }
        catch (IllegalArgumentException | NullPointerException e) { return false; }
    }

    private void deny(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
