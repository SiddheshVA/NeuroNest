package com.neuronest.backend.professional;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContactDetailsGuardTest {

    private static final String BIO_ERROR = "Bio must not contain phone numbers, email addresses or website links. "
            + "People reach you through NeuroNest.";

    @Test
    void plainTextPasses() {
        for (String text : List.of(
                "I help students and young professionals manage stress, anxiety and low mood.",
                "20 years of experience, 2005-2020",
                "B.Com, M.Com, M.Phil in Clinical Psychology, Ph.D.",
                "Sessions in English and Hindi. Evenings only.",
                "I am not on Facebook or Instagram.",
                "RCI registered since 2012.")) {
            assertThat(ContactDetailsGuard.check("Bio", text)).as(text).isEmpty();
        }
        assertThat(ContactDetailsGuard.check("Bio", null)).isEmpty();
        assertThat(ContactDetailsGuard.check("Bio", "   ")).isEmpty();
    }

    @Test
    void contactDetailsAreRejected() {
        for (String text : List.of(
                "Call me on 9876543210",
                "Call 98765-432 10 for an appointment",
                "Phone: (987) 654.3210",
                "+91 98765 43210",
                "Write to asha.rao@example.com",
                "Visit www.example.com",
                "See example.in for details",
                "https://example.org/booking",
                "Find me @asha_rao",
                "WhatsApp: asha rao",
                "Instagram asha.rao",
                "linkedin/in/asharao",
                "telegram id asha_123")) {
            assertThat(ContactDetailsGuard.check("Bio", text)).as(text).contains(BIO_ERROR);
        }
    }

    @Test
    void listsReportTheFirstEntryWithContactDetails() {
        assertThat(ContactDetailsGuard.checkAll("Specializations", List.of("Anxiety", "Stress"))).isEmpty();
        assertThat(ContactDetailsGuard.checkAll("Specializations", List.of("Anxiety", "Call 9876543210")))
                .contains("Specializations must not contain phone numbers, email addresses or website links. "
                        + "People reach you through NeuroNest.");
        assertThat(ContactDetailsGuard.checkAll("Specializations", null)).isEmpty();
    }
}
