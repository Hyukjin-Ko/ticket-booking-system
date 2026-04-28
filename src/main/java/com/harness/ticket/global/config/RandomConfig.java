package com.harness.ticket.global.config;

import java.security.SecureRandom;
import java.util.Random;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RandomConfig {

    @Bean
    public Random random() {
        return new SecureRandom();
    }
}
