package com.neuronest.backend.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityHintsTest {

    @Test
    void namesAndLanguagePreferencesInSeveralLanguagesAreHinted() {
        assertThat(IdentityHints.mayStateIdentity("my name is Abhishek")).isTrue();
        assertThat(IdentityHints.mayStateIdentity("please call me Abhi")).isTrue();
        assertThat(IdentityHints.mayStateIdentity("mera naam Abhishek hai")).isTrue();
        assertThat(IdentityHints.mayStateIdentity("maza nav Abhishek aahe")).isTrue();
        assertThat(IdentityHints.mayStateIdentity("माझं नाव अभिषेक आहे")).isTrue();
        assertThat(IdentityHints.mayStateIdentity("mazyashi mrathitch bolt ja")).isTrue();
        assertThat(IdentityHints.mayStateIdentity("I prefer to talk in Marathi")).isTrue();
    }

    @Test
    void ordinaryMessagesAreNotHinted() {
        assertThat(IdentityHints.mayStateIdentity("I'm feeling anxious today")).isFalse();
        assertThat(IdentityHints.mayStateIdentity("tumhe kbhi stress hota hai kya")).isFalse();
        assertThat(IdentityHints.mayStateIdentity("ok")).isFalse();
    }
}
