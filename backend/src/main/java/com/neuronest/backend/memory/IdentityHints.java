package com.neuronest.backend.memory;

import java.util.regex.Pattern;

// Cheap pre-filter: could this message state the user's name, what to call them,
// or a language preference? Only then is the (small) identity extraction call made
// right after the turn, so most messages cost nothing extra. False positives just
// cost one small call that finds nothing; misses are still caught by the regular
// batch extraction later.
final class IdentityHints {

    private static final Pattern HINT = Pattern.compile(
            // name / what to call me: English, Hinglish, Marathi (Latin and Devanagari)
            "\\bname\\b|\\bnaam\\b|\\bnaav\\b|\\bnav\\b|call me|\\bbula|\\bbol[ao]\\b|mhan|pronoun"
                    + "|नाम|नाव|बुला|म्हण"
                    // language preference: any language name (substring, so "mrathitch" matches)
                    + "|language|bhasha|bhaasha|marathi|mrathi|hindi|english|gujarati|tamil|telugu"
                    + "|kannada|bengali|bangla|punjabi|malayalam|urdu|odia"
                    + "|भाषा|मराठी|हिंदी|हिन्दी|इंग्लिश|इंग्रजी",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private IdentityHints() {}

    static boolean mayStateIdentity(String message) {
        return message != null && HINT.matcher(message).find();
    }
}
