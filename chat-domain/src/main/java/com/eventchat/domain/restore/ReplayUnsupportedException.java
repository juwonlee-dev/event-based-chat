package com.eventchat.domain.restore;

/**
 * 리듀서가 해석할 수 없는 이벤트(모르는 타입·스키마 버전, seq 빈틈). 건너뛰면 결과가 틀리므로 복원 자체를 실패시킨다.
 */
public class ReplayUnsupportedException extends RuntimeException {

    private final long seq;

    public ReplayUnsupportedException(long seq, String message) {
        super(message);
        this.seq = seq;
    }

    public long seq() {
        return seq;
    }
}
