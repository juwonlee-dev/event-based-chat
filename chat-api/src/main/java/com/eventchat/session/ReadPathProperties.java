package com.eventchat.session;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxConcurrentPerNode 노드에서 동시에 처리하는 무거운 읽기(목록·보충·timeline) 수. 넘치면 기다리지 않고 503으로
 *                             돌려보내 읽기가 DB 커넥션 풀을 차지해 쓰기를 막지 않게 한다.
 */
@ConfigurationProperties("chat.read-path")
public record ReadPathProperties(int maxConcurrentPerNode) {
}
