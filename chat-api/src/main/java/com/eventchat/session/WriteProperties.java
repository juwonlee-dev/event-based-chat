package com.eventchat.session;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxConcurrentPerNode 노드에서 동시에 처리하는 쓰기 수. 넘치면 기다리지 않고 503으로 돌려보내
 *                             DB 커넥션 풀이 쓰기로 고갈되지 않게 한다.
 */
@ConfigurationProperties("chat.write")
public record WriteProperties(int maxConcurrentPerNode) {
}
