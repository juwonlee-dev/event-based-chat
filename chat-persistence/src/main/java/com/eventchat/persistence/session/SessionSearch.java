package com.eventchat.persistence.session;

import com.eventchat.domain.session.SessionStatus;
import com.eventchat.domain.user.Category;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 세션 목록 조건. null이거나 비어 있는 조건은 적용하지 않는다.
 *
 * @param createdFrom   생성 시각 하한(포함)
 * @param createdBefore 생성 시각 상한(미포함)
 * @param title         제목 부분 일치(대소문자 무시)
 * @param participantId 이 사용자가 참여 중(퇴장하지 않음)인 세션만
 * @param after         키셋 커서. 이 위치 다음부터 읽는다
 */
public record SessionSearch(Instant createdFrom, Instant createdBefore, Set<SessionStatus> statuses,
        Set<Category> categories, String title, UUID participantId, Position after) {

    /** 정렬 기준(created_at DESC, id DESC)상의 위치. */
    public record Position(Instant createdAt, UUID id) {
    }
}
