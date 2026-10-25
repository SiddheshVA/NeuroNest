package com.neuronest.backend.securitysuite;

import com.neuronest.backend.admin.AdminBookingQueries;
import com.neuronest.backend.admin.AdminBookingService;
import com.neuronest.backend.admin.AdminConfig;
import com.neuronest.backend.admin.AdminJwtService;
import com.neuronest.backend.admin.AdminLoginRateLimiter;
import com.neuronest.backend.admin.AdminProfessionalService;
import com.neuronest.backend.admin.AdminSuspensionService;
import com.neuronest.backend.admin.AdminVerificationService;
import com.neuronest.backend.assessment.AssessmentService;
import com.neuronest.backend.auth.ClerkTokenVerifier;
import com.neuronest.backend.auth.ClerkUserService;
import com.neuronest.backend.auth.JwtService;
import com.neuronest.backend.auth.OtpService;
import com.neuronest.backend.auth.User;
import com.neuronest.backend.auth.UserRepository;
import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingPaymentService;
import com.neuronest.backend.booking.CommissionSplit;
import com.neuronest.backend.booking.PaymentAmounts;
import com.neuronest.backend.booking.BookingRepository;
import com.neuronest.backend.booking.BookingService;
import com.neuronest.backend.booking.BookingStatus;
import com.neuronest.backend.booking.SharedAssessment;
import com.neuronest.backend.booking.SlotService;
import com.neuronest.backend.chat.ChatService;
import com.neuronest.backend.config.SecurityConfig;
import com.neuronest.backend.memory.MemoryRecallService;
import com.neuronest.backend.memory.MemoryRepository;
import com.neuronest.backend.payment.PaymentReportQueries;
import com.neuronest.backend.payment.PaymentRepository;
import com.neuronest.backend.payment.RazorpayGateway;
import com.neuronest.backend.payment.RazorpayWebhookVerifier;
import com.neuronest.backend.booking.RazorpayWebhookService;
import com.neuronest.backend.pro.CloudinaryClient;
import com.neuronest.backend.pro.ProBlockedSlotService;
import com.neuronest.backend.pro.ProPhotoService;
import com.neuronest.backend.pro.ClerkBackendClient;
import com.neuronest.backend.pro.ProBookingService;
import com.neuronest.backend.pro.ProDocumentService;
import com.neuronest.backend.pro.ProEarningsService;
import com.neuronest.backend.review.ProfessionalReviewRepository;
import com.neuronest.backend.review.ReviewService;
import com.neuronest.backend.pro.ProDocumentStorage;
import com.neuronest.backend.pro.ProHomeService;
import com.neuronest.backend.pro.ProProfileService;
import com.neuronest.backend.pro.ProRegistrationRateLimiter;
import com.neuronest.backend.professional.*;
import com.neuronest.backend.session.SessionAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The shared setup of the security suite: every controller, the production security chains
 * (SecurityConfig) and filters, the real services for bookings, professionals, documents and the admin
 * area, real JPA repositories on an embedded PostgreSQL (no Docker), production Clerk verification
 * against a local JWKS endpoint, and the production admin token service with a test secret. Only the
 * parts unrelated to these checks (chat, assessments, memory, mail, Clerk's backend API) are mocked.
 * Every test class extends this, so they share one application context. Each test starts with empty
 * tables.
 */
@WebMvcTest
@ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
        DataJpaRepositoriesAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
        TransactionAutoConfiguration.class})
@Import({SecurityConfig.class, AdminConfig.class, AdminJwtService.class, AdminLoginRateLimiter.class,
        ClerkTokenVerifier.class, ClerkUserService.class, JwtService.class,
        AvailabilityService.class, SlotService.class, PublicProfessionalService.class, BookingService.class,
        BookingPaymentService.class, PaymentAmounts.class, CommissionSplit.class, PaymentReportQueries.class,
        ProEarningsService.class,
        ProBookingService.class, ProHomeService.class, ProProfileService.class, ProDocumentService.class,
        ProDocumentStorage.class, ProRegistrationRateLimiter.class,
        AdminProfessionalService.class, AdminVerificationService.class, AdminBookingService.class,
        AdminBookingQueries.class, AdminSuspensionService.class, ReviewService.class,
        ProPhotoService.class, CloudinaryClient.class, RazorpayWebhookService.class, RazorpayWebhookVerifier.class,
        ProBlockedSlotService.class,
        SecuritySuiteBase.SuiteBeans.class})
abstract class SecuritySuiteBase {

    @DynamicPropertySource
    static void suiteProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SuiteDatabase::jdbcUrl);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("spring.jpa.show-sql", () -> "false");
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("admin.username", () -> SecurityTestTokens.ADMIN_USERNAME);
        registry.add("admin.password", () -> SecurityTestTokens.ADMIN_PASSWORD);
        registry.add("admin.jwt-secret", () -> SecurityTestTokens.ADMIN_SECRET);
        registry.add("app.jwt.secret", () -> "security-suite-user-cookie-secret-0123456789abcdef");
        registry.add("clerk.jwks-url", SecurityTestTokens::jwksUrl);
        registry.add("clerk.issuer", () -> SecurityTestTokens.CLERK_ISSUER);
        registry.add("clerk.secret-key", () -> "sk_test_security_suite");
        registry.add("app.storage-dir", () -> SuiteDatabase.storageDir().toString());
        registry.add("kb.enabled", () -> "false");
        registry.add("memory.enabled", () -> "false");
    }

    @TestConfiguration
    static class SuiteBeans {
        @Bean(name = "chatStreamExecutor")
        ThreadPoolTaskExecutor chatStreamExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.initialize();
            return executor;
        }
    }

    // Unrelated to these checks; mocked so their controllers can still be mapped and called.
    @MockitoBean
    ChatService chatService;
    @MockitoBean
    AssessmentService assessmentService;
    @MockitoBean
    MemoryRepository memoryRepository;
    @MockitoBean
    MemoryRecallService memoryRecallService;
    @MockitoBean
    OtpService otpService;
    @MockitoBean
    ClerkBackendClient clerkBackendClient;
    @MockitoBean
    SessionAccessService sessionAccessService;
    // Razorpay itself is never called. A fresh mock is not configured, so payments answer 503 unless a
    // test stubs isConfigured().
    @MockitoBean
    RazorpayGateway razorpay;

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ProfessionalRepository professionals;
    @Autowired
    ProfessionalAccountRepository accounts;
    @Autowired
    ProfessionalDocumentRepository documents;
    @Autowired
    ProfessionalDayOffRepository daysOff;
    @Autowired
    BookingRepository bookings;
    @Autowired
    UserRepository users;
    @Autowired
    PaymentRepository payments;
    @Autowired
    ProfessionalReviewRepository reviews;

    static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeEach
    void emptyTables() {
        List<String> tables = jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class);
        if (!tables.isEmpty()) jdbc.execute("TRUNCATE " + String.join(", ", tables) + " CASCADE");
    }

    static String bearer(String token) {
        return "Bearer " + token;
    }

    // ---- Test data ----

    /** All week, 09:00 to 17:00. */
    static final Map<String, List<Map<String, String>>> ALL_WEEK = Map.of(
            "MON", List.of(Map.of("start", "09:00", "end", "17:00")), "TUE", List.of(Map.of("start", "09:00", "end", "17:00")),
            "WED", List.of(Map.of("start", "09:00", "end", "17:00")), "THU", List.of(Map.of("start", "09:00", "end", "17:00")),
            "FRI", List.of(Map.of("start", "09:00", "end", "17:00")), "SAT", List.of(Map.of("start", "09:00", "end", "17:00")),
            "SUN", List.of(Map.of("start", "09:00", "end", "17:00")));

    /** A professional with every private field filled in, so tests can look for leaks. */
    Professional professional(String name, VerificationStatus status, boolean active, boolean sample) {
        Professional p = new Professional();
        p.setFullName(name);
        p.setCredentialType(CredentialType.PSYCHOLOGIST);
        p.setConsultationMode(ConsultationMode.BOTH);
        p.setContactEmail(slugOf(name) + ".private@example.com");
        p.setContactPhone("+919812345678");
        p.setCity("Pune");
        p.setState("Maharashtra");
        p.setLanguages(List.of("English"));
        p.setSpecialties(List.of("Anxiety"));
        p.setBio("A short public bio.");
        p.setRegistrationBody(RegistrationBody.RCI);
        p.setRegistrationNumber("REGNO-SECRET-4711");
        p.setRegistrationCouncil("COUNCIL-SECRET");
        p.setDateOfBirth(LocalDate.of(1980, 2, 29));
        p.setSocialLinks(Map.of("LINKEDIN", "https://www.linkedin.com/in/secret-profile"));
        p.setVerificationNotes("VERIFICATION-NOTE-SECRET");
        p.setVerifiedBy("ADMIN-SECRET-NAME");
        p.setVerifiedAt(Instant.now().minus(Duration.ofDays(10)));
        p.setVerificationStatus(status);
        p.setVerified(status == VerificationStatus.VERIFIED);
        p.setActive(active);
        p.setSampleData(sample);
        p.setSessionMinutes(60);
        p.setBufferMinutes(17);
        p.setWeeklyHours(ALL_WEEK);
        return professionals.saveAndFlush(p);
    }

    /** A verified, active, bookable listing. */
    Professional listed(String name) {
        return professional(name, VerificationStatus.VERIFIED, true, false);
    }

    ProfessionalAccount account(Professional p, String clerkUserId) {
        return accounts.saveAndFlush(new ProfessionalAccount(p.getId(), p.getContactEmail(), clerkUserId));
    }

    User user(String clerkUserId, String email) {
        User u = new User();
        u.setClerkUserId(clerkUserId);
        u.setEmail(email);
        u.setEmailVerified(true);
        return users.saveAndFlush(u);
    }

    /** A booking request with every private field filled in. */
    Booking booking(UUID userId, UUID professionalId, BookingStatus status, Instant scheduled, Instant expires) {
        Booking b = new Booking();
        b.setProfessionalId(professionalId);
        b.setUserId(userId);
        b.setSessionId(UUID.randomUUID());
        b.setContactName("Ravi Kumar");
        b.setContactEmail("person.private@example.com");
        b.setContactPhone("+919876543210");
        b.setPreferredMode("ONLINE");
        b.setPreferredMethod("VIDEO");
        b.setPreferredDatetime(scheduled);
        b.setNote("REQUEST-NOTE-SECRET");
        b.setProfessionalNote(status == BookingStatus.PENDING ? null : "PROFESSIONAL-NOTE-TEXT");
        b.setAdminNote("ADMIN-NOTE-SECRET");
        b.setSharedAssessment(new SharedAssessment("PHQ9", "PHQ-9", 17, "moderately severe", "2026-01-01T10:00:00Z"));
        b.setStatus(status);
        b.setExpiresAt(expires);
        if (status == BookingStatus.CONFIRMED || status == BookingStatus.ALTERNATE_PROPOSED) {
            b.setMeetingMethod("VIDEO");
            b.setMeetingLink("https://meet.example.com/MEETING-LINK-SECRET");
        }
        if (status != BookingStatus.PENDING && status != BookingStatus.EXPIRED) {
            b.setRespondedBy(status == BookingStatus.CANCELLED ? "USER" : "PROFESSIONAL");
            b.setRespondedAt(Instant.now().minus(Duration.ofHours(1)));
        }
        return bookings.saveAndFlush(b);
    }

    static String slugOf(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    // ---- JSON inspection ----

    /** Every field name anywhere in a JSON document. */
    static Set<String> allKeys(String json) {
        Set<String> keys = new HashSet<>();
        collectKeys(JSON.readTree(json), keys);
        return keys;
    }

    private static void collectKeys(JsonNode node, Set<String> keys) {
        if (node.isObject()) {
            for (String name : new ArrayList<>(node.propertyNames())) {
                keys.add(name);
                collectKeys(node.get(name), keys);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) collectKeys(child, keys);
        }
    }

    /** No stack trace, class name or SQL in an error body. */
    static void assertCleanErrorBody(String body) {
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("Exception", "exception", "\tat ", "at com.", "at org.", "java.", "org.springframework",
                        "org.hibernate", "com.neuronest", "SELECT", "select ", "INSERT", "UPDATE ", "SQL", "trace");
    }
}
