package com.eventchat.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventchat.session.ReadPathProperties;
import com.eventchat.session.WriteProperties;
import com.eventchat.stomp.StompProperties;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class ConnectionBudgetCheckTest {

    private final StompProperties stomp = new StompProperties(Duration.ofSeconds(10), Duration.ofSeconds(5),
            Duration.ofSeconds(10), 5, DataSize.ofKilobytes(64), DataSize.ofKilobytes(512), Duration.ofSeconds(10),
            Duration.ofSeconds(10), 200, DataSize.ofKilobytes(256), 3, 64, Duration.ofSeconds(10), List.of());

    private static HikariDataSource pool(int size) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setMaximumPoolSize(size);
        return dataSource;
    }

    @Test
    @DisplayName("풀이 허가 합계(쓰기 6 + 읽기 3 + resume 3)와 백그라운드 몫 3보다 작으면 기동을 멈춘다")
    void tooSmall() {
        assertThatThrownBy(() -> new ConnectionBudgetCheck(pool(14), new WriteProperties(6), new ReadPathProperties(3),
                stomp)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("합계 이상이면 통과한다")
    void enough() {
        assertThatCode(() -> new ConnectionBudgetCheck(pool(15), new WriteProperties(6), new ReadPathProperties(3),
                stomp)).doesNotThrowAnyException();
    }
}
