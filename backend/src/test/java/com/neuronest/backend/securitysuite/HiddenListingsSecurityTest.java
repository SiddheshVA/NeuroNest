package com.neuronest.backend.securitysuite;

import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.VerificationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Hidden listings, checked against the real visibility SQL on PostgreSQL. A failure means a listing that
 * is not approved, not active, expired, suspended, or a sample row that belongs to a real account, can
 * be seen in the public directory, or that its 404 differs from a missing id's (which would reveal that
 * it exists).
 */
class HiddenListingsSecurityTest extends SecuritySuiteBase {

    private MvcResult get200or404(String path) throws Exception {
        return mvc.perform(get(path)).andReturn();
    }

    @Test
    void everyHiddenListingAnswersExactlyLikeAMissingIdAndIsNotListed() throws Exception {
        Map<String, Professional> hidden = new LinkedHashMap<>();
        for (VerificationStatus status : List.of(VerificationStatus.DRAFT, VerificationStatus.SUBMITTED,
                VerificationStatus.NEEDS_INFO, VerificationStatus.REJECTED, VerificationStatus.EXPIRED,
                VerificationStatus.SUSPENDED)) {
            hidden.put(status.name(), professional("Dr. " + status.name().toLowerCase() + " hidden", status, true, false));
        }
        hidden.put("verified but inactive", professional("Dr. Inactive", VerificationStatus.VERIFIED, false, false));
        Professional lapsed = professional("Dr. Lapsed", VerificationStatus.VERIFIED, true, false);
        lapsed.setRegistrationExpiry(LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(1));
        hidden.put("registration date passed", professionals.saveAndFlush(lapsed));
        Professional sampleWithAccount = professional("Sample With Account", VerificationStatus.DRAFT, true, true);
        account(sampleWithAccount, "pro_sample_owner");
        hidden.put("sample row with an account", sampleWithAccount);
        Professional visible = listed("Dr. Visible");

        String missingId = UUID.randomUUID().toString();
        String day = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(2).toString();
        String availability = "/availability?from=" + day + "&to=" + day;
        MvcResult missingProfile = get200or404("/api/professionals/" + missingId);
        MvcResult missingSlots = get200or404("/api/professionals/" + missingId + availability);
        assertThat(missingProfile.getResponse().getStatus()).isEqualTo(404);
        assertThat(missingSlots.getResponse().getStatus()).isEqualTo(404);
        String missingBody = missingProfile.getResponse().getContentAsString();
        assertCleanErrorBody(missingBody);
        assertThat(get200or404("/api/professionals/not-a-uuid").getResponse().getContentAsString()).isEqualTo(missingBody);

        for (var entry : hidden.entrySet()) {
            String id = entry.getValue().getId().toString();
            MvcResult profile = get200or404("/api/professionals/" + id);
            MvcResult slots = get200or404("/api/professionals/" + id + availability);
            assertThat(profile.getResponse().getStatus()).as(entry.getKey()).isEqualTo(404);
            assertThat(profile.getResponse().getContentAsString()).as(entry.getKey()).isEqualTo(missingBody);
            assertThat(slots.getResponse().getStatus()).as(entry.getKey()).isEqualTo(404);
            assertThat(slots.getResponse().getContentAsString()).as(entry.getKey())
                    .isEqualTo(missingSlots.getResponse().getContentAsString());
        }

        List<String> listedIds = new ArrayList<>();
        for (JsonNode item : JSON.readTree(get200or404("/api/professionals?size=50").getResponse().getContentAsString()).get("items")) {
            listedIds.add(item.get("id").asString());
        }
        assertThat(listedIds).contains(visible.getId().toString());
        assertThat(listedIds).doesNotContainAnyElementsOf(hidden.values().stream().map(p -> p.getId().toString()).toList());
    }

    @Test
    void aSampleRowWithoutAnAccountIsListedAsASampleAndCannotBeBooked() throws Exception {
        Professional sample = professional("Sample Listing Person", VerificationStatus.DRAFT, true, true);

        JsonNode list = JSON.readTree(get200or404("/api/professionals?size=50").getResponse().getContentAsString());
        JsonNode item = null;
        for (JsonNode candidate : list.get("items")) {
            if (candidate.get("id").asString().equals(sample.getId().toString())) item = candidate;
        }
        assertThat(item).isNotNull();
        assertThat(item.get("isSampleData").asBoolean()).isTrue();
        assertThat(item.get("credentialsLabel").asString()).isEqualTo("Sample listing");
        assertThat(item.get("bookable").asBoolean()).isFalse();

        String slot = Instant.now().plus(Duration.ofDays(3)).atZone(ZoneId.of("Asia/Kolkata"))
                .withHour(10).truncatedTo(ChronoUnit.HOURS).toOffsetDateTime().toString();
        String body = "{\"professionalId\":\"" + sample.getId() + "\",\"preferredMode\":\"ONLINE\",\"preferredMethod\":\"VIDEO\","
                + "\"contactName\":\"Ravi Kumar\",\"contactPhone\":\"9876543210\",\"slotStart\":\"" + slot + "\",\"consentGiven\":true}";
        MvcResult booking = mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", bearer(SecurityTestTokens.clerk("user_booker", "booker@example.com")))).andReturn();
        assertThat(booking.getResponse().getStatus()).isEqualTo(409);
        assertThat(booking.getResponse().getContentAsString()).contains("This is a sample listing and cannot be booked.");
        assertThat(bookings.count()).isZero();
    }
}
