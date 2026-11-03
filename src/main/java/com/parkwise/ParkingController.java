package com.parkwise;

import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ParkingController {
    private final JdbcTemplate db;
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT);
    private static final int MAX_DAYS_AHEAD = 90;

    public ParkingController(JdbcTemplate db) { this.db = db; }

    @PostConstruct
    void initialize() {
        db.execute("CREATE TABLE IF NOT EXISTS bookings (id INTEGER PRIMARY KEY AUTOINCREMENT, car_number TEXT NOT NULL, slot INTEGER NOT NULL, start_time TEXT NOT NULL, end_time TEXT NOT NULL, hours INTEGER NOT NULL, price REAL NOT NULL, status TEXT NOT NULL)");
        addColumn("vehicle_type", "TEXT NOT NULL DEFAULT 'CAR'");
        addColumn("checked_in_at", "TEXT");
        addColumn("checked_out_at", "TEXT");
    }

    private void addColumn(String column, String type) {
        boolean exists = db.queryForList("PRAGMA table_info(bookings)").stream().anyMatch(row -> column.equals(row.get("name")));
        if (!exists) db.execute("ALTER TABLE bookings ADD COLUMN " + column + " " + type);
    }

    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private String now() { return LocalDateTime.now().format(FORMAT); }
    private LocalDateTime parseTime(String text) {
        try { return LocalDateTime.parse(text.replace('T', ' '), FORMAT); }
        catch (DateTimeParseException | NullPointerException e) { throw bad("Enter a valid start date and time."); }
    }
    private LocalDateTime futureHour(String text) {
        LocalDateTime time = parseTime(text);
        if (time.getMinute() != 0 || !time.isAfter(LocalDateTime.now())) throw bad("Choose a future time on a whole hour.");
        if (time.isAfter(LocalDateTime.now().plusDays(MAX_DAYS_AHEAD))) throw bad("Bookings can be made up to " + MAX_DAYS_AHEAD + " days ahead.");
        return time;
    }
    private String registration(String value) {
        String text = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (text.isEmpty() || text.length() > 30) throw bad("Enter a registration number of up to 30 characters.");
        if (!text.matches("[A-Z0-9][A-Z0-9 -]*")) throw bad("Use only letters, numbers, spaces and hyphens in the registration number.");
        return text;
    }
    private String compact(String registration) { return registration.replace(" ", "").replace("-", ""); }
    // the same vehicle cannot be in two places at once, however its registration was spaced when it was typed
    private boolean vehicleFree(String registration, LocalDateTime start, LocalDateTime end, int excludeId) {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM bookings WHERE REPLACE(REPLACE(car_number, ' ', ''), '-', '') = ? AND id <> ? AND status IN ('BOOKED','CHECKED_IN') AND start_time < ? AND end_time > ?", Integer.class,
                compact(registration), excludeId, end.format(FORMAT), start.format(FORMAT));
        return count != null && count == 0;
    }
    private String type(String value) {
        if (!"CAR".equals(value) && !"TWO_WHEELER".equals(value)) throw bad("Choose a valid vehicle type.");
        return value;
    }
    private void hours(int value) { if (value < 1 || value > 5) throw bad("Duration must be between 1 and 5 hours."); }
    private double price(LocalDateTime start, int hours, String type) {
        int total = 0;
        for (int i = 0; i < hours; i++) {
            int hour = start.plusHours(i).getHour();
            boolean peak = hour >= 9 && hour < 18;
            total += type.equals("CAR") ? (peak ? 50 : 30) : (peak ? 30 : 20);
        }
        return hours >= 4 ? total * 0.9 : total;
    }
    private boolean available(int slot, LocalDateTime start, LocalDateTime end, int excludeId) {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM bookings WHERE slot = ? AND id <> ? AND status IN ('BOOKED','CHECKED_IN') AND ((start_time < ? AND end_time > ?) OR (status = 'CHECKED_IN' AND end_time <= ?))", Integer.class,
                slot, excludeId, end.format(FORMAT), start.format(FORMAT), now());
        return count != null && count == 0;
    }
    private Map<String, Object> find(int id) {
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM bookings WHERE id = ?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found.");
        return rows.get(0);
    }
    private int integer(Object value) { return ((Number) value).intValue(); }
    private String string(Object value) { return value == null ? "" : value.toString(); }

    public record BookingRequest(String registration, String vehicleType, String startTime, int hours, int slot) { }
    public record ExtensionRequest(int extraHours) { }

    @GetMapping("/availability")
    public synchronized Map<String, Object> availability(@RequestParam String startTime, @RequestParam int hours, @RequestParam String vehicleType) {
        LocalDateTime start = futureHour(startTime); hours(hours); type(vehicleType);
        List<Map<String, Object>> slots = new ArrayList<>();
        for (int i = 1; i <= 5; i++) slots.add(Map.of("number", i, "available", available(i, start, start.plusHours(hours), 0)));
        return Map.of("slots", slots, "price", price(start, hours, vehicleType), "endTime", start.plusHours(hours).format(FORMAT));
    }

    @PostMapping("/bookings")
    public synchronized Map<String, Object> book(@RequestBody BookingRequest request) {
        String registration = registration(request.registration());
        String vehicle = type(request.vehicleType());
        LocalDateTime start = futureHour(request.startTime()); hours(request.hours());
        int slot = request.slot();
        if (slot < 1 || slot > 5) throw bad("Choose a slot between 1 and 5.");
        LocalDateTime end = start.plusHours(request.hours());
        if (!available(slot, start, end, 0)) throw new ResponseStatusException(HttpStatus.CONFLICT, "This slot is no longer available.");
        if (!vehicleFree(registration, start, end, 0)) throw new ResponseStatusException(HttpStatus.CONFLICT, "This vehicle already has a booking at that time.");
        db.update("INSERT INTO bookings (car_number, slot, start_time, end_time, hours, price, status, vehicle_type) VALUES (?, ?, ?, ?, ?, ?, 'BOOKED', ?)",
                registration, slot, start.format(FORMAT), end.format(FORMAT), request.hours(), price(start, request.hours(), vehicle), vehicle);
        Number id = db.queryForObject("SELECT last_insert_rowid()", Number.class);
        return find(id.intValue());
    }

    @GetMapping("/bookings")
    public List<Map<String, Object>> bookings(@RequestParam(defaultValue = "") String search, @RequestParam(defaultValue = "registration") String by) {
        search = search.trim();
        if (search.isEmpty()) return db.queryForList("SELECT * FROM bookings ORDER BY id DESC");
        if (by.equals("registration")) {
            String pattern = "%" + search.toUpperCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            return db.queryForList("SELECT * FROM bookings WHERE UPPER(car_number) LIKE ? ESCAPE '\\' ORDER BY id DESC", pattern);
        }
        if (by.equals("id") || by.equals("slot")) {
            try {
                int value = Integer.parseInt(search);
                return db.queryForList("SELECT * FROM bookings WHERE " + (by.equals("id") ? "id" : "slot") + " = ? ORDER BY id DESC", value);
            } catch (NumberFormatException e) { throw bad("Enter a whole number for the selected search."); }
        }
        throw bad("Invalid search type.");
    }

    @GetMapping("/bookings/{id}")
    public Map<String, Object> booking(@PathVariable int id) { return find(id); }

    @PostMapping("/bookings/{id}/{action}")
    public synchronized Map<String, Object> action(@PathVariable int id, @PathVariable String action) {
        int changed;
        String now = now();
        switch (action) {
            case "cancel" -> changed = db.update("UPDATE bookings SET status = 'CANCELLED' WHERE id = ? AND status = 'BOOKED' AND start_time > ?", id, now);
            case "check-in" -> {
                Map<String, Object> booking = find(id);
                Integer occupied = db.queryForObject("SELECT COUNT(*) FROM bookings WHERE slot = ? AND status = 'CHECKED_IN' AND id <> ?", Integer.class, booking.get("slot"), id);
                if (occupied != null && occupied > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Another vehicle is still checked in to this slot.");
                changed = db.update("UPDATE bookings SET status = 'CHECKED_IN', checked_in_at = ? WHERE id = ? AND status = 'BOOKED'", now, id);
            }
            case "check-out" -> changed = db.update("UPDATE bookings SET status = 'CHECKED_OUT', checked_out_at = ? WHERE id = ? AND status = 'CHECKED_IN'", now, id);
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown action.");
        }
        if (changed == 0) { find(id); throw new ResponseStatusException(HttpStatus.CONFLICT, "This action is not available for the booking's current status or time."); }
        return find(id);
    }

    @PostMapping("/bookings/{id}/extend")
    public synchronized Map<String, Object> extend(@PathVariable int id, @RequestBody ExtensionRequest request) {
        Map<String, Object> b = find(id);
        String status = string(b.get("status"));
        if (!status.equals("BOOKED") && !status.equals("CHECKED_IN")) throw bad("Only active bookings can be extended.");
        LocalDateTime start = parseTime(string(b.get("start_time")));
        LocalDateTime oldEnd = parseTime(string(b.get("end_time")));
        if (!oldEnd.isAfter(LocalDateTime.now())) throw bad("The booking has already ended.");
        // the extra hours are checked on their own first: adding a huge number to the total would wrap round to a negative one
        if (request.extraHours() < 1 || request.extraHours() > 4 || integer(b.get("hours")) + request.extraHours() > 5) throw bad("The total duration must not exceed 5 hours.");
        int totalHours = integer(b.get("hours")) + request.extraHours();
        LocalDateTime newEnd = oldEnd.plusHours(request.extraHours());
        if (!available(integer(b.get("slot")), oldEnd, newEnd, id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "The slot is unavailable for the extension.");
        if (!vehicleFree(string(b.get("car_number")), oldEnd, newEnd, id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "This vehicle already has a booking at that time.");
        db.update("UPDATE bookings SET end_time = ?, hours = ?, price = ? WHERE id = ?", newEnd.format(FORMAT), totalHours, price(start, totalHours, string(b.get("vehicle_type"))), id);
        return find(id);
    }

    @GetMapping("/statistics")
    public Map<String, Object> statistics() {
        return db.queryForMap("SELECT COUNT(*) AS total, SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END) AS cancelled, SUM(CASE WHEN status = 'CHECKED_IN' THEN 1 ELSE 0 END) AS checked_in, SUM(CASE WHEN status = 'CHECKED_OUT' THEN 1 ELSE 0 END) AS completed, COALESCE(SUM(CASE WHEN status = 'CHECKED_OUT' THEN price ELSE 0 END), 0) AS completed_charges, COALESCE(SUM(CASE WHEN status IN ('BOOKED','CHECKED_IN') THEN price ELSE 0 END), 0) AS outstanding_charges FROM bookings");
    }
}
