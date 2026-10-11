package com.neuronest.backend.safety;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Serves the crisis helplines so the frontend never keeps its own copy, e.g. for the
 * calm banner shown when a conversation with an earlier crisis is reopened.
 */
@RestController
@RequestMapping("/api/safety")
public class CrisisResourceController {

    @GetMapping("/crisis-resources")
    public List<CrisisResource> crisisResources() {
        return CrisisResources.INDIA_RESOURCES;
    }
}
