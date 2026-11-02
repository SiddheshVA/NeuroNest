package com.neuronest.backend.wellness;

import com.neuronest.backend.wellness.WellnessDtos.CheckinRequest;

import java.math.BigDecimal;

/** Shared fixtures: a check-in request and ML service responses shaped like ml-service/docs/wellness_models.md. */
public final class WellnessTestData {

    private WellnessTestData() {}

    /** Distinctive values, so a test can prove they never reach the logs. */
    public static final int SECRET_STEPS = 12_347;
    public static final int SECRET_HEART_RATE = 73;

    public static CheckinRequest request() {
        return new CheckinRequest("working", bd(34), bd("6.5"), bd(6), bd(7), bd(30), bd(4), bd(2), bd(3),
                "moderate", bd(9), null, "overweight", bd(SECRET_HEART_RATE), bd(SECRET_STEPS), null);
    }

    /** The same request as JSON in this API's camelCase. */
    public static String requestJson() {
        return """
                {"segment":"working","age":34,"sleepDuration":6.5,"qualityOfSleep":6,"stressLevel":7,
                 "physicalActivityMinutes":30,"pressure":4,"satisfaction":2,"financialStress":3,
                 "dietaryHabits":"moderate","workStudyHours":9,"bmiCategory":"overweight",
                 "heartRate":%d,"dailySteps":%d}""".formatted(SECRET_HEART_RATE, SECRET_STEPS);
    }

    /** A 200 from the ML service; see_doctor as ml-service sets it (true for insomnia_like and apnea_like). */
    public static String mlResponse(String band, boolean sleepInRange, String sleepCode) {
        return mlResponse(band, sleepInRange, sleepCode, sleepCode != null && !"none".equals(sleepCode));
    }

    /** A 200 from the ML service with an explicit sleep.see_doctor flag. */
    public static String mlResponse(String band, boolean sleepInRange, String sleepCode, boolean seeDoctor) {
        String sleep = sleepInRange ? """
                {"model":"sleep_pattern_extended","in_range":true,"reason":null,"pattern_code":"%s",
                 "display_text":"Your answers resemble a pattern.","see_doctor":%s,"probability":0.5717,
                 "probabilities":{"none":0.241,"insomnia_like":0.5717,"apnea_like":0.1873},
                 "probabilities_calibrated":false,"confidence":"medium",
                 "confidence_reasons":["model quality limits confidence to 'medium'"],
                 "drivers":[{"feature":"sleep_duration","label":"Sleep duration (h)","value":6.5,
                   "direction":"increases","effect":"increases likelihood of insomnia-like pattern","impact":0.6758}],
                 "imputed":[],"out_of_range":[],%s}""".formatted(sleepCode, seeDoctor, GUIDANCE)
                : """
                {"model":null,"in_range":false,"reason":"The sleep-pattern model was built from adults aged 27-59.",
                 "pattern_code":null,"display_text":null,"see_doctor":null,"probability":null,"probabilities":null,
                 "probabilities_calibrated":null,"confidence":null,"confidence_reasons":["out of range"],
                 "drivers":[],"imputed":[],"out_of_range":["age"],%s}""".formatted(GUIDANCE);
        return """
                {"request_id":"ml-req-1",
                 "mood":{"model":"mood_working","segment":"working","risk_band":"%s","probability":0.2041,
                   "confidence":"low","confidence_reasons":["optional input(s) not provided: ['family_history']"],
                   "drivers":[{"feature":"pressure","label":"Academic / work pressure","value":4,
                     "direction":"increases","effect":"increases estimated risk","impact":0.5537}],
                   "imputed":["family_history"],"ignored_inputs":["age"],"out_of_range":[]},
                 "sleep":%s,
                 "model_versions":{"mood_working":{"version":"20261102T110241Z-752258127ccf",
                   "trained_at_utc":"2026-11-02T11:02:41+00:00","algorithm":"logreg","sha256":"7522abc"}},
                 "disclaimer":"This is an estimate based on a few self-reported answers, not a diagnosis."}"""
                .formatted(band, sleep);
    }

    public static final String ML_422 = """
            {"error":{"code":"validation_error","message":"Invalid request: pressure: Input should be less than or equal to 5",
             "request_id":"r1","fields":[{"field":"pressure","message":"Input should be less than or equal to 5",
             "type":"less_than_equal"},{"field":"sleep_duration","message":"Field required","type":"missing"}]}}""";

    private static final String GUIDANCE = """
            "guidance":{"messages":[{"code":"sleep_short","text":"You're sleeping about 6.5 hours."}],
             "meets_sleep_guideline":false,"meets_activity_guideline":true,"weekly_activity_minutes":210.0,
             "source":"General adult guidelines."}""";

    private static BigDecimal bd(int v) {
        return BigDecimal.valueOf(v);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
