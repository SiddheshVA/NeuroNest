package com.neuronest.backend.chat;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic backstop for the reply header's risk verdict. A match never sets risk by
 * itself: when the header says RISK:0 and a phrase matches, the separate safety classifier
 * (temperature 0) is asked, and only it can raise the turn to a crisis.
 * Deliberately broad: a false match only costs one extra classifier call.
 */
final class CrisisKeywords {

    private static final List<Pattern> PATTERNS = List.of(
            // English (including common misspellings: "dieing", "suicidal", "kms")
            p("\\bsuicid"),
            p("\\bkill(ing)?\\s+my\\s*self\\b"),
            p("\\b(end|take)\\s+my\\s+(own\\s+)?life\\b"),
            p("\\bend\\s+it\\s+all\\b"),
            p("\\b(want|wanna|wish)(ed|ing)?\\s+(to\\s+)?(die|be\\s+dead|disappear\\s+forever)\\b"),
            p("\\b(dying|dieing|death|dead)\\s+(feels|seems|sounds|looks|is|would\\s+be)\\s+(easy|easier|better|peaceful|nice|good|like\\s+(a\\s+)?(relief|escape))"),
            p("\\bbetter\\s+off\\s+dead\\b"),
            p("\\b(no|not\\s+any)\\s+(reason|point)\\s+(to|in)\\s+(live|living|go\\s+on)\\b"),
            p("\\bnot\\s+worth\\s+living\\b"),
            p("\\b(don'?t|do\\s+not|dont)\\s+want\\s+to\\s+(live|be\\s+alive|exist|wake\\s+up)\\b"),
            p("\\bself[\\s-]?harm"),
            p("\\b(hurt|harm|cut)(ting)?\\s+my\\s*self\\b"),
            p("\\bcut(ting)?\\s+my\\s+(wrist|arm)s?\\b"),
            p("\\boverdos"),
            p("\\bkms\\b"),
            p("\\b(kill|hurt|stab|shoot)\\s+(him|her|them|someone|somebody|my\\s+\\w+)\\b"),
            // Hinglish
            p("\\bjee?na\\s+(hi\\s+)?nahi\\b"),
            p("\\bjee?ne\\s+(ka|ki)\\s+(mann?|man|icchha|ichha|wajah)\\s+nahi"),
            p("\\bmar\\s+ja(na|aun|un|unga|ungi|ana|aaun|au)\\b"),
            p("\\bmarna\\s+(chahta|chahti|hai)\\b"),
            p("\\bkhud\\s*k(h)?ushi\\b"),
            p("\\bsuicide\\s+kar"),
            p("\\b(sab\\s+)?khatam\\s+kar\\s+(du|doon|dun|dena|lena|lu|loon|lun)\\b"),
            p("\\bzindagi\\s+(khatam|se\\s+tang|nahi\\s+chahiye|bekaar)"),
            p("\\b(apne\\s+aap|khud)\\s+ko\\s+(maar|maarna|khatam|hurt|nuksan|nuksaan|chot)"),
            p("\\bneend\\s+ki\\s+goli"),
            p("\\bnas\\s+kaat"),
            p("\\b(phaansi|phansi|fansi|faansi)\\b"),
            // Devanagari
            p("आत्महत्या"),
            p("खुदकुशी|ख़ुदकुशी"),
            p("मर\\s*जा(ना|ऊं|ऊँ|ऊंगा|ऊँगा|ऊंगी|ऊँगी)"),
            p("मरना\\s*(चाहता|चाहती|है)"),
            p("जीना\\s*(ही\\s*)?नहीं"),
            p("जीने\\s*(का|की)\\s*(मन|इच्छा|वजह)\\s*नहीं"),
            p("(ज़िंदगी|जिंदगी|जिन्दगी)\\s*(खत्म|ख़त्म)"),
            p("(खुद|ख़ुद|अपने\\s*आप)\\s*को\\s*(मार|खत्म|ख़त्म|नुकसान|नुक़सान|चोट)"),
            p("सब\\s*(खत्म|ख़त्म)\\s*कर"),
            p("फांसी|फाँसी"),
            p("नस\\s*काट"),
            p("नींद\\s*की\\s*गोलि")
    );

    private CrisisKeywords() {}

    static boolean matches(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String text = Normalizer.normalize(message, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)
                .replace('’', '\'');
        for (Pattern pattern : PATTERNS) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static Pattern p(String regex) {
        return Pattern.compile(regex, Pattern.UNICODE_CASE);
    }
}
