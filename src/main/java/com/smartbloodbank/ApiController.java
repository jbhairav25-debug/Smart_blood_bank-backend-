package com.smartbloodbank;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;

@RestController
public class ApiController {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiController.class);
    private static final List<String> BLOOD_GROUPS = List.of("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-");
    private static final List<String> SELF_REGISTRATION_ROLES = List.of("donor", "hospital");
    private static final List<String> REQUEST_STATUSES = List.of("pending", "approved", "rejected");
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder(12);

    private final JdbcTemplate db;
    private final JwtService jwtService;

    public ApiController(JdbcTemplate db, JwtService jwtService) {
        this.db = db;
        this.jwtService = jwtService;
    }

    @GetMapping(value = "/", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> root() {
        return Map.of("service", "Smart Blood Bank API", "status", "running", "health", "/health");
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        try {
            db.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(Map.of("status", "UP"));
        } catch (DataAccessException exception) {
            LOGGER.error("Database health check failed.", exception);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "DOWN", "message", "Database connection failed."));
        }
    }

    @PostMapping("/api/auth/register")
    @Transactional
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, Object> body) {
        String name = requiredText(body, "name");
        String email = requiredText(body, "email");
        String password = requiredText(body, "password");
        String role = requiredText(body, "role");
        if (!SELF_REGISTRATION_ROLES.contains(role)) throw badRequest("role must be one of: donor, hospital");
        String bloodGroup = text(body.get("bloodGroup"));
        String location = text(body.get("location"));
        if ("donor".equals(role)) {
            if (bloodGroup == null || location == null || location.isBlank()) {
                throw badRequest("bloodGroup and location are required for donors.");
            }
            validateBloodGroup(bloodGroup);
        }
        if (db.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "An account with this email already exists.");
        }
        long userId = insert("INSERT INTO users (name, email, password, role) VALUES (?, ?, ?, ?)",
                name, email, PASSWORD_ENCODER.encode(password), role);
        if ("donor".equals(role)) {
            db.update("INSERT INTO donors (user_id, blood_group, location) VALUES (?, ?, ?)",
                    userId, bloodGroup, location);
        }
        return result(HttpStatus.CREATED, true, "Registration successful! You can now log in.");
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> body) {
        String email = requiredText(body, "email");
        String password = requiredText(body, "password");
        List<Map<String, Object>> users = db.queryForList("SELECT id, name, email, password, role FROM users WHERE email = ?", email);
        if (users.isEmpty() || !PASSWORD_ENCODER.matches(password, text(users.get(0).get("password")))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        }
        Map<String, Object> row = users.get(0);
        AppUser user = new AppUser(number(row.get("id")), text(row.get("email")),
                text(row.get("role")), text(row.get("name")));
        return result(HttpStatus.OK, Map.of(
                "success", true,
                "message", "Login successful!",
                "token", jwtService.createToken(user),
                "user", Map.of("id", user.id(), "name", user.name(), "email", user.email(), "role", user.role())));
    }

    @GetMapping("/api/auth/me")
    public Map<String, Object> getMe(Authentication authentication) {
        AppUser user = currentUser(authentication);
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT id, name, email, role, created_at FROM users WHERE id = ?", user.id());
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "User not found.");
        return Map.of("success", true, "user", rows.get(0));
    }

    @GetMapping("/api/donors")
    public Map<String, Object> getDonors(@RequestParam(required = false) String bloodGroup,
                                         @RequestParam(required = false) String location) {
        List<Map<String, Object>> donors;
        if (bloodGroup != null || location != null) {
            StringBuilder sql = new StringBuilder("""
                    SELECT d.id, u.name, u.email, d.blood_group, d.location, d.last_donation
                    FROM donors d JOIN users u ON u.id = d.user_id WHERE 1=1
                    """);
            List<Object> args = new ArrayList<>();
            if (bloodGroup != null && !bloodGroup.isBlank()) {
                sql.append(" AND d.blood_group = ?");
                args.add(bloodGroup);
            }
            if (location != null && !location.isBlank()) {
                sql.append(" AND d.location LIKE ?");
                args.add("%" + location + "%");
            }
            donors = db.queryForList(sql.toString(), args.toArray());
        } else {
            donors = db.queryForList("""
                    SELECT d.id, u.name, u.email, d.blood_group, d.location, d.last_donation, d.user_id
                    FROM donors d JOIN users u ON u.id = d.user_id ORDER BY d.id DESC
                    """);
        }
        return Map.of("success", true, "count", donors.size(), "donors", donors);
    }

    @GetMapping("/api/donors/me")
    @PreAuthorize("hasAuthority('ROLE_donor')")
    public Map<String, Object> myDonorProfile(Authentication authentication) {
        Map<String, Object> donor = donorByUser(currentUser(authentication).id());
        if (donor == null) throw new ApiException(HttpStatus.NOT_FOUND, "Donor profile not found.");
        return Map.of("success", true, "donor", donor);
    }

    @GetMapping("/api/donors/{id}")
    public Map<String, Object> getDonor(@PathVariable long id) {
        Map<String, Object> donor = donorById(id);
        if (donor == null) throw new ApiException(HttpStatus.NOT_FOUND, "Donor not found.");
        return Map.of("success", true, "donor", donor);
    }

    @PutMapping("/api/donors/{id}")
    public ResponseEntity<Map<String, Object>> updateDonor(@PathVariable long id, @RequestBody Map<String, Object> body,
                                                            Authentication authentication) {
        Map<String, Object> donor = donorById(id);
        if (donor == null) throw new ApiException(HttpStatus.NOT_FOUND, "Donor not found.");
        AppUser user = currentUser(authentication);
        if (!"admin".equals(user.role()) && number(donor.get("user_id")) != user.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You are not authorized to update this donor.");
        }
        String bloodGroup = Optional.ofNullable(text(body.get("bloodGroup"))).orElse(text(donor.get("blood_group")));
        String location = Optional.ofNullable(text(body.get("location"))).orElse(text(donor.get("location")));
        validateBloodGroup(bloodGroup);
        Object lastDonation = body.containsKey("lastDonation")
                ? sqlDate(body.get("lastDonation")) : donor.get("last_donation");
        int affected = db.update("UPDATE donors SET blood_group = ?, location = ?, last_donation = ? WHERE id = ?",
                bloodGroup, location, lastDonation, id);
        return result(HttpStatus.OK, Map.of("success", true, "message", "Donor updated successfully.", "affected", affected));
    }

    @DeleteMapping("/api/donors/{id}")
    @PreAuthorize("hasAuthority('ROLE_admin')")
    public Map<String, Object> deleteDonor(@PathVariable long id) {
        int affected = db.update("DELETE FROM donors WHERE id = ?", id);
        if (affected == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Donor not found.");
        return Map.of("success", true, "message", "Donor deleted successfully.");
    }

    @GetMapping("/api/stock")
    public Map<String, Object> getStock() {
        return Map.of("success", true, "stock", stockRows());
    }

    @GetMapping("/api/stock/{bloodGroup}")
    public Map<String, Object> getStock(@PathVariable String bloodGroup) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT * FROM blood_stock WHERE blood_group = ?", bloodGroup);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Blood group not found.");
        return Map.of("success", true, "stock", rows.get(0));
    }

    @PutMapping("/api/stock/{bloodGroup}")
    @PreAuthorize("hasAuthority('ROLE_admin')")
    public Map<String, Object> setStock(@PathVariable String bloodGroup, @RequestBody Map<String, Object> body) {
        validateBloodGroup(bloodGroup);
        long units = requiredNumber(body, "units");
        if (units < 0) throw badRequest("units must be a non-negative number.");
        int affected = db.update("UPDATE blood_stock SET units_available = ? WHERE blood_group = ?", units, bloodGroup);
        if (affected == 0 && scalar("SELECT COUNT(*) FROM blood_stock WHERE blood_group = ?", bloodGroup) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Blood group not found.");
        }
        return Map.of("success", true, "message", "Stock updated successfully.");
    }

    @PostMapping("/api/stock/add")
    @PreAuthorize("hasAuthority('ROLE_admin')")
    public Map<String, Object> addStock(@RequestBody Map<String, Object> body) {
        String bloodGroup = requiredText(body, "bloodGroup");
        validateBloodGroup(bloodGroup);
        long units = requiredNumber(body, "units");
        if (units <= 0) throw badRequest("bloodGroup and positive units are required.");
        int affected = db.update(
                "UPDATE blood_stock SET units_available = units_available + ? WHERE blood_group = ?", units, bloodGroup);
        if (affected == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Blood group not found.");
        return Map.of("success", true, "message", units + " unit(s) added to " + bloodGroup + " stock.");
    }

    @GetMapping("/api/requests")
    public Map<String, Object> getRequests(Authentication authentication) {
        AppUser user = currentUser(authentication);
        List<Map<String, Object>> requests = "admin".equals(user.role())
                ? requestsWithRequester()
                : db.queryForList("SELECT * FROM requests WHERE requester_id = ? ORDER BY created_at DESC", user.id());
        return Map.of("success", true, "count", requests.size(), "requests", requests);
    }

    @GetMapping("/api/requests/{id}")
    public Map<String, Object> getRequest(@PathVariable long id) {
        Map<String, Object> request = requestById(id);
        if (request == null) throw new ApiException(HttpStatus.NOT_FOUND, "Request not found.");
        return Map.of("success", true, "request", request);
    }

    @PostMapping("/api/requests")
    public ResponseEntity<Map<String, Object>> createRequest(@RequestBody Map<String, Object> body, Authentication authentication) {
        String bloodGroup = requiredText(body, "bloodGroup");
        validateBloodGroup(bloodGroup);
        long units = requiredNumber(body, "units");
        if (units <= 0) throw badRequest("bloodGroup and a positive units count are required.");
        String urgency = Optional.ofNullable(text(body.get("urgency"))).orElse("normal");
        if (!List.of("normal", "urgent", "emergency").contains(urgency)) {
            throw badRequest("urgency must be one of: normal, urgent, emergency");
        }
        long available = db.queryForObject(
                "SELECT units_available FROM blood_stock WHERE blood_group = ?", Long.class, bloodGroup);
        long requestId = insert("""
                INSERT INTO requests (requester_id, blood_group, units, urgency, notes)
                VALUES (?, ?, ?, ?, ?)
                """, currentUser(authentication).id(), bloodGroup, units, urgency, text(body.get("notes")));
        return result(HttpStatus.CREATED, Map.of("success", true,
                "message", "Blood request submitted successfully.",
                "requestId", requestId, "stockAvailable", available));
    }

    @PatchMapping("/api/requests/{id}/status")
    @PreAuthorize("hasAuthority('ROLE_admin')")
    @Transactional
    public Map<String, Object> updateRequestStatus(@PathVariable long id, @RequestBody Map<String, Object> body) {
        String status = requiredText(body, "status");
        if (!REQUEST_STATUSES.contains(status)) throw badRequest("status must be one of: pending, approved, rejected");
        Map<String, Object> request = requestById(id);
        if (request == null) throw new ApiException(HttpStatus.NOT_FOUND, "Request not found.");
        if ("approved".equals(status) && !"approved".equals(request.get("status"))) {
            int updated = db.update("""
                    UPDATE blood_stock SET units_available = units_available - ?
                    WHERE blood_group = ? AND units_available >= ?
                    """, number(request.get("units")), request.get("blood_group"), number(request.get("units")));
            if (updated == 0) {
                long available = db.queryForObject("SELECT units_available FROM blood_stock WHERE blood_group = ?",
                        Long.class, request.get("blood_group"));
                throw new ApiException(HttpStatus.BAD_REQUEST, "Insufficient stock for "
                        + request.get("blood_group") + ". Available: " + available);
            }
        } else if ("approved".equals(request.get("status")) && !"approved".equals(status)) {
            db.update("UPDATE blood_stock SET units_available = units_available + ? WHERE blood_group = ?",
                    number(request.get("units")), request.get("blood_group"));
        }
        db.update("UPDATE requests SET status = ? WHERE id = ?", status, id);
        return Map.of("success", true, "message", "Request " + status + " successfully.");
    }

    @GetMapping("/api/donations")
    @PreAuthorize("hasAuthority('ROLE_admin')")
    public Map<String, Object> getDonations() {
        List<Map<String, Object>> donations = db.queryForList("""
                SELECT dn.*, u.name AS donor_name, d.blood_group, d.location
                FROM donations dn JOIN donors d ON d.id = dn.donor_id
                JOIN users u ON u.id = d.user_id ORDER BY dn.date DESC
                """);
        return Map.of("success", true, "count", donations.size(), "donations", donations);
    }

    @PostMapping("/api/donations")
    @PreAuthorize("hasAuthority('ROLE_admin')")
    @Transactional
    public ResponseEntity<Map<String, Object>> recordDonation(@RequestBody Map<String, Object> body) {
        long donorId = requiredNumber(body, "donorId");
        long units = requiredNumber(body, "units");
        if (units <= 0) throw badRequest("donorId and positive units are required.");
        Map<String, Object> donor = donorById(donorId);
        if (donor == null) throw new ApiException(HttpStatus.NOT_FOUND, "Donor not found.");
        long donationId = insert("INSERT INTO donations (donor_id, units, notes) VALUES (?, ?, ?)",
                donorId, units, text(body.get("notes")));
        db.update("UPDATE blood_stock SET units_available = units_available + ? WHERE blood_group = ?",
                units, donor.get("blood_group"));
        db.update("UPDATE donors SET last_donation = ? WHERE id = ?", Date.valueOf(java.time.LocalDate.now()), donorId);
        return result(HttpStatus.CREATED, Map.of("success", true,
                "message", "Donation of " + units + " unit(s) of " + donor.get("blood_group") + " recorded.",
                "donationId", donationId));
    }

    @GetMapping("/api/dashboard/admin")
    @PreAuthorize("hasAuthority('ROLE_admin')")
    public Map<String, Object> adminDashboard() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalUsers", scalar("SELECT COUNT(*) FROM users"));
        stats.put("totalDonors", scalar("SELECT COUNT(*) FROM donors"));
        stats.put("totalUnitsInStock", scalar("SELECT COALESCE(SUM(units_available), 0) FROM blood_stock"));
        stats.put("totalDonations", scalar("SELECT COUNT(*) FROM donations"));
        stats.put("requests", Map.of("pending", countRequests("pending"),
                "approved", countRequests("approved"), "rejected", countRequests("rejected")));
        return Map.of("success", true, "stats", stats, "stock", stockRows(),
                "recentRequests", db.queryForList("""
                        SELECT r.*, u.name AS requester_name, u.role FROM requests r
                        JOIN users u ON u.id = r.requester_id ORDER BY r.created_at DESC LIMIT 5
                        """));
    }

    @GetMapping("/api/dashboard/donor")
    @PreAuthorize("hasAuthority('ROLE_donor')")
    public Map<String, Object> donorDashboard(Authentication authentication) {
        Map<String, Object> donor = donorByUser(currentUser(authentication).id());
        if (donor == null) throw new ApiException(HttpStatus.NOT_FOUND, "Donor profile not found.");
        List<Map<String, Object>> donations = db.queryForList(
                "SELECT * FROM donations WHERE donor_id = ? ORDER BY date DESC", donor.get("id"));
        long units = donations.stream().mapToLong(row -> number(row.get("units"))).sum();
        return Map.of("success", true, "donor", donor,
                "stats", Map.of("totalDonations", donations.size(), "totalUnitsDonated", units),
                "donations", donations, "stock", stockRows());
    }

    @GetMapping("/api/dashboard/hospital")
    @PreAuthorize("hasAuthority('ROLE_hospital')")
    public Map<String, Object> hospitalDashboard(Authentication authentication) {
        List<Map<String, Object>> requests = db.queryForList(
                "SELECT * FROM requests WHERE requester_id = ? ORDER BY created_at DESC", currentUser(authentication).id());
        long pending = requests.stream().filter(row -> "pending".equals(row.get("status"))).count();
        long approved = requests.stream().filter(row -> "approved".equals(row.get("status"))).count();
        return Map.of("success", true,
                "stats", Map.of("totalRequests", requests.size(), "pending", pending, "approved", approved),
                "requests", requests, "stock", stockRows());
    }

    private List<Map<String, Object>> stockRows() {
        return db.queryForList("SELECT * FROM blood_stock ORDER BY blood_group");
    }

    private List<Map<String, Object>> requestsWithRequester() {
        return db.queryForList("""
                SELECT r.*, u.name AS requester_name, u.email AS requester_email, u.role
                FROM requests r JOIN users u ON u.id = r.requester_id ORDER BY r.created_at DESC
                """);
    }

    private Map<String, Object> requestById(long id) {
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT r.*, u.name AS requester_name, u.email AS requester_email
                FROM requests r JOIN users u ON u.id = r.requester_id WHERE r.id = ?
                """, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> donorById(long id) {
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT d.*, u.name, u.email FROM donors d JOIN users u ON u.id = d.user_id
                WHERE d.id = ?
                """, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> donorByUser(long userId) {
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT d.*, u.name, u.email FROM donors d JOIN users u ON u.id = d.user_id
                WHERE d.user_id = ?
                """, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long countRequests(String status) {
        return scalar("SELECT COUNT(*) FROM requests WHERE status = ?", status);
    }

    private long scalar(String sql, Object... args) {
        Long value = db.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private long insert(String sql, Object... args) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        db.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("Database did not return a generated ID.");
        return key.longValue();
    }

    private static AppUser currentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AppUser user)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Access denied. No token provided.");
        }
        return user;
    }

    private static String requiredText(Map<String, Object> body, String field) {
        String value = text(body.get(field));
        if (value == null || value.isBlank()) throw badRequest(field + " is required.");
        return value;
    }

    private static long requiredNumber(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (!(value instanceof Number number)) throw badRequest(field + " must be a number.");
        return number.longValue();
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(value.toString());
    }

    private static Object sqlDate(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        try {
            return Date.valueOf(value.toString());
        } catch (IllegalArgumentException ex) {
            throw badRequest("lastDonation must be a date in YYYY-MM-DD format.");
        }
    }

    private static void validateBloodGroup(String bloodGroup) {
        if (!BLOOD_GROUPS.contains(bloodGroup)) {
            throw badRequest("bloodGroup must be one of: A+, A-, B+, B-, AB+, AB-, O+, O-");
        }
    }

    private static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseEntity<Map<String, Object>> result(HttpStatus status, boolean success, String message) {
        return result(status, Map.of("success", success, "message", message));
    }

    private static ResponseEntity<Map<String, Object>> result(HttpStatus status, Map<String, Object> body) {
        return ResponseEntity.status(status).body(body);
    }
}

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> handleApiException(ApiException exception) {
        return ResponseEntity.status(exception.status)
                .body(Map.of("success", false, "message", exception.getMessage()));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<Map<String, Object>> handleForbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("success", false, "message", "Access denied for this role."));
    }
}

class ApiException extends RuntimeException {
    final HttpStatus status;

    ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
