package com.neuronest.backend.professional;

import java.util.Collection;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Keeps contact details out of a professional's public text (bio, availability note, qualifications and
 * specializations), so people reach professionals through NeuroNest. Used by the professional's own form
 * and by the admin form. Never applied to the contact fields, photo URL, social links or bookings, and
 * never to reading: old text that fails is reported, not refused, when it is shown.
 */
public final class ContactDetailsGuard {

    // Phone numbers, checked after removing spaces, dashes, dots and parentheses: 10 or more digits in a
    // row, or +91 followed by digits.
    private static final Pattern NUMBER_SEPARATORS = Pattern.compile("[\\s\\-.()]");
    private static final Pattern PHONE = Pattern.compile("\\d{10,}|\\+91\\d");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");
    // A web address: http(s)://, www., or a word followed by a common domain ending. The word needs at
    // least two characters so degrees such as B.Com and M.Com are not mistaken for one.
    private static final Pattern WEB = Pattern.compile(
            "https?://|\\bwww\\.|\\b[a-z0-9][a-z0-9-]+\\.(?:com|in|org|net|co|io|me)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HANDLE = Pattern.compile("(?:^|[^A-Za-z0-9._%+-])@[A-Za-z0-9_][A-Za-z0-9_.]*");
    // A messaging or social site followed by something that looks like a handle or number: text after a
    // colon or equals sign, or a word that contains a digit, underscore, dot or slash.
    private static final Pattern SOCIAL = Pattern.compile(
            "\\b(?:whatsapp|telegram|instagram|facebook|linkedin)\\b\\s*"
                    + "(?:(?:id|handle|username|page|profile|number|no\\.?)\\s*)?"
                    + "(?:[:=]\\s*\\S+|@?[a-z0-9._/]*[0-9_./][a-z0-9._/]*[a-z0-9_])",
            Pattern.CASE_INSENSITIVE);

    private ContactDetailsGuard() {}

    /** The readable error for this text, or empty when it contains no contact details. */
    public static Optional<String> check(String fieldName, String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        boolean found = PHONE.matcher(NUMBER_SEPARATORS.matcher(text).replaceAll("")).find()
                || EMAIL.matcher(text).find()
                || WEB.matcher(text).find()
                || HANDLE.matcher(text).find()
                || SOCIAL.matcher(text).find();
        return found ? Optional.of(message(fieldName)) : Optional.empty();
    }

    /** The error for the first entry that contains contact details, or empty. */
    public static Optional<String> checkAll(String fieldName, Collection<String> entries) {
        if (entries == null) return Optional.empty();
        for (String entry : entries) {
            Optional<String> error = check(fieldName, entry);
            if (error.isPresent()) return error;
        }
        return Optional.empty();
    }

    public static String message(String fieldName) {
        return fieldName + " must not contain phone numbers, email addresses or website links. "
                + "People reach you through NeuroNest.";
    }
}
