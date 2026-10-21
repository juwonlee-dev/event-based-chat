package com.eventchat.web;

import com.eventchat.domain.user.AppUser;
import com.eventchat.domain.user.AppUserRepository;
import com.eventchat.domain.user.Category;
import com.eventchat.domain.user.Role;
import com.eventchat.web.error.ApiException;
import com.eventchat.web.error.ErrorCode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final AppUserRepository users;
    private final MessageProperties messageProperties;

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        AppUser user = users.findById(UUID.fromString(jwt.getSubject()))
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_INVALID, "사용자를 찾을 수 없습니다."));
        return new MeResponse(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(),
                user.getCategory(), messageProperties.maxLength());
    }

    public record MeResponse(UUID id, String username, String displayName, Role role, Category category,
            int maxMessageLength) {
    }
}
