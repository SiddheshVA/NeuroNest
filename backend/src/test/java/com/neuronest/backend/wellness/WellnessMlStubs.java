package com.neuronest.backend.wellness;

import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Stubs a mocked WellnessMlClient from tests in other packages (its DTOs are package-private). */
public final class WellnessMlStubs {

    private WellnessMlStubs() {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The ML service answers with this JSON body (as WellnessTestData.mlResponse builds it). */
    public static void answers(WellnessMlClient ml, String mlResponseJson) {
        WellnessMlDtos.MlCheckinResult result = JSON.readValue(mlResponseJson, WellnessMlDtos.MlCheckinResult.class);
        when(ml.checkin(any())).thenReturn(result);
    }

    /** The ML service is down, slow, failing or refusing the key. */
    public static void unavailable(WellnessMlClient ml) {
        when(ml.checkin(any())).thenThrow(new WellnessExceptions.UnavailableException());
    }

    /** The ML service was never called. */
    public static void neverCalled(WellnessMlClient ml) {
        org.mockito.Mockito.verify(ml, org.mockito.Mockito.never()).checkin(any());
    }

    /** The ML service rejected one field (its 422), already mapped to this API's field name. */
    public static void rejects(WellnessMlClient ml, String field, String message) {
        when(ml.checkin(any())).thenThrow(new WellnessExceptions.InvalidInputException(
                List.of(new WellnessDtos.FieldError(field, message))));
    }
}
