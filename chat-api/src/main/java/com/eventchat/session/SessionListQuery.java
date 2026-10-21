package com.eventchat.session;

import com.eventchat.domain.session.SessionStatus;
import com.eventchat.domain.user.Category;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 세션 목록 필터. 날짜는 KST 기준이고 양 끝 날짜를 모두 포함한다.
 */
public record SessionListQuery(LocalDate createdFrom, LocalDate createdTo, Set<SessionStatus> statuses,
        Set<Category> categories, String title, boolean onlyMine) {

    /** 커서를 다른 조건에 이어 쓰지 못하도록 조건을 짧게 요약한다. */
    String fingerprint() {
        String canonical = String.join("|",
                String.valueOf(createdFrom), String.valueOf(createdTo),
                new TreeSet<>(statuses).toString(), new TreeSet<>(categories).toString(),
                title == null ? "" : title.toLowerCase(Locale.ROOT), String.valueOf(onlyMine));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
