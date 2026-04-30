# 📘 News-Market Synchronization API Specification
### Version: 1.1 (Revised)
### Last Updated: 2026-04-29
### Changelog from v1.0:
- 중복 지표 제거 (Layer 1/4 변동성, Layer 6 SNS)
- 신규 Layer 추가: News Freshness & Deduplication (Layer 0)
- Risk Management Output 추가
- Liquidity Pre-filter 추가
- Layer 7을 Output Aggregator로 재정의
- 점수 스케일 통일 (0~100)

---

## 1. System Overview

### 1.1 Pipeline (Revised)

```text
[News Crawling Engine]
        ↓
[AI Agent 1 : News Summarizer + Deduplicator]
        ↓
[AI Agent 2 : News Impact Scoring Engine]
        ↓
[Layer 0 : News Freshness & Liquidity Pre-filter]   ← 신규
        ↓
[6-Layer Market Context Filter (Layer 1~6)]
        ↓
[Output Aggregator : Final Score + Horizon Routing + Risk Sizing]
```

### 1.2 Core Objective
단순 뉴스 감성 판단이 아닌,
> **"해당 뉴스가 현재 시장 환경에서 실제 가격 상승/하락으로 번역될 확률"** 과
> **"진입 시 적정 포지션/손절 레벨"** 을 동시에 산출한다.

### 1.3 Score Scale Convention (통일)
- **모든 Layer의 출력 스코어는 0 ~ 100 정수 스케일로 정규화**
- 원시 데이터(raw)는 각 응답에 별도 필드로 보존
- `confidence`만 0.0 ~ 1.0 float 유지

---

## 2. Input / Output Definition

### 2.1 Input Payload
```json
{
  "ticker": "NVDA",
  "news_id": "NEWS_20260429_001",
  "headline": "NVIDIA announces new AI datacenter chip",
  "body_summary": "...",
  "summary_score": 0.87,
  "summary_score_definition": "AI agent가 산출한 뉴스 본문의 정보 밀도(0~1). 1.0=중대 신규 정보, 0.0=재탕/노이즈",
  "news_sentiment": "positive",
  "news_category": "product_launch",
  "source": "Reuters",
  "published_at": "2026-04-29T09:30:00Z",
  "ingested_at": "2026-04-29T09:31:12Z"
}
```

### 2.2 Output Payload (Risk 정보 추가)
```json
{
  "ticker": "NVDA",
  "news_id": "NEWS_20260429_001",
  "final_score": 82.4,
  "signal": "STRONG_BUY",
  "confidence": 0.91,
  "time_horizon": {
    "1h":  { "signal": "BUY",        "expected_move_pct": 0.8 },
    "1d":  { "signal": "STRONG_BUY", "expected_move_pct": 2.4 },
    "3d":  { "signal": "HOLD",       "expected_move_pct": 1.1 }
  },
  "risk_management": {
    "suggested_position_pct": 3.5,
    "stop_loss_pct": -1.8,
    "take_profit_pct": 3.6,
    "max_holding_hours": 24
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
  "rejection_reason": null
}
```

---

## 3. Layer Definitions (Revised)

### 🆕 Layer 0. News Freshness & Liquidity Pre-filter

**Objective**: 뉴스가 너무 오래되었거나, 종목이 거래하기에 너무 작은 경우 **즉시 reject**하여 하위 Layer 연산 비용을 절감.

#### Endpoint
```
POST /api/v1/prefilter
```

#### Response Schema
```json
{
  "news_age_minutes": 7.5,
  "duplicate_cluster_id": "CLUSTER_NVDA_20260429_AI_CHIP",
  "is_primary_source": true,
  "duplicate_count_in_cluster": 12,
  "market_cap_usd": 2800000000000,
  "avg_daily_volume_usd": 18500000000,
  "is_tradable": true,
  "freshness_decay_factor": 0.94
}
```

#### 처리 규칙
| 조건 | Action |
|---|---|
| `news_age_minutes > 60` AND `duplicate_count_in_cluster > 1` | **REJECT** (이미 가격 반영됨) |
| `is_primary_source == false` | 점수 0.7배 디스카운트 |
| `market_cap_usd < 500M` 또는 `avg_daily_volume < 10M` | **REJECT** (유동성 부족) |
| 위 모든 조건 통과 | 다음 Layer 진행 |

#### Decay 공식
```
freshness_decay_factor = exp(-news_age_minutes / 30)
```
→ 30분 경과 시 점수 약 37%로 감쇠

---

### Layer 1. Psychology & Positioning Filter (변동성 중복 제거)

#### Endpoint
```
GET /api/v1/market/positioning/{ticker}
```

#### Response Schema (수정)
```json
{
  "vix_term_structure": -1.7,
  "put_call_ratio": 1.24,
  "short_float_ratio": 11.3,
  "days_to_cover": 4.1,
  "fear_greed_composite": 38,
  "implied_realized_vol_spread": 6.4
}
```
**제거된 필드**: `vix` (단일값) → `vix_term_structure`로 대체 (구조 정보가 더 풍부)
**유지 사유**: `implied_realized_vol_spread`는 IV vs HV 차이로, Layer 4의 `intraday_volatility`(절대값)와 의미가 다름

---

### Layer 2. Expectation Revision Filter

#### Endpoint
```
GET /api/v1/market/expectation/{ticker}
```

#### Response Schema (필드 추가)
```json
{
  "eps_revision_trend_30d": 0.12,
  "target_price_revision_30d": 0.08,
  "insider_activity_score": 71,
  "analyst_rating_delta_30d": 2,
  "expectation_gap": 5.7,
  "days_to_next_earnings": 18
}
```
**추가 사유**: `days_to_next_earnings` → 실적 발표 임박 시 모든 뉴스의 영향력이 증폭됨

---

### Layer 3. Event Surprise Filter

#### Endpoint
```
POST /api/v1/market/event-surprise
```

#### Response Schema (변경 없음)
```json
{
  "surprise_zscore": 2.4,
  "historical_analog_return_1d": 3.1,
  "historical_winrate": 0.72,
  "base_rate_sector": 0.66,
  "sample_size": 47
}
```
**추가 사유**: `sample_size` → 과거 유사 사례 수가 적으면 confidence 하향 조정

---

### Layer 4. Microstructure & Price Action Filter (중복 정리)

#### Endpoint
```
GET /api/v1/market/microstructure/{ticker}
```

#### Response Schema (수정)
```json
{
  "volume_shock_zscore": 3.4,
  "bollinger_squeeze": true,
  "bid_ask_spread_bps": 3.0,
  "realized_vol_5min": 2.1,
  "sector_breadth": 0.69,
  "distance_from_52w_high_pct": -2.3,
  "vwap_deviation_pct": 0.8
}
```
**변경**: `intraday_volatility` → `realized_vol_5min` (Layer 1과 명확히 구분)
**추가**: `vwap_deviation_pct` → 기관 평단 대비 위치, 단기 모멘텀의 핵심 지표

---

### Layer 5. Macro & Cross-Asset Filter

#### Endpoint
```
GET /api/v1/market/macro
```

#### Response Schema (변경 없음)
```json
{
  "us10y_yield_change_bps": 14,
  "dxy_change_pct": -0.32,
  "nfci": 0.11,
  "sector_etf_corr_30d": 0.74,
  "index_futures_direction": "UP"
}
```

---

### Layer 6. Attention & Edge Filter (SNS 중복 통합)

#### Endpoint
```
GET /api/v1/market/attention/{ticker}
```

#### Response Schema (수정)
```json
{
  "social_velocity_zscore": 2.9,
  "search_trend_acceleration": 1.8,
  "sentiment_momentum": -0.3,
  "options_unusual_activity_score": 65
}
```
**제거**: `reddit_mentions` (절대값) → `social_velocity_zscore`에 이미 반영됨
**변경**: `social_sentiment_decay` → `sentiment_momentum` (의미 명확화)
**추가**: `options_unusual_activity_score` → 스마트머니의 선행 베팅 신호

---

## 4. Output Aggregator (구 Layer 7)

> Layer 7은 **연산 Layer가 아닌 Output Aggregator**로 재분류.

### 4.1 Final Score 공식
```
final_score = freshness_decay_factor × (
    0.18 × layer1_psychology_positioning +
    0.14 × layer2_expectation_revision +
    0.24 × layer3_event_surprise +
    0.16 × layer4_microstructure +
    0.14 × layer5_macro_cross_asset +
    0.14 × layer6_attention_edge
)
```
가중치 검증: 0.18 + 0.14 + 0.24 + 0.16 + 0.14 + 0.14 = **1.00** ✓

### 4.2 Horizon Routing 규칙
| Horizon | 핵심 의존 Layer |
|---|---|
| 1h | Layer 4 (microstructure) + Layer 6 (attention) |
| 1d | Layer 3 (surprise) + Layer 4 (microstructure) |
| 3d | Layer 2 (expectation) + Layer 5 (macro) |

### 4.3 Risk Sizing 공식 (Kelly 변형)
```
suggested_position_pct = min(
    base_position × (final_score / 100) × confidence,
    max_single_position_cap
)

stop_loss_pct = -1.5 × ATR_pct
take_profit_pct = stop_loss_pct × |reward_risk_ratio|
```

---

## 5. Final Trade Signal Mapping

| Final Score | Signal | 권장 행동 |
|---|---|---|
| 85 ~ 100 | STRONG_BUY | 풀 사이즈 진입 |
| 70 ~ 84 | BUY | 절반 사이즈 |
| 55 ~ 69 | HOLD | 관망 |
| 40 ~ 54 | SELL | 보유분 일부 청산 |
| 0 ~ 39 | STRONG_SELL | 전량 청산 / 숏 검토 |

---

## 6. Recommended DB Tables (확장)

| Table | 비고 |
|---|---|
| news_raw | 원본 뉴스 |
| news_summary | AI Agent 1 출력 |
| news_cluster | **신규** - 중복 뉴스 그룹핑 |
| layer0_prefilter | **신규** |
| layer1_positioning | |
| layer2_expectation | |
| layer3_event_surprise | |
| layer4_microstructure | |
| layer5_macro | |
| layer6_attention | |
| final_trade_signal | |
| risk_management_log | **신규** - 손절/익절 추적 |
| historical_analog_library | |
| backtest_result | **신규** - 시그널 성과 검증 |

---

## 7. Future Expansion
- LLM Reinforcement Feedback Loop (실제 수익률 기반 가중치 학습)
- Sector Specific Scoring Models (반도체/금융/바이오 등 섹터별 가중치)
- Intraday Streaming Alert Engine (Kafka/Redis Stream)
- Multi-asset Portfolio Optimizer (단일 종목 → 포트폴리오 단위)
- News Source Reliability Scoring (출처별 신뢰도 가중)