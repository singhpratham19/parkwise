package com.parkwise;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ParkingControllerTest {
    SingleConnectionDataSource source;
    JdbcTemplate db;
    ParkingController controller;
    LocalDateTime start;
    String time;

    @BeforeEach void setup() {
        source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        db = new JdbcTemplate(source);
        controller = new ParkingController(db);
        controller.initialize();
        start = LocalDateTime.now().plusDays(2).withHour(17).withMinute(0).withSecond(0).withNano(0);
        time = start.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }
    @AfterEach void close() { source.destroy(); }

    @Test void bookingPricingConflictSearchAndCancellation() {
        var request = new ParkingController.BookingRequest("mh12ab1234", "CAR", time, 2, 1);
        Map<String, Object> booking = controller.book(request);
        int id = ((Number) booking.get("id")).intValue();
        assertEquals(80.0, ((Number) booking.get("price")).doubleValue());
        assertEquals("MH12AB1234", booking.get("car_number"));
        assertEquals(1, controller.bookings("mh12ab1234", "registration").size());
        assertEquals(1, controller.bookings(String.valueOf(id), "id").size());
        assertEquals(1, controller.bookings("1", "slot").size());
        assertThrows(ResponseStatusException.class, () -> controller.book(request));
        assertEquals("CANCELLED", controller.action(id, "cancel").get("status"));
        assertNotNull(controller.book(request));
    }

    @Test void extensionAndLifecycle() {
        var request = new ParkingController.BookingRequest("KA1234", "TWO_WHEELER", time, 2, 3);
        Map<String, Object> booking = controller.book(request);
        int id = ((Number) booking.get("id")).intValue();
        Map<String, Object> extended = controller.extend(id, new ParkingController.ExtensionRequest(2));
        assertEquals(4, ((Number) extended.get("hours")).intValue());
        assertEquals(81.0, ((Number) extended.get("price")).doubleValue(), 0.001);
        assertThrows(ResponseStatusException.class, () -> controller.extend(id, new ParkingController.ExtensionRequest(2)));
        String before = LocalDateTime.now().minusMinutes(30).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String after = LocalDateTime.now().plusMinutes(30).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        db.update("UPDATE bookings SET start_time = ?, end_time = ? WHERE id = ?", before, after, id);
        assertEquals("CHECKED_IN", controller.action(id, "check-in").get("status"));
        assertEquals("CHECKED_OUT", controller.action(id, "check-out").get("status"));
        assertEquals(1, ((Number) controller.statistics().get("completed")).intValue());
    }
}
