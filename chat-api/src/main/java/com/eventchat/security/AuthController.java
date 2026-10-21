package com.eventchat.security;

import com.eventchat.persistence.user.AppUser;
import com.eventchat.persistence.user.AppUserRepository;
import com.eventchat.security.RefreshTokenService.RotationResult;
import com.eventchat.web.error.ApiException;
import com.eventchat.web.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(1);
    private static final int LOGIN_LIMIT = 5;
    private static final Duration REFRESH_WINDOW = Duration.ofMinutes(1);
    private static final int REFRESH_LIMIT = 10;

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokens;
    private final RefreshTokenService refreshTokens;
    private final RateLimiter rateLimiter;
    private final AuditLog auditLog;
    /** 없는 사용자도 같은 비용으로 비교해, 응답 시간으로 존재 여부가 드러나지 않게 한다. */
    private final String dummyPasswordHash;

    public AuthController(AppUserRepository users, PasswordEncoder passwordEncoder,
            AccessTokenService accessTokens, RefreshTokenService refreshTokens,
            RateLimiter rateLimiter, AuditLog auditLog) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.rateLimiter = rateLimiter;
        this.auditLog = auditLog;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        ClientInfo client = ClientInfo.from(http);
        String username = request.username().trim().toLowerCase(Locale.ROOT);
        rateLimiter.check("login:" + client.ip() + ":" + username, LOGIN_LIMIT, LOGIN_WINDOW, true);

        Optional<AppUser> user = users.findByUsernameIgnoreCase(username);
        String hash = user.map(AppUser::getPasswordHash).orElse(dummyPasswordHash);
        if (!passwordEncoder.matches(request.password(), hash) || user.isEmpty()) {
            auditLog.record("LOGIN", username, null, "FAILED", client.ip());
            throw new ApiException(ErrorCode.AUTH_INVALID, "아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        AppUser found = user.get();
        auditLog.record("LOGIN", found.getId().toString(), found.getRole().name(), "OK", client.ip());
        return issueTokens(found, refreshTokens.issue(found.getId(), client));
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
        ClientInfo client = ClientInfo.from(http);
        refreshTokens.findOwner(request.refreshToken()).ifPresent(owner ->
                rateLimiter.check("refresh:" + owner, REFRESH_LIMIT, REFRESH_WINDOW, false));

        RotationResult result = refreshTokens.rotate(request.refreshToken(), client);
        return switch (result) {
            case RotationResult.Rotated rotated -> {
                AppUser user = users.findById(rotated.userId())
                        .orElseThrow(() -> new ApiException(ErrorCode.REFRESH_TOKEN_INVALID, "갱신 토큰이 유효하지 않습니다."));
                auditLog.record("REFRESH", user.getId().toString(), user.getRole().name(), "OK", client.ip());
                yield issueTokens(user, rotated.token());
            }
            case RotationResult.Reused reused -> {
                auditLog.record("REFRESH", reused.userId().toString(), null, "REUSE_DETECTED", client.ip());
                throw new ApiException(ErrorCode.REFRESH_TOKEN_REUSED,
                        "이미 사용된 갱신 토큰입니다. 보안을 위해 다시 로그인해 주세요.");
            }
            case RotationResult.Invalid invalid -> {
                auditLog.record("REFRESH", null, null, "INVALID", client.ip());
                throw new ApiException(ErrorCode.REFRESH_TOKEN_INVALID, "갱신 토큰이 유효하지 않습니다.");
            }
        };
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestBody(required = false) LogoutRequest body, @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        LogoutRequest request = body != null ? body : new LogoutRequest(null, false);
        UUID userId = UUID.fromString(jwt.getSubject());
        if (request.all()) {
            refreshTokens.revokeAll(userId);
        } else if (request.refreshToken() != null) {
            refreshTokens.revokeFamilyOf(request.refreshToken(), userId);
        }
        auditLog.record(request.all() ? "LOGOUT_ALL" : "LOGOUT", userId.toString(), jwt.getClaimAsString("role"),
                "OK", ClientInfo.from(http).ip());
    }

    private TokenResponse issueTokens(AppUser user, RefreshTokenService.Issued refresh) {
        AccessTokenService.IssuedToken access = accessTokens.issue(user);
        return new TokenResponse("Bearer", access.value(), access.expiresAt(), refresh.value(), refresh.expiresAt());
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record LogoutRequest(String refreshToken, boolean all) {
    }

    public record TokenResponse(String tokenType, String accessToken, Instant accessTokenExpiresAt,
            String refreshToken, Instant refreshTokenExpiresAt) {
    }
}
