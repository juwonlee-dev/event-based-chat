package com.eventchat.web;

import com.eventchat.session.ReadPathProperties;
import com.eventchat.session.WriteProperties;
import com.eventchat.stomp.StompProperties;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * 노드별 허가(쓰기·읽기·resume)는 커넥션 풀이 바닥나기 전에 503으로 돌려보내려고 둔 것이다. 그러려면 허가를 모두 써도
 * 백그라운드 작업(outbox relay, 스냅샷 작업자, presence 대조) 몫이 남을 만큼 풀이 커야 한다.
 * 설정이 이 관계를 깨면 기동을 멈춘다(허가보다 풀이 먼저 고갈되면 503 대신 커넥션 대기 오류가 난다).
 */
@Component
class ConnectionBudgetCheck {

    static final int BACKGROUND_RESERVE = 3;

    ConnectionBudgetCheck(DataSource dataSource, WriteProperties write, ReadPathProperties read,
            StompProperties stomp) throws SQLException {
        if (!dataSource.isWrapperFor(HikariDataSource.class)) {
            return;
        }
        int pool = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
        int needed = write.maxConcurrentPerNode() + read.maxConcurrentPerNode() + stomp.resumeMaxConcurrent()
                + BACKGROUND_RESERVE;
        if (pool < needed) {
            throw new IllegalStateException("커넥션 풀(" + pool + ")이 허가 합계와 백그라운드 몫(" + needed
                    + ")보다 작습니다. spring.datasource.hikari.maximum-pool-size를 늘리세요.");
        }
    }
}
