package com.eventchat.web.error;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

public final class Problems {

    private Problems() {
    }

    public static ProblemDetail of(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setProperty("code", code.name());
        return problem;
    }

    /** 프레임워크가 만든 오류에 code가 없을 때 쓰는 기본값. */
    public static String defaultCode(HttpStatusCode status) {
        if (status.value() == 400) {
            return ErrorCode.VALIDATION_FAILED.name();
        }
        return status instanceof org.springframework.http.HttpStatus known ? known.name() : "HTTP_" + status.value();
    }
}
