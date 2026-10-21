package com.eventchat.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("chat.message")
public record MessageProperties(int maxLength) {
}
