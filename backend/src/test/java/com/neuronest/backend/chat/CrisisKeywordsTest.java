package com.neuronest.backend.chat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CrisisKeywordsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            // English
            "watching at my grades. i cant face my parents. dieing feels easy",
            "Dying seems easier than this",
            "I want to die",
            "i wanna die",
            "sometimes I think about suicide",
            "I feel suicidal",
            "I want to kill myself",
            "thinking of ending my life, I mean end my life",
            "I just want to end it all",
            "everyone would be better off dead without me, I'd be better off dead",
            "there's no reason to live",
            "I don't want to wake up tomorrow",
            "I've been cutting myself again",
            "I hurt myself last night",
            "took an overdose once",
            "I'm going to hurt him",
            // Hinglish
            "mujhe ab jeena nahi hai",
            "jeene ka mann nahi karta",
            "main mar jaana chahta hoon, mar jaun",
            "marna chahti hoon",
            "khudkushi ke baare mein soch raha hoon",
            "sab khatam kar dena chahta hoon",
            "zindagi se tang aa gaya hoon",
            "apne aap ko maar dunga",
            "khud ko hurt karne ka mann karta hai",
            "neend ki goliyan jama kar li hain",
            // Devanagari
            "मैं आत्महत्या के बारे में सोचता हूँ",
            "मैं मर जाना चाहता हूँ",
            "अब जीना नहीं है",
            "जीने का मन नहीं करता",
            "ज़िंदगी खत्म कर दूँगा",
            "मैं खुद को नुकसान पहुँचाना चाहती हूँ",
            "सब खत्म कर दूँ"
    })
    void matchesCrisisPhrasesInEnglishHinglishAndDevanagari(String message) {
        assertThat(CrisisKeywords.matches(message)).as(message).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "hii",
            "I had a long day at work and I'm tired",
            "my phone battery died",
            "this exam is killing me",
            "aaj bahut thak gaya hoon",
            "mann nahi lag raha padhai mein",
            "मुझे आज बहुत थकान है",
            "the plants died while I was away",
            "I live in Pune"
    })
    void ignoresEverydayMessages(String message) {
        assertThat(CrisisKeywords.matches(message)).as(message).isFalse();
    }
}
