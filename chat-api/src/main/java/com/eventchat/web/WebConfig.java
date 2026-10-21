package com.eventchat.web;

import com.eventchat.session.IngestProperties;
import com.eventchat.session.ReadPathProperties;
import com.eventchat.session.WriteProperties;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({MessageProperties.class, WriteProperties.class, IngestProperties.class,
        ReadPathProperties.class})
@OpenAPIDefinition(
        info = @Info(title = "Event-based Chat API", version = "v1",
                description = "1:1 실시간 상담 채팅과 이벤트 기반 상태 복원 API"),
        security = @SecurityRequirement(name = "bearerAuth"))
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class WebConfig {
}
