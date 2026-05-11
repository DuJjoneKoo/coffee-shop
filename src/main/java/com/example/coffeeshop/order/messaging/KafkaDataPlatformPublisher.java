package com.example.coffeeshop.order.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 외부 데이터 수집 플랫폼의 Kafka 구현.
 *
 * <p>[2026-05-11 추가 — 카프카 전환]
 * {@link MockDataPlatformPublisher} 와 동등한 인터페이스 구현. mode=kafka 일 때만 활성화.
 *
 * <p>설계 결정:
 * <ul>
 *   <li><b>파티션 키 = userId</b> — 같은 사용자의 이벤트들이 동일 파티션으로 가서 *순서가 보장*됨.
 *       데이터 플랫폼 측에서 "사용자별 시계열 분석" 시 순서가 핵심이라 partition affinity 가 가치.</li>
 *   <li><b>동기 대기 (future.get(timeout))</b> — KafkaTemplate.send 는 비동기지만, 본 publisher 는
 *       {@code OrderEventListener.publishWithRetry} 가 동기 retry 로직을 가정하므로 즉시 결과 확인.
 *       리스너 자체가 @Async 라 호출 스레드는 별도 풀 (락 밖) — 동기 대기로 메인 흐름 영향 없음.</li>
 *   <li><b>acks=all + idempotent producer</b> — application.yml 의 spring.kafka.producer 설정 참조.
 *       리더 + ISR 모두 ack 확인 → at-least-once 보장. 중복은 컨슈머가 idempotent 처리 책임.</li>
 *   <li><b>토픽명</b> — {@code coffeeshop.order-created}. 도메인.이벤트형 네이밍으로 다른 도메인의
 *       토픽과 충돌 회피.</li>
 * </ul>
 *
 * <p>예외 처리:
 * 발행 실패 시 {@link RuntimeException} 으로 변환하여 던지면 {@code OrderEventListener.publishWithRetry}
 * 가 {@link TimeoutException} / Kafka {@code RetriableException} 을 일시 장애로 분류해 재시도.
 * 영구 실패(스키마/권한 등) 는 상위에서 즉시 실패로 처리되어 failureCounter 증가 + 로그.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "coffeeshop.data-platform.mode", havingValue = "kafka")
@RequiredArgsConstructor
public class KafkaDataPlatformPublisher implements DataPlatformPublisher {

    /** 발행 대기 한도. 너무 길면 dataPlatformExecutor 스레드 풀 점유, 너무 짧으면 정상 발행도 실패. */
    private static final long SEND_TIMEOUT_SECONDS = 3L;

    private final KafkaTemplate<String, OrderEventMessage> kafkaTemplate;

    @Value("${coffeeshop.data-platform.topic:coffeeshop.order-created}")
    private String topic;

    @Override
    public void publish(OrderEventMessage message) {
        // userId 를 파티션 키로 사용 — 같은 사용자 이벤트의 발행 순서 보장.
        String partitionKey = String.valueOf(message.userId());

        CompletableFuture<SendResult<String, OrderEventMessage>> future =
                kafkaTemplate.send(topic, partitionKey, message);

        try {
            // 동기 대기. timeout 초과 시 TimeoutException → 리스너가 일시 장애로 분류 후 재시도.
            SendResult<String, OrderEventMessage> result =
                    future.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (log.isDebugEnabled()) {
                log.debug("[Kafka] 발행 성공 topic={} partition={} offset={} orderId={}",
                        result.getRecordMetadata().topic(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset(),
                        message.orderId());
            }
        } catch (InterruptedException e) {
            // 인터럽트 시 스레드 상태 보존 후 RuntimeException 으로 전파.
            Thread.currentThread().interrupt();
            throw new RuntimeException("Kafka 발행 대기 중 인터럽트", e);
        } catch (ExecutionException e) {
            // future.get 의 ExecutionException 은 원인 예외를 cause 로 감싸므로 풀어서 던짐.
            // 리스너의 isTransient() 가 cause 까지 검사해 RetriableException 여부를 판별.
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("Kafka 발행 실패: " + cause.getMessage(), cause);
        } catch (TimeoutException e) {
            // 명시적으로 TimeoutException 으로 던져 리스너의 일시 장애 분류에 해당하게 함.
            throw new RuntimeException("Kafka 발행 타임아웃: " + topic, e);
        }
    }
}
