package com.travel.travel_recommendation.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({OpenAiProperties.class, ClaudeProperties.class, VerificationProperties.class})
public class OpenAiConfig {}
