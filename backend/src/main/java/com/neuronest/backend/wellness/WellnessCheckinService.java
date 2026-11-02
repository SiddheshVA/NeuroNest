package com.neuronest.backend.wellness;

import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.wellness.WellnessDtos.CheckinItem;
import com.neuronest.backend.wellness.WellnessDtos.CheckinPage;
import com.neuronest.backend.wellness.WellnessDtos.CheckinRequest;
import com.neuronest.backend.wellness.WellnessDtos.CheckinResponse;
import com.neuronest.backend.wellness.WellnessMlDtos.MlCheckinRequest;
import com.neuronest.backend.wellness.WellnessMlDtos.MlCheckinResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Wellness check-ins. Anyone may run a check-in; only a signed-in user's is stored, and every read and delete
 * is scoped to the signed-in user here, in the service: another user's check-in id behaves exactly like one
 * that does not exist. The ML call happens outside any transaction and before anything is written, so a
 * failed call saves nothing.
 */
@Service
public class WellnessCheckinService {

    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 50;

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final WellnessMlClient ml;
    private final WellnessCheckinRepository checkins;
    private final JsonMapper json;

    public WellnessCheckinService(WellnessMlClient ml, WellnessCheckinRepository checkins, JsonMapper json) {
        this.ml = ml;
        this.checkins = checkins;
        this.json = json;
    }

    /** Scores the answers; stores them with the result only when currentUserId is not null. */
    public CheckinResponse checkin(CheckinRequest request, UUID currentUserId) {
        MlCheckinRequest mlRequest = WellnessInputs.toMl(request);
        MlCheckinResult result = ml.checkin(mlRequest);

        String sleepClass = Boolean.TRUE.equals(result.sleep().inRange()) ? result.sleep().patternCode() : null;
        Map<String, Object> resultJson = json.convertValue(result, MAP);
        Boolean seeDoctor = sleepClass == null ? null : result.sleep().seeDoctor();
        var actions = WellnessSuggestedActions.codes(result.mood().riskBand(), sleepClass, seeDoctor);

        if (currentUserId == null) {
            return new CheckinResponse(null, false, null, resultJson, actions);
        }
        WellnessCheckin row = new WellnessCheckin();
        row.setUserId(currentUserId);
        row.setSegment(mlRequest.segment());
        row.setMoodBand(result.mood().riskBand());
        row.setMoodProbability(result.mood().probability());
        row.setMoodConfidence(result.mood().confidence());
        row.setSleepInRange(Boolean.TRUE.equals(result.sleep().inRange()));
        row.setSleepClass(sleepClass);
        row.setInputs(json.convertValue(mlRequest, MAP));
        row.setResult(resultJson);
        row.setModelVersions(json.convertValue(result.modelVersions(), MAP));
        WellnessCheckin saved = checkins.save(row);
        return new CheckinResponse(saved.getId(), true, saved.getCreatedAt(), resultJson, actions);
    }

    @Transactional(readOnly = true)
    public CheckinPage list(UUID currentUserId, int page, int size) {
        if (page < 0) throw new ApiValidationException("page must be 0 or more.");
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiValidationException("size must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        Page<WellnessCheckin> rows = checkins.findByUserId(currentUserId, PageRequest.of(page, size, NEWEST_FIRST));
        return new CheckinPage(rows.map(WellnessCheckinService::item).getContent(), page, size,
                rows.getTotalElements(), rows.getTotalPages());
    }

    @Transactional(readOnly = true)
    public Optional<CheckinItem> latest(UUID currentUserId) {
        return checkins.findFirstByUserIdOrderByCreatedAtDescIdDesc(currentUserId).map(WellnessCheckinService::item);
    }

    /** The id is taken as text so a malformed one gets the same 404 as a missing or foreign one. */
    @Transactional
    public void delete(UUID currentUserId, String checkinId) {
        UUID id;
        try {
            id = UUID.fromString(checkinId);
        } catch (IllegalArgumentException e) {
            throw new WellnessExceptions.NotFoundException();
        }
        if (checkins.deleteOwn(id, currentUserId) == 0) throw new WellnessExceptions.NotFoundException();
    }

    /** Deletes all of the user's own check-ins; returns how many there were. */
    @Transactional
    public int deleteAll(UUID currentUserId) {
        return checkins.deleteAllOwn(currentUserId);
    }

    static CheckinItem item(WellnessCheckin row) {
        return new CheckinItem(row.getId(), row.getCreatedAt(), row.getSegment(), row.getResult(),
                WellnessSuggestedActions.codes(row.getMoodBand(), row.getSleepClass(),
                        WellnessSuggestedActions.seeDoctorOf(row.getResult())));
    }
}
