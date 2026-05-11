# ☕ Coffee Shop Order System

> 다중 인스턴스 환경에서 **동시성 안전한 주문/결제 시스템**.
> 대기업 채용 사전과제. 평가 핵심은 *"왜 이렇게 설계했는가"의 논리*.

[![Java](https://img.shields.io/badge/Java-17-007396?logo=openjdk&logoColor=white)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-6DB33F?logo=springboot&logoColor=white)]()
[![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1?logo=mysql&logoColor=white)]()
[![Redis](https://img.shields.io/badge/Redis-7.2-DC382D?logo=redis&logoColor=white)]()
[![Kafka](https://img.shields.io/badge/Kafka-3.7-231F20?logo=apachekafka&logoColor=white)]()
[![Gradle](https://img.shields.io/badge/Gradle-8.10-02303A?logo=gradle&logoColor=white)]()

---

## 📑 목차

1. [핵심 가치 제안](#1-핵심-가치-제안)
2. [기술 스택](#2-기술-스택)
3. [시스템 아키텍처](#3-시스템-아키텍처)
4. [동시성 제어 전략](#4-동시성-제어-전략)
5. [외부 시스템 발행](#5-외부-시스템-발행)
6. [API 명세](#6-api-명세)
7. [실행 방법](#7-실행-방법)
8. [테스트](#8-테스트)
9. [트러블슈팅 / 의사결정 기록](#9-트러블슈팅--의사결정-기록)
10. [트레이드오프 / 향후 개선](#10-트레이드오프--향후-개선)
11. [디렉토리 구조](#11-디렉토리-구조)

---

## 1. 핵심 가치 제안

> **문제**: 다수의 서버 인스턴스가 동일 사용자의 결제 요청을 동시에 받으면 *Lost Update* 가 발생해 잔액이 음수가 되거나 1,000원 결제가 두 번 일어날 수 있다.

### 채택한 방어 전략 — **삼중 방어**

| 계층 | 메커니즘 | 역할 |
|---|---|---|
| **1차** | Redisson 분산 락 (`@DistributedLock`) | 사용자 단위 직렬화 — 다중 인스턴스 간 임계 구역 보장 |
| **2차** | JPA `@Version` (낙관적 락) | 1차 락이 깨졌을 때 DB 레벨 마지막 방어선 |
| **3차** | 도메인 불변식 (`UserPoint.use()`) | `잔액 음수`/`0원 충전` 등 비즈니스 규칙 강제 |

### 정합성 보강

- 주문 트랜잭션 = `(메뉴 조회 + 포인트 차감 + 주문 저장)` 원자성 보장
- 외부 데이터 플랫폼 발행은 `AFTER_COMMIT + @Async` 로 메인 흐름과 분리
- `acks=all + 멱등 producer` 로 Kafka 발행 at-least-once 보장

---

## 2. 기술 스택

| 영역 | 선택 | 채택 근거 |
|---|---|---|
| **Language** | Java 17 (LTS) | record / sealed / text blocks 활용. Spring Boot 3 최소 요구 |
| **Framework** | Spring Boot 3.3.5 | 최신 LTS, Observability/JPA/Kafka 통합 우수 |
| **ORM** | Hibernate 6 + Spring Data JPA | 도메인 중심 모델링 + dirty checking |
| **DB** | MySQL 8.0 (Docker) | `caching_sha2_password` 보안 기본값. utf8mb4 |
| **Distributed Lock** | Redisson 3.34 + Redis 7.2 | RLock pub/sub → Lettuce `SETNX` 폴링 대비 효율적 |
| **Async/Event** | Spring `@Async` + `@TransactionalEventListener` | 메인 트랜잭션과 외부 발행 격리 |
| **Messaging** | Apache Kafka 3.7 (KRaft 모드) | Zookeeper 의존 없는 단일 노드. 운영 발행기 |
| **Metrics** | Micrometer + Spring Boot Actuator | 발행 success/failure/retry Counter + latency Timer |
| **Build** | Gradle 8.10 (Wrapper 미포함) | IntelliJ 임포트 시 자동 생성 가정 |
| **Container** | Docker Compose | MySQL/Redis/Kafka 한 명령으로 기동 |
| **Test** | JUnit 5 + AssertJ + `@SpringBootTest` | 동시성 race condition 재현 부하 테스트 |

---

## 3. 시스템 아키텍처

### 3-1. 요청 흐름

```
┌──────────┐
│  Client  │
└─────┬────┘
      │ POST /api/v1/orders {userId, menuId}
      ▼
┌────────────────┐
│ OrderController│
└─────┬──────────┘
      │
      ▼
┌─────────────────────────────────────────────────────────┐
│ OrderService.order()                                    │
│   @DistributedLock(key="point:#{userId}", lease=10s)    │  ← HIGHEST_PRECEDENCE
│   @Transactional                                        │  ← 락 내부에서 시작
│                                                         │
│   1. MenuRepository.findById     (가격 확정)            │
│   2. PointService.usePoint       (REQUIRED 합류 +       │
│                                   @Version 검증)        │
│   3. OrderRepository.save        (INSERT)               │
│   4. eventPublisher.publishEvent (커밋 후 발행 예약)    │
│                                                         │
│   ── 트랜잭션 커밋 ──                                   │
│   ── 분산 락 해제   ──                                  │
└──────────────────────┬──────────────────────────────────┘
                       │ AFTER_COMMIT
                       ▼
┌────────────────────────────────────────────────────────┐
│ OrderEventListener.onOrderCreated()                    │
│   @Async("dataPlatformExecutor")    ← 락 밖, 별도 풀   │
│   @Transactional(NOT_SUPPORTED)                        │
│                                                        │
│   • OrderEventMessage.from(event)   ← 페이로드 분리    │
│   • publishWithRetry (50→100→200ms backoff, 3회)       │
│   • Counter: success / failure / retry                 │
│   • Timer: latency                                     │
└──────────────────────┬─────────────────────────────────┘
                       │
                       ▼
┌────────────────────────────────────────────────────────┐
│ DataPlatformPublisher (인터페이스)                     │
│                                                        │
│   mode=mock  → MockDataPlatformPublisher  (로그 발행)  │
│   mode=kafka → KafkaDataPlatformPublisher              │
│                  └─→ topic: coffeeshop.order-created   │
│                       partition key: userId            │
└────────────────────────────────────────────────────────┘
```

### 3-2. ERD

```
┌──────────────────┐    ┌───────────────┐    ┌─────────────────────┐
│   user_point     │    │     menu      │    │       orders        │
├──────────────────┤    ├───────────────┤    ├─────────────────────┤
│ id (PK)          │    │ id (PK)       │    │ id (PK)             │
│ user_id (UQ)     │    │ name          │    │ user_id  (idx)      │
│ balance          │    │ price         │    │ menu_id             │
│ version          │    └───────────────┘    │ paid_amount         │
└──────────────────┘                         │ created_at (idx)    │
   ↑ 낙관적 락                              │ (menu_id,            │
                                            │  created_at) (idx)   │
                                            └─────────────────────┘
                                                ↑ 인기 메뉴 7일 윈도우
                                                  집계 가속용 복합 인덱스
```

---

## 4. 동시성 제어 전략

### 4-1. 핵심 시나리오: Lost Update

```
[같은 사용자가 동시에 1,000원 결제 요청]

  T1 (server-A):  read balance=1000 → use 1000 → write balance=0
  T2 (server-B):  read balance=1000 → use 1000 → write balance=0   ← T1 변경분 유실
                                                                       1,000원 어치 공짜 결제
```

### 4-2. 락 경계는 **트랜잭션을 감싸야** 한다 (가장 중요)

> 분산 락의 보호 구간은 *DB 가 변경분을 가시화하는 시점* 까지여야 한다. 락이 트랜잭션 안쪽에 있으면 *락 해제 ↔ 커밋* 사이 갭에서 다른 인스턴스가 stale 데이터로 진입한다.

**Wrong (초기 구현)** — 락이 `PointService.usePoint` 단위에 위치:

```
OrderService 트랜잭션 시작
  → PointService 락 획득
  → 차감
  → PointService 락 해제          ← 이 시점에 아직 커밋 전!
  → orderRepository.save
OrderService 트랜잭션 커밋        ← 잔액 변경분이 이제야 가시화
```

**Right (수정 후)** — 락이 `OrderService.order` 트랜잭션 전체를 감쌈:

```
[분산 락 획득 — Aspect, HIGHEST_PRECEDENCE]
  → @Transactional 시작
    → 메뉴 조회 / 포인트 차감 / 주문 저장 / 이벤트 발행 등록
  → 트랜잭션 커밋
  → AFTER_COMMIT 리스너 (별도 스레드)
[분산 락 해제]
```

### 4-3. `@Order(HIGHEST_PRECEDENCE)` 의 의미

Spring 의 `@Transactional` 은 기본 우선순위가 `LOWEST_PRECEDENCE` 다. 분산 락 Aspect 를 `HIGHEST_PRECEDENCE` 로 명시해야 AOP 적용 순서가 **락 → 트랜잭션** 으로 보장된다.

```java
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)  // ← @Transactional 보다 먼저 실행
public class DistributedLockAspect { ... }
```

### 4-4. 비관적 락을 안 쓴 이유

| 방식 | 장점 | 단점 | 채택 |
|---|---|---|---|
| 비관적 락 (`SELECT FOR UPDATE`) | DB 단일 진실 | 다중 인스턴스에서 동일 사용자 트래픽 몰리면 **DB 커넥션이 row lock 대기로 묶여 풀 고갈** | ❌ |
| 낙관적 락 (`@Version`) | 충돌 적을 때 효율 | 충돌 시 재시도 로직 필요 | △ 보조 |
| 분산 락 (Redisson) | 사용자 단위 직렬화. DB 부하 분산 | Redis SPOF (Sentinel/Cluster 로 보완 가능) | ✅ 메인 |

### 4-5. 락 키 통일

```
충전: LOCK:point:{userId}
결제: LOCK:point:{userId}    ← 같은 자원으로 인식, 충전↔결제 직렬화
```

### 4-6. 락 획득 실패 응답: **429 Too Many Requests**

| 후보 | 의미 | 적합성 |
|---|---|---|
| 500 INTERNAL_ERROR | 서버 결함 | ❌ 운영 알람 시끄러움 + 클라이언트 재시도 어려움 |
| 503 Service Unavailable | 서비스 전체 불가 | △ 과함 (사용자별 락이므로) |
| 409 Conflict | 리소스 상태 충돌 | △ 잔액 부족엔 맞지만 락 대기 초과엔 부적합 |
| **429 Too Many Requests** | 호출 빈도 초과 | ✅ 사용자별 혼잡 의미에 정확. 4xx → 운영 알람 제외 |

---

## 5. 외부 시스템 발행

### 5-1. 페이즈 선택: AFTER_COMMIT

| 페이즈 | 동작 | 리스크 |
|---|---|---|
| BEFORE_COMMIT | 커밋 직전 발행 | 발행 후 커밋 실패 시 외부 시스템 ↔ DB 불일치 |
| **AFTER_COMMIT (채택)** | 커밋 직후 발행 | 발행 직전 서버 다운 시 이벤트 유실 (트레이드오프) |
| AFTER_ROLLBACK | 롤백 후 발행 | 실패 알림 전용 — 현재 미사용 |

### 5-2. 비동기화: `@Async("dataPlatformExecutor")`

락이 트랜잭션을 감싸는 구조이므로, AFTER_COMMIT 리스너를 동기로 호출하면 *외부 시스템 지연이 락 보유 시간을 잠식*한다. 별도 스레드 풀로 분리해 락은 커밋 직후 즉시 해제.

```yaml
core: 4 / max: 16 / queue: 500
rejection: CallerRunsPolicy   # burst 흡수, 메시지 유실보다 호출 지연 선택
```

### 5-3. swallow 정책 + 분류 재시도

| 예외 종류 | 분류 | 처리 |
|---|---|---|
| `IOException`, `TimeoutException`, Kafka `RetriableException` | 일시 장애 | exponential backoff 재시도 (50ms → 100ms → 200ms, 최대 3회) |
| 그 외 `RuntimeException` (스키마 거부, NPE 등) | 영구 실패 | 즉시 실패. `failureCounter` 증가, ERROR 로그 with `orderId` |

### 5-4. 페이로드 분리: 도메인 이벤트 vs 외부 메시지

> 내부 도메인 이벤트는 리팩토링으로 자유롭게 바뀌어야 하지만, 외부 컨트랙트는 한번 발행되면 깨기 어렵다. **라이프사이클이 다른 두 책임을 한 클래스로 묶으면 안 된다.**

```
OrderCreatedEvent  (내부 도메인 이벤트, ApplicationEventPublisher 용)
        │
        ▼  OrderEventMessage.from(event)
OrderEventMessage  (외부 발행 페이로드, schemaVersion 포함)
```

### 5-5. Kafka producer 설정 (운영 모드)

```yaml
acks: all                              # 리더 + ISR 모두 확인 = at-least-once
enable.idempotence: true               # 중복 최소화
max.in.flight.requests.per.connection: 1   # 멱등 + 순서 보장
request.timeout.ms: 2000               # 단일 요청 timeout
delivery.timeout.ms: 3000              # 전체 발행 timeout (≥ request.timeout)
```

**파티션 키 = `userId`** → 같은 사용자의 이벤트는 동일 파티션, **순서 보장**. 데이터 플랫폼 측에서 시계열 분석 시 핵심 가치.

### 5-6. 메트릭

| 메트릭 | 유형 | 의미 |
|---|---|---|
| `coffeeshop.data_platform.publish.success` | Counter | 발행 성공 누적 |
| `coffeeshop.data_platform.publish.failure` | Counter | 영구 실패 누적 |
| `coffeeshop.data_platform.publish.retry` | Counter | 재시도 횟수 |
| `coffeeshop.data_platform.publish.latency` | Timer | 발행 latency 분포 |

---

## 6. API 명세

| Method | Path | Body / Params | 응답 (`data`) | 주요 에러 코드 |
|---|---|---|---|---|
| `GET` | `/api/v1/menus` | - | `[{menuId, name, price}, ...]` | - |
| `POST` | `/api/v1/points/charge` | `{userId, amount}` | `{userId, balance}` | `INVALID_AMOUNT`, `LOCK_ACQUISITION_FAILED` |
| `GET` | `/api/v1/points/{userId}` | - | `{userId, balance}` | `POINT_NOT_FOUND` |
| `POST` | `/api/v1/orders` | `{userId, menuId}` | `{orderId, userId, menuId, paidAmount, createdAt}` | `MENU_NOT_FOUND`, `INSUFFICIENT_POINT`, `POINT_NOT_FOUND`, `LOCK_ACQUISITION_FAILED` |
| `GET` | `/api/v1/menus/popular` | - | `[{menuId, name, orderCount}, ...]` (TOP 3, 7일 윈도우) | - |

**공통 응답 포맷**

```json
{ "success": true,  "data": { ... }, "message": null,  "code": null }
{ "success": false, "data": null,    "message": "...", "code": "INSUFFICIENT_POINT" }
```

**전역 에러 매핑**

| 예외 | HTTP | 코드 | 설명 |
|---|---|---|---|
| `BusinessException` | 4xx (지정) | 도메인 코드 | 도메인이 의도적으로 던진 예외 |
| `MethodArgumentNotValidException` | 400 | `VALIDATION_ERROR` | `@Valid` 검증 실패 |
| `ObjectOptimisticLockingFailureException` | 409 | `CONCURRENT_MODIFICATION` | `@Version` 충돌 |
| `DataIntegrityViolationException` | 409 | `DATA_INTEGRITY_VIOLATION` | unique/FK/NOT NULL 위반 |
| `NoResourceFoundException` | 404 | `RESOURCE_NOT_FOUND` | 존재하지 않는 URL |
| 그 외 `Exception` | 500 | `INTERNAL_ERROR` | 미분류 |

---

## 7. 실행 방법

### 7-1. 인프라 기동

```bash
# MySQL + Redis (필수) + Kafka (운영 모드 검증용, 선택)
docker compose up -d

# Kafka 없이 mock 모드만 쓸 거면:
docker compose up -d mysql redis
```

**호스트 포트 매핑**

| 서비스 | 컨테이너 포트 | 호스트 포트 | 비고 |
|---|---|---|---|
| MySQL | 3306 | **3308** | 호스트 3306/3307 점유 회피 |
| Redis | 6379 | **6380** | 호스트 6379 점유 회피 |
| Kafka | 9092 | 9092 | KRaft 단일 노드 |

### 7-2. 애플리케이션 실행

```bash
# Mock 모드 (기본) — 외부 발행은 로그로만
./gradlew bootRun

# Kafka 모드 — KafkaDataPlatformPublisher 가 실제 토픽으로 발행
./gradlew bootRun --args='--coffeeshop.data-platform.mode=kafka'
```

> **참고**: 본 저장소에는 `gradlew` 스크립트가 포함되어 있지 않습니다. IntelliJ 임포트 시 자동 생성되거나, 시스템에 설치된 `gradle` 또는 캐시된 wrapper 를 사용하세요.

### 7-3. 동작 확인

```bash
# 메뉴 목록 (시드 데이터 자동 적재됨)
curl http://localhost:8080/api/v1/menus

# 포인트 충전
curl -X POST http://localhost:8080/api/v1/points/charge \
  -H 'Content-Type: application/json' \
  -d '{"userId":1,"amount":10000}'

# 주문
curl -X POST http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{"userId":1,"menuId":1}'

# Kafka 발행 메시지 확인 (mode=kafka 일 때)
docker exec coffee-shop-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic coffeeshop.order-created --from-beginning --timeout-ms 5000
```

**Kafka 발행 메시지 예시**

```
key:   99
value: {"schemaVersion":"v1","orderId":1,"userId":99,"menuId":1,"paidAmount":4500,"occurredAt":[2026,5,11,12,46,9,...]}
```

---

## 8. 테스트

### 8-1. 동시성 부하 테스트

| 테스트 | 시나리오 | 핵심 불변식 |
|---|---|---|
| `PointConcurrencyTest` | 100 스레드 동시 충전 | `최종잔액 = 성공횟수 × 충전금액` |
| `OrderConcurrencyTest` | 50 스레드 동시 결제 (잔액 한도 30회) | 성공 ≤ 30, 잔액 음수 불가, 주문 row 수 = 성공 건수 |

### 8-2. `OrderConcurrencyTest` 가 검증하는 6개 불변식

```
1. 의도되지 않은 예외 0건 (NPE/OptimisticLock 등 누수 차단)
2. 시도 50 = 성공 + 잔액부족 + 락실패
3. 성공 ≤ 잔액 한도(30) — Lost Update 발생 시 초과
4. 최종 잔액 = 30,000 − (성공 × 1,000), 음수 불가
5. 주문 row 수 = 성공 건수 — 트랜잭션 원자성
6. 락 경합 0건이면 성공 = 정확히 30 (강한 보장)
```

### 8-3. 실행

```bash
./gradlew test
./gradlew test --tests "com.example.coffeeshop.order.OrderConcurrencyTest"
./gradlew test --tests "com.example.coffeeshop.point.PointConcurrencyTest"
```

> **전제**: 로컬에 MySQL(3308) + Redis(6380) 가 떠 있어야 분산 락이 동작. Testcontainers 격리는 향후 개선 항목.

---

## 9. 트러블슈팅 / 의사결정 기록

> 진행하면서 실제로 부딪힌 이슈와 의사결정 흔적. 면접 답변 그대로 활용 가능.

<details>
<summary><b>9-1. 분산 락 경계가 트랜잭션 안쪽에 있어 Lost Update 가능</b> (블로커 #1)</summary>

**상황**
초기 구현은 `PointService.usePoint()` 메서드에 `@DistributedLock`. `OrderService.order()` 트랜잭션 안에서 락 획득/해제.

**문제**
락 해제 → OrderService 커밋 사이 마이크로초 갭에서 다른 인스턴스가 stale 잔액으로 진입 가능. `@Version` 이 막아주긴 하지만 그 시점엔 이미 주문 row 가 INSERT 되어 롤백 비용이 큼.

**해결**
락을 `OrderService.order()` 메서드 어노테이션으로 이동. `@Order(HIGHEST_PRECEDENCE)` 로 트랜잭션을 감싸도록 보장.

**커밋**: `279e6fa refactor(concurrency): 분산 락 경계를 OrderService 레벨로 끌어올리고 락 실패를 429로 매핑`

</details>

<details>
<summary><b>9-2. 락 획득 실패가 500 응답으로 빠짐</b> (블로커 #2)</summary>

**상황**
`DistributedLockAspect` 가 락 획득 실패 시 `IllegalStateException` 을 던짐 → `GlobalExceptionHandler.handleUnknown` 매칭 → 500 INTERNAL_ERROR + ERROR 로그.

**문제**
락 경합은 정상 트래픽에서도 발생하는 *클라이언트 재시도 가능 케이스* 인데 500 으로 묶이면 운영 알람 시끄러워지고 SLO 측정 왜곡.

**해결**
`BusinessException("LOCK_ACQUISITION_FAILED", HttpStatus.TOO_MANY_REQUESTS)` 로 변환. 4xx 분류 + WARN 로그.

**커밋**: `279e6fa` (블로커 #1 과 함께)

</details>

<details>
<summary><b>9-3. 낙관적 락 / DB 무결성 위반이 500 으로 빠짐</b> (블로커 #3)</summary>

**상황**
`ObjectOptimisticLockingFailureException`, `DataIntegrityViolationException` 모두 미분류 핸들러로 빠져 500.

**문제**
분산 락이 깨진 비정상 케이스에서야말로 이런 예외가 의미 있는데, 사용자에겐 "정상 동작 코드가 간헐 실패" 로 보임.

**해결**
GlobalExceptionHandler 에 두 핸들러 추가 → 409 CONFLICT + 일관된 ApiResponse 포맷.

**커밋**: `279e6fa`

</details>

<details>
<summary><b>9-4. 외부 발행이 락 보유 시간을 잠식</b> (블로커 B1)</summary>

**상황**
블로커 #1 수정으로 락이 OrderService 트랜잭션 전체를 감싸는 구조가 됐는데, `AFTER_COMMIT` 리스너가 동기로 외부 시스템을 호출.

**문제**
운영용 Kafka/HTTP 구현체로 바꾸면 외부 시스템 SLA (P99 수백 ms ~ 수 초) 가 그대로 락 보유 시간으로 들어와 *같은 userId 후속 요청 차단*. 더 위험한 경우, 외부 시스템 hang → leaseTime 만료 → Redisson 강제 해제 → 다른 인스턴스 진입 + 기존 리스너가 외부 시스템 계속 두드림.

**해결**
- `AsyncConfig` 신규: `@EnableAsync` + `dataPlatformExecutor` (core 4 / max 16 / queue 500, `CallerRunsPolicy`).
- `OrderEventListener.onOrderCreated` 에 `@Async("dataPlatformExecutor")` 적용 → 락은 커밋 직후 즉시 해제, 외부 I/O 는 별도 풀에서 흡수.

**커밋**: `cb04907 refactor(messaging): 외부 발행을 비동기로 분리 + 분류 재시도 + 메트릭 + 페이로드 분리`

</details>

<details>
<summary><b>9-5. Mock 발행기가 운영에 그대로 주입될 위험</b> (블로커 B2)</summary>

**상황**
`MockDataPlatformPublisher` 에 `@Component` 만 부착. javadoc 에 *"운영 전환 시 @Profile 로 한정하라"* 경고만 있을 뿐 코드 차원의 가드는 없음.

**문제**
운영 배포 시 누가 `KafkaDataPlatformPublisher` 추가하며 Mock 가드 까먹는 순간, 빈 충돌(부팅 실패)이나 Mock 묵묵히 로그만 찍는 사고(데이터 누락).

**해결**
`@ConditionalOnProperty(name="coffeeshop.data-platform.mode", havingValue="mock", matchIfMissing=true)`. 운영은 명시적으로 `mode=kafka` 설정 시에만 Mock 비활성화.

**커밋**: `cb04907`

</details>

<details>
<summary><b>9-6. ApiResponse record 와 success() 메서드 시그니처 충돌</b> (사전 존재 컴파일 에러)</summary>

**증상**
```
ApiResponse.java:32: error: invalid accessor method in record ApiResponse
  (return type of accessor method <T>success() must match the type of record component success)
```

**원인**
record 컴포넌트 `success` (boolean) 의 자동 생성 accessor 와 같은 이름의 no-arg static 메서드 `public static <T> ApiResponse<T> success()` 충돌. Java record 는 컴포넌트와 동일한 이름의 no-arg 메서드를 accessor 로 간주 (반환 타입 일치 필요).

**해결**
미사용 dead code 라 안전하게 삭제. 사유는 코드 주석으로 보존.

**커밋**: `acda596 fix(common): ApiResponse record 컴포넌트와 충돌하는 미사용 success() 제거`

</details>

<details>
<summary><b>9-7. MySQL caching_sha2_password 부팅 실패</b></summary>

**증상**
```
Public Key Retrieval is not allowed
→ Unable to determine Dialect without JDBC metadata
```

**원인**
MySQL 8 의 기본 인증 plugin `caching_sha2_password` 는 평문 비밀번호 전송 금지. `useSSL=false` 일 때 공개키 교환이 필요한데 `allowPublicKeyRetrieval=false` (기본값) 이라 인증 실패.

**해결**
`application-local.yml` JDBC URL 에 `allowPublicKeyRetrieval=true` 추가. 로컬 한정 — 운영은 `useSSL=true` 필수.

**커밋**: `b644e05 fix(config): MySQL 8 caching_sha2_password 부팅 실패 해소`

</details>

<details>
<summary><b>9-8. 호스트 포트 충돌 (3306 / 3307 / 6379)</b></summary>

**증상**
다른 프로젝트의 MySQL/Redis 가 기본 포트 점유 → `docker compose up` 시 포트 바인딩 실패 또는 인증이 잘못된 인스턴스로 시도됨.

**해결**
docker-compose 호스트 매핑을 비어있는 포트로 이동:

```yaml
mysql: "3308:3306"
redis: "6380:6379"
```

`application-local.yml` 의 connection 정보도 동시 갱신.

**커밋**: `42acbf5 fix(local): 호스트 포트 충돌 회피 — MySQL 3306→3308, Redis 6379→6380`

</details>

<details>
<summary><b>9-9. 존재하지 않는 URL 이 500 응답</b></summary>

**증상**
`GET /menus` (잘못된 경로) → 500 INTERNAL_ERROR.

**원인**
Spring Boot 3.2+ 부터 매핑되지 않은 요청은 정적 리소스 핸들러로 fallback 되어 `NoResourceFoundException` 이 던져짐. GlobalExceptionHandler 의 미분류 `Exception` 핸들러로 빠져 500 으로 노출.

**해결**
`@ExceptionHandler(NoResourceFoundException.class)` 추가 → 404 + `RESOURCE_NOT_FOUND`. 로그는 INFO 레벨 (봇/스캐너 트래픽 고려).

**커밋**: `6739e2e fix(api): 존재하지 않는 경로를 500→404, 로컬 메뉴 시드 추가`

</details>

<details>
<summary><b>9-10. 로컬 메뉴 시드 데이터 미적재 → 부팅 후 빈 메뉴</b></summary>

**상황**
`ddl-auto: create-drop` 으로 매 부팅 시 테이블이 재생성되지만 메뉴 데이터가 없음.

**해결**
- `data.sql` 신규 (아메리카노 / 카페라떼 / 카푸치노 / 바닐라라떼 4종).
- `application-local.yml`:
  - `spring.jpa.defer-datasource-initialization: true` (Hibernate ddl-auto 이후 시드 실행 보장)
  - `spring.sql.init.mode: always` (MySQL 은 기본 never 라 명시 필요)
  - `spring.sql.init.encoding: UTF-8` (Windows JVM cp949 기본값 회피)

운영 프로파일은 mode 미설정 → 시드 오염 방지.

**커밋**: `6739e2e`

</details>

<details>
<summary><b>9-11. application.yml duplicate key 부팅 실패</b> (Kafka 추가 중)</summary>

**증상**
```
DuplicateKeyException: found duplicate key spring
```

**원인**
기존 `spring:` 블록과 새로 추가한 `spring.kafka:` 블록을 별도 키로 작성. YAML 은 동일 레벨의 같은 키를 허용 안 함.

**해결**
두 `spring:` 블록을 하나로 통합.

**커밋**: `597cf6f feat(messaging): Kafka 외부 발행 구현체 + KRaft 단일 노드 인프라`

</details>

<details>
<summary><b>9-12. Kafka delivery.timeout.ms 검증 실패</b></summary>

**증상**
```
ConfigException: delivery.timeout.ms should be equal to or larger than linger.ms + request.timeout.ms
```

**원인**
`delivery.timeout.ms: 3000` 만 설정. `request.timeout.ms` 의 기본값(30000) 이 더 커서 검증 실패.

**해결**
둘 다 명시:
```yaml
request.timeout.ms: 2000
delivery.timeout.ms: 3000
```

producer 내부 재시도는 짧게, 더 큰 retry 정책은 `OrderEventListener.publishWithRetry` 가 담당하는 책임 분리.

**커밋**: `597cf6f`

</details>

<details>
<summary><b>9-13. bitnami/kafka:3.7 태그 미공개</b></summary>

**증상**
```
failed to resolve reference "docker.io/bitnami/kafka:3.7": not found
```

**원인**
bitnami 가 free tier 일부 태그 정리.

**해결**
공식 `apache/kafka:3.7.1` 이미지로 교체. 환경변수 prefix 도 `KAFKA_CFG_` → `KAFKA_` 로 변경.

**커밋**: `597cf6f`

</details>

---

## 10. 트레이드오프 / 향후 개선

### 의도적으로 안 한 것

| 항목 | 미채택 사유 | 도입 조건 |
|---|---|---|
| **완전한 Outbox 패턴** | Outbox 테이블 + Relay 워커 + Debezium 까지 가면 인프라 부담 큼. 본 과제는 *분석용 데이터 플랫폼* 발행이라 1건 유실이 즉시 비즈니스 손실은 아님. AFTER_COMMIT + @Async + retry + 메트릭 의 3중 안전망으로 추적 가능성 확보. | 결제/정산 등 *강결제* 영역 |
| **Spring Retry 의존성** | manual exponential backoff loop 으로 처리. 본 과제 범위에서 의존성 단순화 가치 큼. | 정교한 retry 정책 (jitter, circuit breaker 연동 등) 필요 시 |
| **Testcontainers** | PointConcurrencyTest / OrderConcurrencyTest 가 로컬 docker compose 의존. | CI 환경 격리 / 다른 개발자 환경 의존성 제거 |
| **Prometheus exporter** | actuator endpoint 노출 안 함 (보안). 메트릭 수집만 내부 진행. | 운영 모니터링 인프라 연동 시 (Grafana 대시보드) |
| **Redis Sorted Set 으로 인기 메뉴 가속** | DB 단일 source-of-truth + 인덱스(`menu_id, created_at`) 로 충분히 빠를 것이라 가정. | 주문 폭증으로 GROUP BY 쿼리 부담 증가 시 |
| **failed_event 테이블** | 운영 환경 본격 운용 전엔 ERROR 로그 + 메트릭이 추적 가능성 확보 | DLQ + 자동 재처리 워커 도입 시 |

### 향후 강화 방향

1. **Transactional Outbox** — 같은 트랜잭션에서 outbox 테이블 INSERT → 별도 워커 polling 또는 Debezium CDC. *강결제* 도메인 추가 시 필수.
2. **`failed_event` 테이블 + 재처리 도구** — 영구 실패 이벤트를 DB 에 보관 + 운영 도구에서 수동/자동 재발행.
3. **Kafka 컨슈머 측 idempotent 처리** — at-least-once 의 중복을 컨슈머가 흡수.
4. **인기 메뉴 집계 가속** — Redis Sorted Set (ZINCRBY) 또는 사전 집계 테이블 + 일배치.
5. **Redis 단일 점 보강** — Sentinel 또는 Cluster 도입, Redlock 알고리즘 적용 고려.
6. **컨테이너 health check / depends_on** — docker-compose 에서 MySQL/Kafka ready 까지 대기 후 앱 부팅.
7. **분산 트레이싱** — Micrometer Tracing + Zipkin/Jaeger 로 락↔트랜잭션↔외부발행 흐름 시각화.

---

## 11. 디렉토리 구조

```
src/main/java/com/example/coffeeshop
├── CoffeeShopApplication.java
├── menu/
│   ├── controller/MenuController
│   ├── service/MenuService
│   ├── repository/MenuRepository
│   ├── domain/Menu
│   └── dto/
├── point/                              ← 동시성 핵심
│   ├── controller/PointController
│   ├── service/PointService            ← @DistributedLock + @Version
│   ├── repository/UserPointRepository
│   ├── domain/UserPoint                ← @Version, 도메인 불변식
│   └── dto/
├── order/
│   ├── controller/OrderController
│   ├── service/OrderService            ← @DistributedLock(트랜잭션 감쌈)
│   ├── repository/OrderRepository      ← 인기 메뉴 집계 쿼리
│   ├── domain/Order
│   ├── dto/
│   └── messaging/
│       ├── OrderCreatedEvent           ← 내부 도메인 이벤트
│       ├── OrderEventMessage           ← 외부 발행 페이로드 (schemaVersion)
│       ├── DataPlatformPublisher       ← 인터페이스
│       ├── MockDataPlatformPublisher   ← mode=mock
│       ├── KafkaDataPlatformPublisher  ← mode=kafka
│       └── OrderEventListener          ← @Async + AFTER_COMMIT + retry
├── popularity/
│   ├── controller/PopularityController
│   ├── service/PopularityService
│   └── dto/
└── common/
    ├── lock/
    │   ├── DistributedLock             ← @DistributedLock 어노테이션
    │   └── DistributedLockAspect       ← @Order(HIGHEST_PRECEDENCE)
    ├── exception/
    │   ├── BusinessException
    │   └── GlobalExceptionHandler      ← 6개 예외 → ApiResponse 매핑
    ├── response/
    │   └── ApiResponse                 ← 공통 응답 record
    └── config/
        ├── RedissonConfig
        └── AsyncConfig                 ← dataPlatformExecutor
```

---

## 📌 커밋 컨벤션 / 진행 흔적

| Prefix | 횟수 |
|---|---|
| `feat(scope)` | 9 |
| `fix(scope)` | 5 |
| `refactor(scope)` | 2 |
| `test(scope)` | 2 |
| `docs` | 1 |
| `chore` | 2 |

최신 5개 커밋:

```
bc475e4 test(order): 동시 결제 50회 race condition 검증 테스트 추가
597cf6f feat(messaging): Kafka 외부 발행 구현체 + KRaft 단일 노드 인프라
6739e2e fix(api): 존재하지 않는 경로를 500→404, 로컬 메뉴 시드 추가
42acbf5 fix(local): 호스트 포트 충돌 회피 — MySQL 3306→3308, Redis 6379→6380
b644e05 fix(config): MySQL 8 caching_sha2_password 부팅 실패 해소
```

---

## 🔗 참고 자료

- [Redisson Distributed Locks](https://redisson.org/distributed-locks-and-synchronizers.html)
- [Spring `@TransactionalEventListener`](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html)
- [Kafka KRaft Mode](https://kafka.apache.org/documentation/#kraft)
- [Transactional Outbox Pattern](https://microservices.io/patterns/data/transactional-outbox.html)
