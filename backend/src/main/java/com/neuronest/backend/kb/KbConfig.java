package com.neuronest.backend.kb;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(KbProperties.class)
public class KbConfig {
}
