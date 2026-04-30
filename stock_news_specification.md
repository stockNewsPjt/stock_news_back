# Stock News Backend — 구현 명세서 v1.3 (Prototype)
### 기반 개념 명세: CLAUDE.md v1.1 (2026-04-29)
### 기준 ERD: `stock_news_erd.md` v1.0 (Prototype)
### 작성일: 2026-04-29 (v1.3 갱신: ERD 정합성 반영)

> **문서 목적**: CLAUDE.md(What) 에 대응하는 **How 명세**. 각 기능의 Spring 컴포넌트·외부 API·데이터 흐름을 정의하여 개발팀이 즉시 구현에 착수할 수 있도록 한다.
>
> **v1.3 (Prototype) 범위 — ERD 와 일치**:
> - 사용자(`app_user`)는 **조회 전용** — 뉴스 요약, Layer 결과, AI 매매 정보를 읽기만 한다.
> - 가상 매매는 **AI 단일 계정** (`virtual_account.owner_id = 'AI_SYSTEM'`) 이 수행. 사용자 수동 매매 없음.
> - AI 매매 방향은 **long-only** (BUY 진입 + STOP_HIT/TP_HIT 자동 청산). SELL 은 v1.4 에 도입.
> - `leaderboard_snapshot` 테이블 / 리더보드 API / User vs AI 경쟁 — 후속 단계로 연기.
> - 총 DB 테이블: **18개** (v1.2 의 19개 - `leaderboard_snapshot` 1개).

---

## 목차

1. [시스템 개요](#1-시스템-개요)
2. [모듈/패키지 구조](#2-모듈패키지-구조)
3. [API 명세](#3-api-명세)
4. [Input / Output Payload](#4-input--output-payload)
5. [News Crawler (Java)](#5-news-crawler-java)
6. [AI Agent 1 — Summarizer + Deduplicator](#6-ai-agent-1--summarizer--deduplicator)
7. [Layer 별 구현 명세](#7-layer-별-구현-명세)
8. [Final AI Agent — Trade Decision](#8-final-ai-agent--trade-decision)
9. [Pipeline Orchestrator](#9-pipeline-orchestrator)
10. [Output Aggregator](#10-output-aggregator)
11. [AI Virtual Trading (Prototype)](#11-ai-virtual-trading-prototype)
12. [JPA Entity / DB Schema](#12-jpa-entity--db-schema)
13. [보안](#13-보안)
14. [캐시 전략 (Redis Key Schema)](#14-캐시-전략-redis-key-schema)
15. [비동기 / 동시성 설정](#15-비동기--동시성-설정)
16. [모니터링](#16-모니터링)
17. [테스트 전략](#17-테스트-전략)
18. [외부 API 의존성 매트릭스 및 추가 의존성](#18-외부-api-의존성-매트릭스-및-추가-의존성)
19. [구현 마일스톤](#19-구현-마일스톤)

---

## 1. 시스템 개요

### 1.1 프로젝트 목표

> "해당 뉴스가 현재 시장 환경에서 실제 가격 상승/하락으로 번질 확률"과
> "진입 시 적정 포지션·손절·익절 레벨"을 동시에 산출하고,
> **AI 가 가상자금으로 자동 매매**하여 사용자에게 매매 의사결정의 참고 정보를 제공한다.

### 1.2 v1.1 개념 명세와의 차이

| 항목 | CLAUDE.md v1.1 | 본 문서 v1.3 (Prototype) |
|---|---|---|
| AI Agent 수 | Agent 1 + Agent 2 (별도 Python) | Agent 1(요약) + **Final AI Agent(매매결정)** — Spring 내부 LLM 호출 |
| 파이프라인 최상단 | (미정의) | **Java 기반 News Crawler** |
| AI 최종 출력 | final_score + signal | + **action / stop_loss_price / take_profit_price / position_pct_of_capital** |
| 가상 트레이딩 | 미정의 | **AI 단독 가상 매매 (long-only)** — 사용자는 조회 전용. 사용자 매매·리더보드는 v1.4 |
| DB 테이블 수 | 13개 | **18개** (5개 신규: `news_source_registry`, `virtual_account`, `virtual_position`, `virtual_trade_log`, `app_user`) |

### 1.3 전체 파이프라인

```
[News Crawler (Java)]                          §5
       ↓  NewsIngestedEvent
[AI Agent 1 : Summarizer + Deduplicator]       §6
       ↓
[Layer 0 : Freshness & Liquidity Pre-filter]   §7.0  → REJECT 분기 (즉시 종료)
       ↓
[Layer 1 ~ 6 : Async Parallel API Calls]       §7.1~7.6  (CompletableFuture.allOf)
       ↓
[Output Aggregator : computeBaseScore]         §10  (CLAUDE.md §4.1 가중합)
       ↓
[Final AI Agent : Trade Decision]              §8
   action ∈ {BUY, SELL, HOLD}
   + stop_loss_price + take_profit_price + position_pct_of_capital
       ↓
[Output Aggregator : mergeAiDecision]          §10
       ↓
[DB 저장 + Virtual Trade Execution]            §11
       ↓
[API Response]
```

### 1.4 기술 스택 매트릭스

| 계층 | 기술 | 역할 |
|---|---|---|
| Web | Spring MVC (REST) | API 엔드포인트 |
| Service | Spring Service + WebClient (Webflux) | 외부 시세/매크로/LLM API 호출 |
| Persistence | Spring Data JPA + QueryDSL + PostgreSQL | 데이터 저장 및 복잡 집계 조회 |
| Cache | Redis (Spring Data Redis) | 중복 제거·시세·시그널 캐싱 |
| Search | Elasticsearch | 뉴스 임베딩 인덱싱 + 유사도 dedup |
| Async | `@Async` + `CompletableFuture` + `TaskExecutor` | Layer 병렬 호출, 크롤러 병렬 수집 |
| Scheduler | `@Scheduled` | 크롤러 (1m), 포지션 모니터 (30s) |
| Monitoring | Actuator + Micrometer + Prometheus | 메트릭, 헬스체크 |
| Security | Spring Security + OAuth2 (Google) + JWT (jjwt) | 사용자 인증, 관리자 권한 |
| Migration | Flyway | DB 스키마 버전 관리 |
| Resilience | Resilience4j | 외부 API RateLimiter + CircuitBreaker |

---

## 2. 모듈/패키지 구조

```
com.example.stock_news_back
 ├─ api
 │   ├─ signal          SignalController                   POST /api/v1/signals
 │   ├─ trading         AiAccountController                AI 계좌·포지션·거래 내역 조회 전용
 │   └─ admin           AdminController                    AI 가상자금 충전, 백테스트 조회
 │
 ├─ pipeline
 │   └─ SignalPipelineOrchestrator                         §9 파이프라인 흐름 제어
 │
 ├─ crawler                                                §5 신규
 │   ├─ NewsCrawlerScheduler                              @Scheduled 1분마다
 │   ├─ NewsIngestionService
 │   └─ adapter
 │       ├─ NewsSourceAdapter (interface)
 │       ├─ ReutersRssAdapter
 │       ├─ BloombergRssAdapter
 │       ├─ YahooFinanceRssAdapter
 │       └─ SecEdgar8KAdapter
 │
 ├─ ai
 │   ├─ summarizer
 │   │   └─ AiAgent1Client                                §6 요약 + 중복 제거
 │   └─ decision
 │       └─ FinalAiAgentClient                            §8 매매 결정
 │
 ├─ layer0_prefilter
 │   └─ Layer0PrefilterService
 ├─ layer1_positioning
 │   └─ Layer1PositioningService
 ├─ layer2_expectation
 │   └─ Layer2ExpectationService
 ├─ layer3_event_surprise
 │   └─ Layer3EventSurpriseService
 ├─ layer4_microstructure
 │   └─ Layer4MicrostructureService
 ├─ layer5_macro
 │   └─ Layer5MacroService
 ├─ layer6_attention
 │   └─ Layer6AttentionService
 │
 ├─ aggregator
 │   ├─ OutputAggregator                                  §10 점수 합산 + AI 결정 병합
 │   └─ ScoreCalculator                                   CLAUDE.md §4.1 가중합 공식
 │
 ├─ trading                                               §11 신규 (AI 전용)
 │   ├─ VirtualTradeEngine
 │   └─ PositionMonitorScheduler
 │
 ├─ domain
 │   └─ (JPA Entity — §12 참조)
 │
 ├─ repository
 │   └─ (JpaRepository + QueryDSL Custom — §12 참조)
 │
 ├─ infra
 │   ├─ WebClientConfig      (crawlerWebClient, marketWebClient, aiWebClient)
 │   ├─ RedisConfig
 │   └─ ElasticsearchConfig
 │
 ├─ common
 │   ├─ enums               Action, Signal, Horizon, TradeAction, PositionStatus
 │   ├─ dto                 ApiResponse<T>, LayerScoresAggregate
 │   └─ exception           RejectedNewsException, ExternalApiException,
 │                          InsufficientFundsException, LowConfidenceException
 │
 └─ config
     ├─ SecurityConfig
     ├─ AsyncConfig          (@EnableAsync + 3개 TaskExecutor Bean)
     ├─ CacheConfig
     └─ SwaggerConfig
```

---

## 3. API 명세

### 3.1 메인 엔드포인트

| Method | Path | 설명 | 인증 |
|---|---|---|---|
| POST | `/api/v1/signals` | 단건 뉴스 → 파이프라인 트리거 (수동/테스트용) | JWT |
| GET | `/api/v1/signals/{news_id}` | 시그널 결과 조회 | JWT |
| GET | `/api/v1/signals?ticker={ticker}&limit=20` | ticker 별 최신 시그널 목록 | JWT |

### 3.2 Layer 디버깅 엔드포인트 (CLAUDE.md §3)

| Method | Path | Layer |
|---|---|---|
| POST | `/api/v1/prefilter` | Layer 0 |
| GET | `/api/v1/market/positioning/{ticker}` | Layer 1 |
| GET | `/api/v1/market/expectation/{ticker}` | Layer 2 |
| POST | `/api/v1/market/event-surprise` | Layer 3 |
| GET | `/api/v1/market/microstructure/{ticker}` | Layer 4 |
| GET | `/api/v1/market/macro` | Layer 5 |
| GET | `/api/v1/market/attention/{ticker}` | Layer 6 |

### 3.3 AI 가상 매매 조회 엔드포인트 (사용자: 조회 전용)

| Method | Path | 설명 | 인증 |
|---|---|---|---|
| POST | `/api/v1/admin/virtual-account/deposit` | 관리자: AI 가상자금 충전 (`AI_SYSTEM` 단일 계정) | JWT + ROLE_ADMIN |
| GET | `/api/v1/virtual-account/ai` | AI 계좌 조회 (잔고, 평가금, 누적 수익률) | JWT |
| GET | `/api/v1/virtual-account/ai/positions?status=OPEN` | AI 보유 포지션 | JWT |
| GET | `/api/v1/virtual-account/ai/trades?limit=20` | AI 거래 내역 | JWT |

> 사용자 수동 매매(`POST /me/orders`) 및 리더보드(`/leaderboard`) 엔드포인트는 v1.4 에서 도입.

### 3.4 Spring 구현 매핑

```java
// 공통 응답 래퍼
record ApiResponse<T>(boolean success, T data, ErrorDetail error) {
    static <T> ApiResponse<T> ok(T data) { ... }
    static <T> ApiResponse<T> fail(ErrorDetail err) { ... }
}

// 컨트롤러 패턴
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SignalController {
    @PostMapping("/signals")
    public ResponseEntity<ApiResponse<SignalResponse>> createSignal(
        @Validated @RequestBody SignalRequest req) { ... }
}
```

### 3.5 에러 처리

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(RejectedNewsException.class)   // 200 + rejection_reason 채움
    @ExceptionHandler(ExternalApiException.class)    // 503
    @ExceptionHandler(InsufficientFundsException.class) // 400
    @ExceptionHandler(LowConfidenceException.class)  // 200 + signal=HOLD
    @ExceptionHandler(ConstraintViolationException.class) // 400
}
```

---

## 4. Input / Output Payload

### 4.1 Input (CLAUDE.md §2.1 동일)

```json
{
  "ticker": "NVDA",
  "news_id": "NEWS_20260429_001",
  "headline": "NVIDIA announces new AI datacenter chip",
  "body_summary": "...",
  "summary_score": 0.87,
  "summary_score_definition": "AI agent가 산출한 뉴스 본문의 정보 밀도(0~1)",
  "news_sentiment": "positive",
  "news_category": "product_launch",
  "source": "Reuters",
  "published_at": "2026-04-29T09:30:00Z",
  "ingested_at": "2026-04-29T09:31:12Z"
}
```

Crawler 가 자동으로 채워 파이프라인에 투입. `POST /api/v1/signals` 는 수동 테스트 시에만 사용.

### 4.2 Output (v1.2 — ai_decision 신규)

```json
{
  "ticker": "NVDA",
  "news_id": "NEWS_20260429_001",
  "final_score": 82.4,
  "signal": "STRONG_BUY",
  "confidence": 0.91,

  "ai_decision": {
    "action": "BUY",
    "stop_loss_price": 145.20,
    "take_profit_price": 162.00,
    "position_pct_of_capital": 12.5,
    "rationale": "VIX 하락 구간 + 실적 발표 18일 전 + 데이터센터 모멘텀 강화"
  },

  "layer_scores": {
    "layer0_freshness_liquidity": 88,
    "layer1_psychology_positioning": 74,
    "layer2_expectation_revision": 81,
    "layer3_event_surprise": 92,
    "layer4_microstructure": 79,
    "layer5_macro_cross_asset": 68,
    "layer6_attention_edge": 77
  },
  "time_horizon": {
    "1h":  { "signal": "BUY",        "expected_move_pct": 0.8 },
    "1d":  { "signal": "STRONG_BUY", "expected_move_pct": 2.4 },
    "3d":  { "signal": "HOLD",       "expected_move_pct": 1.1 }
  },
  "risk_management": {
    "suggested_position_pct": 12.5,
    "stop_loss_pct": -1.8,
    "take_profit_pct": 3.6,
    "stop_loss_price": 145.20,
    "take_profit_price": 162.00,
    "max_holding_hours": 24
  },
  "rejection_reason": null
}
```

#### ai_decision 검증 규칙

| action | stop_loss_price | take_profit_price | position_pct_of_capital |
|---|---|---|---|
| `HOLD` | `null` | `null` | `0` |
| `BUY` | `> 0`, `< entry_price` | `> entry_price` | `0 < x ≤ 25` (cap) |
| `SELL` | `> entry_price` | `> 0`, `< entry_price` | `0 < x ≤ 25` (cap) |

- 부등식 위반 시: `OutputAggregator` 가 `action = HOLD` 로 강등, 메트릭 `ai.decision.invalid` 카운트
- `position_pct_of_capital > admin.max_single_position_cap` (기본 25%) 초과 시: cap 적용

---

## 5. News Crawler (Java)

### 5.1 책임

외부 뉴스 소스를 주기적으로 수집하여 `news_raw` 테이블에 적재하고, `NewsIngestedEvent` 를 발행하여 파이프라인을 트리거한다.

### 5.2 컴포넌트

#### NewsCrawlerScheduler
```java
@Component
@RequiredArgsConstructor
public class NewsCrawlerScheduler {

    private final List<NewsSourceAdapter> adapters;
    private final NewsIngestionService ingestionService;

    @Scheduled(fixedDelayString = "${crawler.interval-ms:60000}")
    public void crawl() {
        adapters.parallelStream()
            .filter(NewsSourceAdapter::isEnabled)
            .forEach(adapter -> ingestionService.ingest(adapter));
    }
}
```

#### NewsSourceAdapter (interface)
```java
public interface NewsSourceAdapter {
    String sourceId();
    boolean isEnabled();
    List<RawNewsDto> fetch();   // 개별 어댑터가 구현
}
```

#### 구현 어댑터

| 어댑터 | 수집 방식 | 파싱 라이브러리 |
|---|---|---|
| `ReutersRssAdapter` | RSS Feed | Rome (`SyndFeedInput`) |
| `BloombergRssAdapter` | RSS Feed | Rome |
| `YahooFinanceRssAdapter` | RSS Feed + JSON | Rome + Jackson |
| `SecEdgar8KAdapter` | EDGAR Full-Text Search API | WebClient + Jackson |

#### NewsIngestionService
```java
@Service
public class NewsIngestionService {

    private final StringRedisTemplate redis;
    private final NewsRawRepository newsRawRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final RateLimiterRegistry rateLimiterRegistry;

    @Async("crawlerExecutor")
    public void ingest(NewsSourceAdapter adapter) {
        RateLimiter limiter = rateLimiterRegistry.rateLimiter(adapter.sourceId());
        limiter.executeRunnable(() -> {
            List<RawNewsDto> items = adapter.fetch();
            items.stream()
                .filter(dto -> !isDuplicate(dto.url()))   // URL 해시 dedup
                .map(this::toEntity)
                .forEach(entity -> {
                    newsRawRepository.save(entity);
                    markUrl(entity.getSourceUrl());
                    eventPublisher.publishEvent(new NewsIngestedEvent(entity.getNewsId()));
                });
        });
    }

    private boolean isDuplicate(String url) {
        String key = "news:url_hash";
        String hash = DigestUtils.sha256Hex(url);
        return Boolean.FALSE.equals(redis.opsForSet().add(key, hash));  // 0 = 이미 존재
    }
}
```

### 5.3 HTTP 클라이언트 설정

```java
@Bean("crawlerWebClient")
public WebClient crawlerWebClient() {
    return WebClient.builder()
        .clientConnector(new ReactorClientHttpConnector(
            HttpClient.create().responseTimeout(Duration.ofSeconds(5))))
        .defaultHeader("User-Agent", "StockNewsBot/1.0")
        .build();
}
```

- retry: `.retryWhen(Retry.fixedDelay(2, Duration.ofSeconds(1)).filter(WebClientException.class::isInstance))`
- `robots.txt` 캐시: 어댑터 초기화 시 1회 확인, Redis `robots:{sourceId}` 24h TTL

### 5.4 Resilience4j 설정

```yaml
resilience4j:
  ratelimiter:
    instances:
      reuters:   { limitForPeriod: 10, limitRefreshPeriod: 60s, timeoutDuration: 0 }
      bloomberg: { limitForPeriod: 5,  limitRefreshPeriod: 60s, timeoutDuration: 0 }
  circuitbreaker:
    instances:
      default: { slidingWindowSize: 20, failureRateThreshold: 50 }
```

### 5.5 추가 DB 테이블

**`news_source_registry`**

| 컬럼 | 타입 | 설명 |
|---|---|---|
| source_id | VARCHAR(50) PK | 어댑터 식별자 |
| name | VARCHAR(100) | 표시명 |
| base_url | VARCHAR(500) | 기본 URL |
| robots_checked_at | TIMESTAMPTZ | robots.txt 마지막 확인 |
| enabled | BOOLEAN | 크롤링 활성화 여부 |
| rate_limit_per_min | INT | 분당 최대 요청 수 |

### 5.6 추가 의존성 (build.gradle)

```groovy
implementation 'org.jsoup:jsoup:1.17.2'
implementation 'com.rometools:rome:2.1.0'
implementation 'io.github.resilience4j:resilience4j-spring-boot3:2.2.0'
```

---

## 6. AI Agent 1 — Summarizer + Deduplicator

### 6.1 책임

뉴스 본문을 LLM 으로 요약하고 임베딩을 생성하여 의미 기반 중복 여부를 판별한다.

### 6.2 구현

```java
@Component
public class AiAgent1Client {

    private final WebClient aiWebClient;
    private final ElasticsearchOperations esOps;
    private final NewsClusterRepository clusterRepo;

    @Async("aiExecutor")
    public CompletableFuture<SummaryResult> summarize(NewsRaw news) {
        // 1. LLM 요약 + 임베딩 요청
        LlmResponse resp = aiWebClient.post()
            .uri("/v1/messages")
            .bodyValue(buildSummaryRequest(news))
            .retrieve()
            .bodyToMono(LlmResponse.class)
            .timeout(Duration.ofSeconds(8))
            .retry(1)
            .block();

        // 2. 임베딩 → ES 인덱싱
        NewsEmbeddingDoc doc = new NewsEmbeddingDoc(news.getNewsId(), resp.embedding());
        esOps.save(doc);

        // 3. 의미 기반 dedup (more_like_this)
        String clusterId = findOrCreateCluster(news.getTicker(), resp.embedding());

        return CompletableFuture.completedFuture(
            new SummaryResult(resp.summary(), resp.summaryScore(), resp.category(),
                              clusterId, resp.isPrimarySource()));
    }
}
```

### 6.3 LLM 호출 상세

- **호출 방식**: Spring 내부 WebClient → Anthropic Messages API (또는 OpenAI Chat Completions)
- **모델 선택**: `@ConfigurationProperties("ai.summarizer.model")` — 런타임 교체 가능
- **Prompt 구조**:
  - system: "다음 뉴스를 3문장 이내로 요약하고, 카테고리(product_launch/earnings/regulatory/macro/other)를 분류하라. JSON 형식으로만 응답하라."
  - user: `{headline}\n\n{body}`
- **응답 파싱**: `response_format: {type: "json_object"}` (OpenAI) 또는 tool use / JSON mode (Anthropic)
- **임베딩**: 별도 Embeddings API 호출 (`text-embedding-3-small` 또는 `voyage-3`) → 1536dim dense_vector → ES

### 6.4 의미 기반 Dedup

```java
private String findOrCreateCluster(String ticker, float[] embedding) {
    // ES more_like_this 또는 kNN 검색으로 유사 뉴스 탐색
    SearchHits<NewsEmbeddingDoc> hits = esOps.search(
        new NativeQuery(knnQuery(embedding, 0.92f, 3)), NewsEmbeddingDoc.class);

    if (hits.hasSearchHits()) {
        return hits.getSearchHit(0).getContent().getClusterId();
    }
    String newClusterId = "CLUSTER_" + ticker + "_" + LocalDate.now() + "_" + UUID.randomUUID();
    return newClusterId;
}
```

### 6.5 장애 처리

- timeout/retry 실패 시: `news_summary.status = 'FAILED'` 저장
- Redis Stream `dlq:summarizer` 에 `news_id` 적재 → 별도 `@Scheduled` 리스너가 재처리 (최대 3회)
- 3회 실패 시: `status = 'DEAD'`, 알림 메트릭 `ai.summarizer.dead_count` 카운트

---

## 7. Layer 별 구현 명세

**공통 패턴**:
- 각 Layer Service 는 `@Async("layerExecutor")` + `CompletableFuture<LayerNResult>` 반환
- 외부 API 호출: `marketWebClient` (crawlerWebClient 와 풀 분리)
- timeout 1.5s cap (파이프라인 SLA 보호)
- fallback: `@CircuitBreaker` — open 시 이전 캐시 값 반환, 없으면 중립값 반환 (점수 50)

---

### 7.0 Layer 0 — Freshness & Liquidity Pre-filter

**Endpoint**: `POST /api/v1/prefilter`

**컴포넌트**: `Layer0PrefilterService`

```java
@Service
public class Layer0PrefilterService {

    public Layer0Result evaluate(NewsRaw news, SummaryResult summary) {
        double ageMin = Duration.between(news.getPublishedAt(), Instant.now()).toMinutes();
        double decay = Math.exp(-ageMin / 30.0);

        LiquidityInfo liq = getLiquidity(news.getTicker());   // Redis 캐시 15m

        // Reject 판정
        if (ageMin > 60 && summary.duplicateCountInCluster() > 1)
            throw new RejectedNewsException("AGE_AND_DUPLICATE");
        if (!summary.isPrimarySource())
            decay *= 0.7;
        if (liq.marketCapUsd() < 500_000_000L || liq.avgDailyVolumeUsd() < 10_000_000L)
            throw new RejectedNewsException("LOW_LIQUIDITY");

        return new Layer0Result(ageMin, summary.clusterId(), summary.isPrimarySource(),
                                summary.duplicateCountInCluster(), liq.marketCapUsd(),
                                liq.avgDailyVolumeUsd(), true, decay);
    }
}
```

**데이터 소스**: Polygon.io `/v3/reference/tickers/{ticker}` (시총), `/v2/aggs` (ADV 30일)

**캐시**: `liquidity:{ticker}` Redis 15m TTL

---

### 7.1 Layer 1 — Psychology & Positioning

**Endpoint**: `GET /api/v1/market/positioning/{ticker}`

**컴포넌트**: `Layer1PositioningService`

**데이터 소스 및 매핑**:

| 필드 | 외부 API | 캐시 TTL |
|---|---|---|
| `vix_term_structure` | CBOE 공식 데이터 / Yahoo `^VIX1D`, `^VIX`, `^VIX3M` | 1m |
| `put_call_ratio` | CBOE Daily Market Statistics CSV | 15m |
| `short_float_ratio`, `days_to_cover` | Finra Short Interest API | 1h |
| `fear_greed_composite` | CNN Fear & Greed (unofficial scraping) | 30m |
| `implied_realized_vol_spread` | Tradier / Polygon Options → IV 산출 후 HV 차감 | 5m |

**정규화**: 각 raw 값 → 히스토리컬 퍼센타일 기반 0~100 변환 (`Layer1Normalizer.normalize()`)

**Fallback**: CBOE 장애 시 yfinance-proxy 마이크로서비스 (별도 운영) 또는 이전 캐시 값

---

### 7.2 Layer 2 — Expectation Revision

**Endpoint**: `GET /api/v1/market/expectation/{ticker}`

**컴포넌트**: `Layer2ExpectationService`

**데이터 소스**:

| 필드 | 외부 API |
|---|---|
| `eps_revision_trend_30d`, `target_price_revision_30d`, `analyst_rating_delta_30d` | Financial Modeling Prep (FMP) `/analyst-estimates/{ticker}` |
| `expectation_gap` | FMP EPS Surprise API |
| `days_to_next_earnings` | FMP `/historical/earning_calendar/{ticker}` |
| `insider_activity_score` | SEC EDGAR EFTS Form 4 API → `InsiderActivityClient` (XML 파싱) |

**증폭 규칙**: `days_to_next_earnings < 7` → Layer 2 점수 가중치 ×1.5 적용 (`OutputAggregator` 에서 처리)

**캐시**: `expectation:{ticker}` 1h TTL (실적 캘린더), Form 4 는 1일 TTL

---

### 7.3 Layer 3 — Event Surprise

**Endpoint**: `POST /api/v1/market/event-surprise`

**컴포넌트**: `Layer3EventSurpriseService`

```java
@Service
public class Layer3EventSurpriseService {

    private final HistoricalAnalogLibraryRepository histRepo;

    public Layer3Result calculate(String ticker, String category, double summaryScore) {
        // QueryDSL 집계: 동일 category, 동일 ticker 의 과거 1d 수익률 통계
        HistoricalStats stats = histRepo.findStatsByTickerAndCategory(ticker, category);

        if (stats.sampleSize() < 20)
            return Layer3Result.lowSample(stats);  // confidence cap 0.5

        double zScore = (summaryScore - stats.meanReturn()) / stats.stdDev();
        return new Layer3Result(zScore, stats.mean1dReturn(), stats.winRate(),
                                stats.baseSectorRate(), stats.sampleSize());
    }
}
```

**데이터**: 내부 `historical_analog_library` 테이블 — 외부 API 불필요

**QueryDSL**: `QHistoricalAnalogLibrary` 로 `ticker`, `category` 필터링 + `AVG`, `STDDEV`, `COUNT` 집계

---

### 7.4 Layer 4 — Microstructure

**Endpoint**: `GET /api/v1/market/microstructure/{ticker}`

**컴포넌트**: `Layer4MicrostructureService`

**데이터 소스**: Polygon.io (1m bar, Level 1 quote)

**핵심 계산**:

```java
// volume_shock_zscore: 오늘 거래량 vs 30일 동시간대 분포
double shock = (todayVolume - historicalMean) / historicalStdDev;

// bollinger_squeeze: BB width 가 6개월 percentile 20 이하
boolean squeeze = bbWidth < bb6MonthPercentile20;

// vwap_deviation_pct: (현재가 - VWAP) / VWAP * 100
double vwapDev = (currentPrice - vwap) / vwap * 100.0;
```

**캐시**: `quote:{ticker}` Redis 1m TTL — **Layer 4 의 `currentPrice` 를 Final AI Agent 와 Virtual Trade Engine 이 공유**

**Fallback**: Polygon 장애 시 Yahoo Finance unofficial API (비공식, 운영 환경에선 유료 벤더 권장)

---

### 7.5 Layer 5 — Macro & Cross-Asset

**Endpoint**: `GET /api/v1/market/macro`

**컴포넌트**: `Layer5MacroService`

**특징**: ticker 무관 — 응답을 `macro:snapshot` 단일 키로 전체 공유

**데이터 소스**:

| 필드 | API | 갱신 주기 |
|---|---|---|
| `us10y_yield_change_bps` | FRED API `DGS10` | 일 1회 |
| `dxy_change_pct` | FRED `DTWEXBGS` 또는 Yahoo `DX-Y.NYB` | 1h |
| `nfci` | FRED `NFCI` | 주 1회 (수요일) |
| `sector_etf_corr_30d` | Polygon `/v2/aggs` (QQQ, SOXX 등) → Pearson 계산 | 1h |
| `index_futures_direction` | CME Group Data (ES1!, NQ1!) 또는 Yahoo | 5m |

**캐시**: `macro:snapshot` 5m TTL

---

### 7.6 Layer 6 — Attention & Edge

**Endpoint**: `GET /api/v1/market/attention/{ticker}`

**컴포넌트**: `Layer6AttentionService`

**데이터 소스**:

| 필드 | 외부 API | 캐시 TTL |
|---|---|---|
| `social_velocity_zscore` | StockTwits API `/streams/symbol/{ticker}` + Reddit Pushshift | 5m |
| `search_trend_acceleration` | SerpAPI Google Trends (또는 pytrends 별도 마이크로서비스) | 15m |
| `sentiment_momentum` | StockTwits sentiment + Reddit comment score delta | 5m |
| `options_unusual_activity_score` | CBOE LiveVol / Tradier unusual options flow | 5m |

**정규화**: 각 raw → z-score 변환 후 0~100 스케일

---

## 8. Final AI Agent — Trade Decision

### 8.1 책임

Layer 0~6 의 모든 시장 컨텍스트 + 뉴스 요약을 하나의 LLM 호출로 종합하여 **매매 결정**을 산출한다. 구 AI Agent 2 를 완전 대체한다.

### 8.2 컴포넌트: `FinalAiAgentClient`

#### Input DTO: `TradeDecisionRequest`

```java
public record TradeDecisionRequest(
    String newsId,
    String ticker,
    String summary,
    double summaryScore,
    String newsCategory,
    String newsSentiment,
    Map<String, Object> layerScoresRaw,    // layer0~6 raw + normalized 모두 포함
    double freshnessDecayFactor,
    double baseScore,                      // OutputAggregator.computeBaseScore 결과
    double currentPrice,                   // Layer4.quote 재사용
    double atrPct,                         // Layer4 realized_vol_5min 재사용
    VirtualAccountSnapshot aiAccount      // AI 현재 가용 자본, 오픈 포지션
) {}
```

#### Output DTO: `TradeDecisionResponse`

```java
public record TradeDecisionResponse(
    Action action,               // BUY | SELL | HOLD
    Double stopLossPrice,        // HOLD 시 null
    Double takeProfitPrice,      // HOLD 시 null
    Double positionPctOfCapital, // HOLD 시 0.0
    String rationale,
    Double confidence
) {}
```

### 8.3 LLM 호출

```java
@Async("aiExecutor")
public CompletableFuture<TradeDecisionResponse> decide(TradeDecisionRequest req) {
    String systemPrompt = """
        너는 단기 퀀트 트레이더다. 다음 제약을 반드시 준수하라:
        - 손절가는 ATR×1.5 이상 여유를 두어야 한다.
        - 진입 비율(position_pct_of_capital)은 0~25% 범위 이내.
        - BUY: stop_loss < entry_price < take_profit 순서를 지켜야 한다.
        - SELL(숏): take_profit < entry_price < stop_loss 순서.
        - 확신이 없으면 HOLD 로 응답하고 가격 필드를 null 로 설정하라.
        - 반드시 JSON 형식으로만 응답하라.
        """;

    return aiWebClient.post()
        .uri("/v1/messages")
        .bodyValue(buildDecisionPayload(systemPrompt, req))
        .retrieve()
        .bodyToMono(LlmRawResponse.class)
        .map(this::parseAndValidate)
        .timeout(Duration.ofSeconds(6))
        .retry(1)
        .onErrorReturn(fallbackHold())
        .toFuture();
}
```

### 8.4 Spring 측 검증 (`parseAndValidate`)

```
1. action == HOLD → stop/tp null, pct == 0 검증
2. action == BUY  → stopLoss < currentPrice < takeProfitPrice
3. action == SELL → takeProfitPrice < currentPrice < stopLossPrice
4. positionPct ∈ (0, 25]
위반 시 → action = HOLD, rationale = "validation_failed:{reason}"
         + 메트릭: ai.decision.invalid{reason} +1
```

### 8.5 Fallback

```java
private TradeDecisionResponse fallbackHold() {
    return new TradeDecisionResponse(Action.HOLD, null, null, 0.0, "ai_unavailable", 0.0);
}
```

---

## 9. Pipeline Orchestrator

### 9.1 클래스: `SignalPipelineOrchestrator`

```java
@Service
@RequiredArgsConstructor
public class SignalPipelineOrchestrator {

    // 이벤트 수신 (Crawler 발행) 또는 REST 트리거
    @EventListener
    @Async("layerExecutor")
    public void onNewsIngested(NewsIngestedEvent event) {
        process(event.newsId());
    }

    public SignalResult process(String newsId) {
        NewsRaw news = newsRawRepo.findById(newsId).orElseThrow();

        // Step 1: AI Agent 1 — 요약 + 임베딩
        SummaryResult summary = aiAgent1Client.summarize(news).get(8, SECONDS);

        // Step 2: Layer 0 — reject 시 즉시 종료
        Layer0Result l0;
        try {
            l0 = layer0Service.evaluate(news, summary);
        } catch (RejectedNewsException ex) {
            return SignalResult.rejected(newsId, ex.getReason());
        }

        // Step 3: Layer 1~6 병렬 호출
        CompletableFuture<Layer1Result> f1 = layer1Service.evaluate(news.getTicker());
        CompletableFuture<Layer2Result> f2 = layer2Service.evaluate(news.getTicker());
        CompletableFuture<Layer3Result> f3 = layer3Service.evaluate(news.getTicker(), summary);
        CompletableFuture<Layer4Result> f4 = layer4Service.evaluate(news.getTicker());
        CompletableFuture<Layer5Result> f5 = layer5Service.evaluate();
        CompletableFuture<Layer6Result> f6 = layer6Service.evaluate(news.getTicker());

        CompletableFuture.allOf(f1, f2, f3, f4, f5, f6)
            .orTimeout(1500, MILLISECONDS).join();

        LayerScoresAggregate agg = LayerScoresAggregate.of(l0, f1.join(), f2.join(),
                                    f3.join(), f4.join(), f5.join(), f6.join());

        // Step 4: 기본 점수 계산 (CLAUDE.md §4.1)
        double baseScore = aggregator.computeBaseScore(agg, l0.freshnessDecayFactor());

        // Step 5: Final AI Agent — 매매 결정
        TradeDecisionRequest req = buildDecisionReq(news, summary, agg, baseScore, f4.join());
        TradeDecisionResponse decision = finalAiAgent.decide(req).get(6, SECONDS);

        // Step 6: 최종 결과 병합
        SignalResult result = aggregator.mergeAiDecision(agg, baseScore, decision, f4.join().currentPrice());

        // Step 7: DB 저장 + 가상 매매 체결 (트랜잭션)
        persist(result);

        return result;
    }
}
```

### 9.2 SLA

| 단계 | 목표 시간 |
|---|---|
| AI Agent 1 (요약) | ≤ 8s |
| Layer 0 | ≤ 500ms |
| Layer 1~6 (병렬) | ≤ 1.5s (allOf timeout) |
| Final AI Agent | ≤ 6s |
| **전체 E2E** | **≤ 12s** |

---

## 10. Output Aggregator

### 10.1 클래스: `OutputAggregator`

#### computeBaseScore — CLAUDE.md §4.1 가중합 그대로

```java
public double computeBaseScore(LayerScoresAggregate agg, double freshnessDecay) {
    double weighted = 0.18 * agg.layer1Score()
                    + 0.14 * agg.layer2Score()
                    + 0.24 * agg.layer3Score()
                    + 0.16 * agg.layer4Score()
                    + 0.14 * agg.layer5Score()
                    + 0.14 * agg.layer6Score();
    // 가중치 합 = 1.00 (검증됨)
    return freshnessDecay * weighted;
}
```

#### routeHorizon — CLAUDE.md §4.2

```java
public Map<String, HorizonSignal> routeHorizon(LayerScoresAggregate agg) {
    return Map.of(
        "1h", calcHorizon(agg.layer4Score() * 0.6 + agg.layer6Score() * 0.4),
        "1d", calcHorizon(agg.layer3Score() * 0.6 + agg.layer4Score() * 0.4),
        "3d", calcHorizon(agg.layer2Score() * 0.6 + agg.layer5Score() * 0.4)
    );
}
```

#### mergeAiDecision

```java
public SignalResult mergeAiDecision(LayerScoresAggregate agg, double baseScore,
                                    TradeDecisionResponse decision, double currentPrice) {
    // 점수 ↔ action 일관성 체크
    boolean scoreActionConflict = isConflict(baseScore, decision.action());
    double confidence = decision.confidence() * (scoreActionConflict ? 0.7 : 1.0);

    // 절대가 → pct 변환 (risk_management 하위 호환)
    double slPct = decision.stopLossPrice() != null
        ? (decision.stopLossPrice() - currentPrice) / currentPrice * 100 : 0;
    double tpPct = decision.takeProfitPrice() != null
        ? (decision.takeProfitPrice() - currentPrice) / currentPrice * 100 : 0;

    Signal signal = Signal.fromScore(baseScore);   // CLAUDE.md §5 표

    return SignalResult.builder()
        .baseScore(baseScore)
        .signal(signal)
        .confidence(confidence)
        .aiDecision(decision)
        .stopLossPct(slPct)
        .takeProfitPct(tpPct)
        .layerScores(agg.toScoreMap())
        .timeHorizon(routeHorizon(agg))
        .build();
}
```

### 10.2 Signal Enum (CLAUDE.md §5)

```java
public enum Signal {
    STRONG_BUY(85, 100),
    BUY(70, 84),
    HOLD(55, 69),
    SELL(40, 54),
    STRONG_SELL(0, 39);

    public static Signal fromScore(double score) { ... }
}
```

---

## 11. AI Virtual Trading (Prototype)

### 11.1 개요

- **AI 단일 가상계좌** (`virtual_account.owner_id = 'AI_SYSTEM'`) 만 운영
- 관리자가 AI 계좌에 가상 초기자금 충전
- AI 는 파이프라인 시그널의 `ai_decision.action == BUY` 일 때 자동으로 long 포지션 진입
- `PositionMonitorScheduler` 가 30초마다 손절/익절 자동 청산
- 사용자는 모든 AI 매매 정보(`/api/v1/virtual-account/ai/*`) 를 **조회만** 가능

> **프로토타입 매매 방향 정책**: long-only.
> AI 의 `ai_decision.action ∈ {BUY, HOLD}` 만 실행한다. SELL 응답은 메트릭만 카운트하고 무시.
> v1.4 에서 SELL(기존 long 청산) 또는 short 진입 도입 예정.

### 11.2 도메인 모델 (ERD 와 일치)

#### `virtual_account` — AI 단일 row

| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | BIGSERIAL PK | |
| owner_id | VARCHAR(100) | `'AI_SYSTEM'` 고정 (UNIQUE) |
| initial_capital | NUMERIC(18,2) | 최초 지급 금액 (충전 시 누계) |
| cash_balance | NUMERIC(18,2) | 현재 가용 현금 |
| created_at | TIMESTAMPTZ | |

> v1.2 의 `owner_type` 컬럼은 **삭제** (사용자 계좌 미운영).

#### `virtual_position`

| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | BIGSERIAL PK | |
| account_id | BIGINT FK → virtual_account | |
| ticker | VARCHAR(20) | |
| qty | NUMERIC(18,4) | 수량 |
| entry_price | NUMERIC(18,4) | 진입가 (슬리피지 포함) |
| stop_loss | NUMERIC(18,4) | 손절가 |
| take_profit | NUMERIC(18,4) | 익절가 |
| status | VARCHAR(10) | `OPEN` \| `CLOSED` |
| opened_at | TIMESTAMPTZ | |
| closed_at | TIMESTAMPTZ | |
| exit_price | NUMERIC(18,4) | 청산가 |
| realized_pnl | NUMERIC(18,4) | 실현 손익 |
| source_news_id | VARCHAR(100) | 시그널 출처 |

> long-only 프로토타입이므로 별도 `side` 컬럼 없음. 모든 row 는 long.

#### `virtual_trade_log`

| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | BIGSERIAL PK | |
| account_id | BIGINT FK | |
| ticker | VARCHAR(20) | |
| action | VARCHAR(20) | `BUY` \| `STOP_HIT` \| `TP_HIT` (프로토타입에서 사용하는 3종) |
| price | NUMERIC(18,4) | 체결가 |
| qty | NUMERIC(18,4) | |
| fee | NUMERIC(18,4) | 수수료 (10bps) |
| timestamp | TIMESTAMPTZ | |
| source_news_id | VARCHAR(100) | |

> ERD 의 `SELL` 값은 enum 자체에는 존재하지만 프로토타입에서는 사용되지 않음 (v1.4 청산 매매 도입 시 활성화).

### 11.3 관리자 AI 자금 충전

```java
// POST /api/v1/admin/virtual-account/deposit
// @PreAuthorize("hasRole('ADMIN')")
@Transactional
public VirtualAccount deposit(BigDecimal amount) {
    VirtualAccount ai = accountRepo.findByOwnerId("AI_SYSTEM").orElseThrow();
    ai.setCashBalance(ai.getCashBalance().add(amount));
    ai.setInitialCapital(ai.getInitialCapital().add(amount));   // 누적 출자금
    return ai;
}
```

**AI 계정 자동 생성**: `@EventListener(ApplicationReadyEvent.class)` → 부팅 시 `owner_id = 'AI_SYSTEM'` row 가 없으면 잔고 0 으로 생성

### 11.4 AI 자동 체결 (BUY 진입)

```java
@Service
public class VirtualTradeEngine {

    @Transactional
    public void executeAiDecision(TradeDecisionResponse decision,
                                  String ticker, double currentPrice) {
        // 프로토타입: BUY 만 처리, SELL/HOLD 는 무시
        if (decision.action() != Action.BUY) {
            if (decision.action() == Action.SELL)
                meterRegistry.counter("ai.decision.sell_ignored").increment();
            return;
        }

        VirtualAccount acc = accountRepo.findByOwnerIdWithLock("AI_SYSTEM");

        // long 진입 슬리피지 +0.05%
        double fillPrice = currentPrice * 1.0005;

        // qty = floor(cash × pct% / fillPrice)
        long qty = (long) Math.floor(
            acc.getCashBalance().doubleValue() * (decision.positionPctOfCapital() / 100.0)
            / fillPrice);

        if (qty <= 0) return;

        double cost = qty * fillPrice;
        double fee  = cost * 0.001;   // 10bps

        if (acc.getCashBalance().doubleValue() < cost + fee)
            throw new InsufficientFundsException(acc.getCashBalance(), cost + fee);

        acc.setCashBalance(acc.getCashBalance().subtract(BigDecimal.valueOf(cost + fee)));

        VirtualPosition pos = VirtualPosition.builder()
            .accountId(acc.getId())
            .ticker(ticker)
            .qty(BigDecimal.valueOf(qty))
            .entryPrice(BigDecimal.valueOf(fillPrice))
            .stopLoss(BigDecimal.valueOf(decision.stopLossPrice()))
            .takeProfit(BigDecimal.valueOf(decision.takeProfitPrice()))
            .status(PositionStatus.OPEN)
            .build();

        positionRepo.save(pos);
        tradeLogRepo.save(TradeLog.of(acc.getId(), pos, TradeAction.BUY, fillPrice, qty, fee));
    }
}
```

### 11.5 손절/익절 자동 청산 워커 (long-only)

```java
@Component
public class PositionMonitorScheduler {

    @Scheduled(fixedDelay = 30_000)
    public void monitor() {
        List<VirtualPosition> openPositions = positionRepo.findAllByStatus(OPEN);

        // ticker 단위 일괄 현재가 조회 (Polygon batch)
        Map<String, Double> prices = fetchCurrentPrices(
            openPositions.stream().map(VirtualPosition::getTicker).distinct().toList());

        openPositions.forEach(pos -> {
            double price = prices.getOrDefault(pos.getTicker(), 0.0);
            if (price <= 0) return;

            // long-only 프로토타입: stop_loss < entry < take_profit
            boolean stopHit = price <= pos.getStopLoss().doubleValue();
            boolean tpHit   = price >= pos.getTakeProfit().doubleValue();

            if (stopHit)      tradeEngine.close(pos, price, TradeAction.STOP_HIT);
            else if (tpHit)   tradeEngine.close(pos, price, TradeAction.TP_HIT);
        });
    }
}
```

`tradeEngine.close()` 는 청산가에 슬리피지 -0.05% 적용 → `realized_pnl` 계산 → `cash_balance` 환입 → `virtual_trade_log` 에 `STOP_HIT`/`TP_HIT` 기록.

### 11.6 가격 데이터 일관성

- 모든 체결가는 **Layer 4 가 캐시한 `quote:{ticker}` (Polygon, 1m TTL)** 를 그대로 재사용
- 시그널 발생 시점과 체결 시점의 가격 데이터 소스 일치 보장

---

## 12. JPA Entity / DB Schema

총 **19개 테이블** (CLAUDE.md 13개 + 본 명세 6개 신규)

### 12.1 테이블 목록

| 테이블 | 출처 | 핵심 변경 |
|---|---|---|
| `news_raw` | CLAUDE.md | |
| `news_summary` | CLAUDE.md | `embedding_vector` 컬럼 추가 (vector 타입 or jsonb) |
| `news_cluster` | CLAUDE.md | |
| `layer0_prefilter` | CLAUDE.md | |
| `layer1_positioning` | CLAUDE.md | |
| `layer2_expectation` | CLAUDE.md | |
| `layer3_event_surprise` | CLAUDE.md | |
| `layer4_microstructure` | CLAUDE.md | |
| `layer5_macro` | CLAUDE.md | |
| `layer6_attention` | CLAUDE.md | |
| `final_trade_signal` | CLAUDE.md | `ai_decision` JSONB 컬럼 추가 |
| `risk_management_log` | CLAUDE.md | `stop_loss_price`, `take_profit_price` 컬럼 추가 |
| `historical_analog_library` | CLAUDE.md | |
| `backtest_result` | CLAUDE.md | |
| **`news_source_registry`** | §5 신규 | |
| **`virtual_account`** | §11 신규 | |
| **`virtual_position`** | §11 신규 | |
| **`virtual_trade_log`** | §11 신규 | |
| **`leaderboard_snapshot`** | §11 신규 | |
| **`app_user`** | §13 신규 | OAuth2 사용자 매핑 |

### 12.2 주요 인덱스

```sql
-- 뉴스 조회 성능
CREATE INDEX idx_news_raw_published ON news_raw(published_at DESC, source);
CREATE INDEX idx_news_raw_ticker ON news_raw(ticker, published_at DESC);

-- 포지션 모니터
CREATE INDEX idx_vposition_status ON virtual_position(status) WHERE status = 'OPEN';
CREATE INDEX idx_vposition_account ON virtual_position(account_id, status);

-- 거래 내역
CREATE INDEX idx_vtrade_log_account ON virtual_trade_log(account_id, timestamp DESC);

-- 시그널 조회
CREATE INDEX idx_signal_ticker ON final_trade_signal(ticker, created_at DESC);
```

### 12.3 기술 설정

- **JSONB**: `final_trade_signal.ai_decision`, `final_trade_signal.time_horizon` → `@JdbcTypeCode(SqlTypes.JSON)` (Hibernate 6)
- **마이그레이션**: Flyway
  - `V1__init_news_tables.sql`
  - `V2__init_layer_tables.sql`
  - `V3__init_signal_tables.sql`
  - `V4__init_news_source_registry.sql`
  - `V5__init_virtual_trading.sql`

---

## 13. 보안

### 13.1 인증 흐름

```
사용자 → Google OAuth2 로그인 → app_user 테이블 upsert
       → JWT 발급 (HS256, jjwt, 24h 유효)
       → 이후 API 요청 시 Authorization: Bearer {token}
```

### 13.2 `app_user` 테이블

| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | VARCHAR(100) PK | Google sub (subject) |
| email | VARCHAR(255) | |
| nickname | VARCHAR(100) | |
| role | VARCHAR(20) | `ROLE_USER` \| `ROLE_ADMIN` |
| created_at | TIMESTAMPTZ | |

### 13.3 권한 매핑

| 역할 | 권한 |
|---|---|
| `ROLE_USER` | 시그널 조회, 가상 매매 주문, 리더보드 |
| `ROLE_ADMIN` | 위 + 가상자금 지급, 백테스트 트리거, AI 모델 설정 변경 |

```java
@PreAuthorize("hasRole('ADMIN')")
@PostMapping("/admin/virtual-account")
public ResponseEntity<?> createVirtualAccount(...) { ... }
```

### 13.4 내부 보안

- **AI 가상매매 endpoint** 는 외부 REST 없음 — `ApplicationEventPublisher` 내부 이벤트로만 트리거
- **외부 API 키**: `@ConfigurationProperties("external.api.*")` + 환경변수 (`POLYGON_API_KEY`, `LLM_API_KEY` 등) — 코드/Git 하드코딩 금지, 운영 환경 Vault 사용 권장

---

## 14. 캐시 전략 (Redis Key Schema)

| Key Pattern | TTL | 용도 | 공유 범위 |
|---|---|---|---|
| `news:url_hash` (SET) | 14d | Crawler URL 1차 dedup | Crawler |
| `news:cluster:{ticker}:{yyyyMMdd}` (SET) | 24h | Layer 0 의미 dedup cluster_id | Layer 0 |
| `liquidity:{ticker}` | 15m | 시총/ADV | Layer 0 |
| `robots:{sourceId}` | 24h | robots.txt 캐시 | Crawler |
| `vix:term` | 1m | Layer 1 VIX term structure | Layer 1 |
| `fear_greed` | 30m | Layer 1 Fear&Greed | Layer 1 |
| `expectation:{ticker}` | 1h | Layer 2 consensus | Layer 2 |
| `macro:snapshot` | 5m | Layer 5 (ticker 무관, 전역 공유) | Layer 5 |
| `quote:{ticker}` | **1m** | Layer 4 현재가 → **Virtual Trade Engine 공유** | Layer 4, §11 |
| `signal:{news_id}` | 1h | 동일 뉴스 재요청 캐시 | Pipeline |
| `leaderboard:rank:{period}` | 5m | 리더보드 응답 캐시 | Leaderboard |

---

## 15. 비동기 / 동시성 설정

### 15.1 TaskExecutor 3개 Bean

```java
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("crawlerExecutor")
    public TaskExecutor crawlerExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(4); e.setMaxPoolSize(8); e.setQueueCapacity(200);
        e.setThreadNamePrefix("crawler-"); return e;
    }

    @Bean("layerExecutor")
    public TaskExecutor layerExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(10); e.setMaxPoolSize(30); e.setQueueCapacity(100);
        e.setThreadNamePrefix("layer-"); return e;
    }

    @Bean("aiExecutor")
    public TaskExecutor aiExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(4); e.setMaxPoolSize(8); e.setQueueCapacity(50);
        e.setThreadNamePrefix("ai-"); return e;
    }
}
```

### 15.2 WebClient Pool 분리

```java
// 시세·매크로 API용 (Layer 0~6)
@Bean("marketWebClient")
public WebClient marketWebClient() {
    ConnectionProvider pool = ConnectionProvider.builder("market")
        .maxConnections(200).pendingAcquireTimeout(Duration.ofSeconds(3)).build();
    return WebClient.builder()
        .clientConnector(new ReactorClientHttpConnector(HttpClient.create(pool))).build();
}

// LLM API용 (AI Agent 1, Final Agent) — 타임아웃 분리
@Bean("aiWebClient")
public WebClient aiWebClient() {
    return WebClient.builder()
        .clientConnector(new ReactorClientHttpConnector(
            HttpClient.create().responseTimeout(Duration.ofSeconds(10))))
        .baseUrl(llmBaseUrl)
        .defaultHeader("Authorization", "Bearer " + llmApiKey)
        .build();
}
```

---

## 16. 모니터링

### 16.1 Actuator

```
/actuator/health        → UP/DOWN (DB, Redis, ES 상태 포함)
/actuator/prometheus    → Prometheus 스크레이핑
```

### 16.2 Custom Metrics

```java
// Layer 처리 시간
Timer.builder("pipeline.layer.duration").tag("layer", "layer1").register(registry);

// Layer 0 reject 사유
Counter.builder("pipeline.reject.count").tag("reason", reason).register(registry);

// AI 매매 결정 분포
Counter.builder("ai.decision.action").tag("action", action.name()).register(registry);

// AI 결정 검증 실패 (HOLD 강등)
Counter.builder("ai.decision.invalid").tag("reason", reason).register(registry);

// 가상 계좌 실시간 자산
Gauge.builder("virtual.account.equity", account, a -> calcEquity(a).doubleValue())
     .tag("owner_type", ownerType).register(registry);
```

### 16.3 로깅

```xml
<!-- logback-spring.xml -->
<encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
```

MDC 주입: `news_id`, `ticker`, `account_id` — 요청 진입 시 Filter 에서 설정

---

## 17. 테스트 전략

### 17.1 단위 테스트 (JUnit 5 + Mockito)

| 대상 | 검증 항목 |
|---|---|
| `ScoreCalculator` | CLAUDE.md §4.1 가중치 합 = 1.0, Decay 공식 |
| `Layer0PrefilterService` | reject 조건 3가지, decay 값 |
| `OutputAggregator.mergeAiDecision` | score-action 충돌 penalty, 절대가→pct 환산 |
| `VirtualTradeEngine` | qty 계산 (슬리피지, 수수료), HOLD 시 체결 없음 |
| `FinalAiAgentClient.parseAndValidate` | BUY/SELL 부등식 위반 → HOLD 강등 |

### 17.2 통합 테스트

- `@SpringBootTest` + **Testcontainers** (PostgreSQL 16, Redis 7, Elasticsearch 8)
- 파이프라인 E2E: `news_raw` 적재 → 시그널 생성 → `virtual_trade_log` 삽입 확인

### 17.3 외부 API 모킹

- **WireMock** 스텁 파일: `src/test/resources/wiremock/`
  - `polygon_quote_NVDA.json`, `fred_dgs10.json`, `stocktwits_NVDA.json` 등
- `@Qualifier("marketWebClient")` → 테스트 프로필에서 WireMock URL 으로 교체

### 17.4 LLM 모킹

```java
@Profile("test")
@Primary
@Component
public class MockFinalAiAgentClient extends FinalAiAgentClient {
    // 시나리오: BUY / SELL / HOLD / invalid(부등식 위반) 강제 반환
}
```

### 17.5 백테스트

`BacktestRunner` (별도 Spring Batch Job):
1. 과거 1년 `news_raw` 순차 재처리
2. AI 가상계좌에 파이프라인 시그널 적용
3. 실제 Polygon 일봉 데이터로 PnL 산정
4. `backtest_result` 테이블 적재

---

## 18. 외부 API 의존성 매트릭스 및 추가 의존성

### 18.1 외부 API

| 파이프라인 단계 | API / 서비스 | 용도 | Fallback |
|---|---|---|---|
| Crawler | Reuters/Bloomberg/Yahoo RSS, SEC EDGAR | 뉴스 수집 | NewsAPI |
| AI Agent 1 | Anthropic Claude / OpenAI | 요약 + 임베딩 | 다른 LLM 벤더로 전환 |
| Layer 0, 4 | **Polygon.io** (1순위) | 시총/ADV/분봉/quote | Finnhub |
| Layer 1 | CBOE, Tradier | VIX, IV, 옵션 | yfinance-proxy 마이크로서비스 |
| Layer 2 | FMP, SEC EDGAR | EPS consensus, Form 4 | Zacks |
| Layer 3 | 내부 DB | historical analog | — |
| Layer 5 | FRED API | 미 10y, DXY, NFCI | ECB Statistical Data Warehouse |
| Layer 6 | StockTwits, Reddit Pushshift, SerpAPI | 관심도 | NewsAPI |
| Final AI Agent | Anthropic Claude / OpenAI | 매매 결정 | HOLD fallback |

### 18.2 build.gradle 추가 필요 의존성

현재 `build.gradle` 에 없는 의존성:

```groovy
// News Crawler
implementation 'org.jsoup:jsoup:1.17.2'
implementation 'com.rometools:rome:2.1.0'

// Resilience (CircuitBreaker, RateLimiter)
implementation 'io.github.resilience4j:resilience4j-spring-boot3:2.2.0'

// DB Migration
implementation 'org.flywaydb:flyway-core'

// JSON 타입 (JSONB) — Hibernate 6 기본 지원이나 명시적 사용 시
implementation 'io.hypersistence:hypersistence-utils-hibernate-63:3.7.3'
```

---

## 19. 구현 마일스톤

| 마일스톤 | 기간 | 내용 |
|---|---|---|
| **M1** | 1주 | Domain Entity 19개 + Flyway 마이그레이션 + OAuth2/JWT + `/actuator/health` |
| **M2** | 1주 | News Crawler (RSS/Jsoup) + AI Agent 1 + `news_raw` E2E (Crawler → DB) |
| **M3** | 1주 | Layer 0 + Layer 4 (Polygon 연동, `quote:{ticker}` 캐시) — 가격 데이터 공유 핵심 |
| **M4** | 2주 | Layer 1/2/3/5/6 — 한 Layer 씩 PR, WireMock 스텁 포함 |
| **M5** | 1주 | Final AI Agent + OutputAggregator + SignalPipelineOrchestrator 병렬화 E2E |
| **M6** | 1주 | VirtualTradeEngine + PositionMonitorScheduler + Leaderboard API |
| **M7** | 1주 | BacktestRunner + Prometheus Custom Metric + 통합 테스트 + 부하 테스트 (k6) |
| **총합** | **8주** | |

---

*본 문서는 CLAUDE.md v1.1 을 기반으로 작성된 v1.2 구현 명세서입니다. CLAUDE.md 의 개념 정의(Layer 점수 공식, 신호 매핑 표, 가중치)는 그대로 유지됩니다.*
