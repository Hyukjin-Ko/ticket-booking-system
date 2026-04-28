package com.harness.ticket.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "queue")
public record QueueProperties(int tickIntervalSec, int admitsPerTick) {
}
