package com.neuronest.backend.pro;

import java.util.List;
import java.util.Locale;

// The one list of Indian states and union territories a professional may choose. GET /api/pro/me sends
// it as stateOptions, so the profile form's dropdown uses exactly these spellings.
public final class IndianStates {

    static final List<String> ALL = List.of(
            // States
            "Andhra Pradesh", "Arunachal Pradesh", "Assam", "Bihar", "Chhattisgarh", "Goa", "Gujarat", "Haryana",
            "Himachal Pradesh", "Jharkhand", "Karnataka", "Kerala", "Madhya Pradesh", "Maharashtra", "Manipur",
            "Meghalaya", "Mizoram", "Nagaland", "Odisha", "Punjab", "Rajasthan", "Sikkim", "Tamil Nadu", "Telangana",
            "Tripura", "Uttar Pradesh", "Uttarakhand", "West Bengal",
            // Union territories
            "Andaman and Nicobar Islands", "Chandigarh", "Dadra and Nagar Haveli and Daman and Diu", "Delhi",
            "Jammu and Kashmir", "Ladakh", "Lakshadweep", "Puducherry");

    private IndianStates() {}

    /** The listed spelling of a state (ignoring case and surrounding spaces), or null when it is not listed. */
    static String canonical(String value) {
        if (value == null) return null;
        String wanted = value.trim().toLowerCase(Locale.ROOT);
        return ALL.stream().filter(s -> s.toLowerCase(Locale.ROOT).equals(wanted)).findFirst().orElse(null);
    }
}
