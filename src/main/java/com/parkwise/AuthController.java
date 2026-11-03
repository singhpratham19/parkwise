package com.parkwise;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/* Sign-in and user accounts. The first visitor creates the administrator; after that only an administrator can add
   users. Passwords are stored as salted PBKDF2 hashes, never as text. */
@RestController
@RequestMapping("/api")
public class AuthController {
    private static final int ITERATIONS = 210_000, MAX_FAILURES = 5, LOCK_SECONDS = 60;
    private static final String PUBLIC = "id, username, full_name, role, created_at";
    private final JdbcTemplate db;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, long[]> failures = new ConcurrentHashMap<>(); // username -> [count, locked until]
    private String decoy; // compared against for unknown usernames, so they take as long as real ones

    public AuthController(JdbcTemplate db) { this.db = db; }

    @PostConstruct
    void initialize() {
        db.execute("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL UNIQUE COLLATE NOCASE, full_name TEXT NOT NULL, password_hash TEXT NOT NULL, role TEXT NOT NULL, created_at TEXT NOT NULL)");
        decoy = hash("not a real password");
    }

    public record LoginRequest(String username, String password) { }
    public record UserRequest(String username, String fullName, String password, String role, String currentPassword) { }

    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException forbidden(String message) { return new ResponseStatusException(HttpStatus.FORBIDDEN, message); }

    private String hash(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }
    private byte[] derive(String password, byte[] salt, int iterations) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, 256)).getEncoded();
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    private boolean matches(String password, String stored) {
        String[] parts = stored.split("\\$");
        byte[] expected = Base64.getDecoder().decode(parts[3]);
        return MessageDigest.isEqual(expected, derive(password, Base64.getDecoder().decode(parts[2]), Integer.parseInt(parts[1])));
    }

    private String username(String value) {
        String text = value == null ? "" : value.trim();
        if (!text.matches("[A-Za-z0-9._@-]{3,30}")) throw bad("Usernames need 3 to 30 letters, numbers, dots, hyphens, underscores or @.");
        return text;
    }
    private String fullName(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty() || text.length() > 60) throw bad("Enter a full name of up to 60 characters.");
        return text;
    }
    private String password(String value) {
        if (value == null || value.length() < 8 || value.length() > 128) throw bad("Passwords need 8 to 128 characters.");
        return value;
    }
    private String role(String value) {
        if (!"ADMIN".equals(value) && !"STAFF".equals(value)) throw bad("Choose a valid role.");
        return value;
    }
    private void unique(String username, int excludeId) {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM users WHERE username = ? AND id <> ?", Integer.class, username, excludeId);
        if (count != null && count > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "That username is already taken.");
    }
    private int admins() {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM users WHERE role = 'ADMIN'", Integer.class);
        return count == null ? 0 : count;
    }
    private String now() { return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")); }
    private Map<String, Object> publicUser(int id) {
        List<Map<String, Object>> rows = db.queryForList("SELECT " + PUBLIC + " FROM users WHERE id = ?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found.");
        return rows.get(0);
    }
    private int idOf(Map<String, Object> user) { return ((Number) user.get("id")).intValue(); }
    private boolean isAdmin(Map<String, Object> user) { return "ADMIN".equals(user.get("role")); }
    @SuppressWarnings("unchecked")
    private Map<String, Object> signedIn(HttpServletRequest request) { return (Map<String, Object>) request.getAttribute(AuthFilter.USER); }

    private void startSession(HttpServletRequest request, int id) {
        HttpSession old = request.getSession(false);
        if (old != null) old.invalidate(); // a fresh session id at every sign-in
        HttpSession session = request.getSession(true);
        session.setAttribute("userId", id);
        session.setAttribute("stamp", db.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, id));
    }

    /* The session's user, re-read each time: deleting a user, or changing their password, ends their sessions at once. */
    static Map<String, Object> sessionUser(JdbcTemplate db, HttpSession session) {
        if (session == null || session.getAttribute("userId") == null) return null;
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM users WHERE id = ?", session.getAttribute("userId"));
        if (rows.isEmpty() || !rows.get(0).get("password_hash").equals(session.getAttribute("stamp"))) return null;
        Map<String, Object> user = new LinkedHashMap<>(rows.get(0));
        user.remove("password_hash");
        return user;
    }

    @GetMapping("/auth/status")
    public Map<String, Object> status(HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        Integer users = db.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        result.put("setupNeeded", users == null || users == 0);
        result.put("user", sessionUser(db, request.getSession(false)));
        return result;
    }

    @PostMapping("/auth/setup")
    public synchronized Map<String, Object> setup(@RequestBody UserRequest body, HttpServletRequest request) {
        Integer users = db.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        if (users != null && users > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "The administrator account already exists.");
        db.update("INSERT INTO users (username, full_name, password_hash, role, created_at) VALUES (?, ?, ?, 'ADMIN', ?)", username(body.username()), fullName(body.fullName()), hash(password(body.password())), now());
        Number id = db.queryForObject("SELECT last_insert_rowid()", Number.class);
        startSession(request, id.intValue());
        return publicUser(id.intValue());
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        String name = body.username() == null ? "" : body.username().trim().toLowerCase(Locale.ROOT);
        long[] state = failures.get(name);
        if (state != null && state[1] > System.currentTimeMillis())
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed attempts. Try again in a minute.");
        List<Map<String, Object>> rows = db.queryForList("SELECT id, password_hash FROM users WHERE username = ?", name);
        String password = body.password() == null ? "" : body.password();
        boolean found = !rows.isEmpty();
        boolean right = matches(password, found ? (String) rows.get(0).get("password_hash") : decoy) && found;
        if (!right) {
            long[] next = failures.merge(name, new long[] {1, 0}, (a, b) -> new long[] {a[0] + 1, 0});
            if (next[0] >= MAX_FAILURES) { next[0] = 0; next[1] = System.currentTimeMillis() + LOCK_SECONDS * 1000L; }
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect username or password.");
        }
        failures.remove(name);
        int id = idOf(rows.get(0));
        startSession(request, id);
        return publicUser(id);
    }

    @PostMapping("/auth/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        return Map.of("signedOut", true);
    }

    /* Administrators see everyone; staff see only their own account. */
    @GetMapping("/users")
    public List<Map<String, Object>> users(HttpServletRequest request) {
        Map<String, Object> me = signedIn(request);
        return isAdmin(me) ? db.queryForList("SELECT " + PUBLIC + " FROM users ORDER BY id") : List.of(publicUser(idOf(me)));
    }

    @GetMapping("/users/{id}")
    public Map<String, Object> user(@PathVariable int id, HttpServletRequest request) {
        Map<String, Object> me = signedIn(request);
        if (!isAdmin(me) && idOf(me) != id) throw forbidden("You can only view your own account.");
        return publicUser(id);
    }

    @PostMapping("/users")
    public synchronized Map<String, Object> create(@RequestBody UserRequest body, HttpServletRequest request) {
        if (!isAdmin(signedIn(request))) throw forbidden("Only an administrator can add users.");
        String username = username(body.username());
        unique(username, 0);
        db.update("INSERT INTO users (username, full_name, password_hash, role, created_at) VALUES (?, ?, ?, ?, ?)", username, fullName(body.fullName()), hash(password(body.password())), role(body.role()), now());
        return publicUser(db.queryForObject("SELECT last_insert_rowid()", Number.class).intValue());
    }

    @PutMapping("/users/{id}")
    public synchronized Map<String, Object> update(@PathVariable int id, @RequestBody UserRequest body, HttpServletRequest request) {
        Map<String, Object> me = signedIn(request);
        boolean self = idOf(me) == id;
        if (!isAdmin(me) && !self) throw forbidden("You can only change your own account.");
        Map<String, Object> target = publicUser(id);
        String username = (String) target.get("username"), role = (String) target.get("role");
        if (isAdmin(me)) {
            username = username(body.username());
            unique(username, id);
            role = role(body.role());
            if ("ADMIN".equals(target.get("role")) && "STAFF".equals(role) && admins() == 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "There must be at least one administrator.");
        }
        String name = fullName(body.fullName());
        boolean newPassword = body.password() != null && !body.password().isEmpty();
        if (newPassword) {
            password(body.password());
            // someone left signed in on a shared computer cannot change the password without knowing it
            if (self) {
                String stored = db.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, id);
                if (body.currentPassword() == null || !matches(body.currentPassword(), stored)) throw forbidden("Your current password is incorrect.");
            }
            db.update("UPDATE users SET password_hash = ? WHERE id = ?", hash(body.password()), id);
        }
        db.update("UPDATE users SET username = ?, full_name = ?, role = ? WHERE id = ?", username, name, role, id);
        if (self && newPassword) startSession(request, id);
        return publicUser(id);
    }

    @DeleteMapping("/users/{id}")
    public synchronized Map<String, Object> delete(@PathVariable int id, HttpServletRequest request) {
        Map<String, Object> me = signedIn(request);
        if (!isAdmin(me)) throw forbidden("Only an administrator can delete users.");
        if (idOf(me) == id) throw new ResponseStatusException(HttpStatus.CONFLICT, "You cannot delete your own account.");
        publicUser(id);
        db.update("DELETE FROM users WHERE id = ?", id);
        return Map.of("deleted", id);
    }
}
