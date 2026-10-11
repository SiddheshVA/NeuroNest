package com.neuronest.backend.safety;

import java.util.List;

public final class CrisisResources {

    public static final List<CrisisResource> INDIA_RESOURCES = List.of(
            new CrisisResource("KIRAN Mental Health Helpline", "1800-599-0019", "Toll-free, 24/7, available in 13 languages"),
            new CrisisResource("Tele-MANAS", "14416 or 1800-891-4416", "24/7")
    );

    private CrisisResources() {}
}