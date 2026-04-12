# 技術選型說明

本文件說明 Rate Limiting Service 各項技術選擇的原因與考量。

---

## 1. 核心框架：Spring Boot 3.5

| 考量點 | 選擇原因 |
|--------|----------|
| 生態成熟 | Spring 生態圈有完整的 Redis、MQ、JPA 整合方案 |
| 企業標準 | 多數企業後端使用 Spring，降低維護成本 |
| 自動配置 | Spring Boot 的 auto-configuration 減少樣板程式碼 |
| 測試支援 | 內建 MockMvc、@SpringBootTest，整合 Testcontainers 容易 |

**替代方案考量：**
- Micronaut / Quarkus：啟動快但生態較小，此專案不需要冷啟動優化
- 純 Java (Javalin, Spark)：太輕量，需自行整合各元件

---

## 2. 限流演算法：Fixed Window

| 演算法 | 優點 | 缺點 |
|--------|------|------|
| **Fixed Window** ✓ | 實作簡單、記憶體用量最低 | 視窗邊界可能有流量突波 |
| Sliding Window Log | 精確 | 記憶體用量高（需存每個請求時間戳） |
| Sliding Window Counter | 折衷方案 | 實作較複雜 |
| Token Bucket | 允許短期突發 | 需維護 token 狀態 |
| Leaky Bucket | 流量平滑 | 不允許任何突發 |

**選擇 Fixed Window 的理由：**

```
時間軸：|-------- 60s --------|-------- 60s --------|
請求：  [################]    [################]
                        ↑ 視窗邊界
```

1. **Redis 原生支援**：`INCR` + `EXPIRE` 就能實現，無需複雜 Lua
2. **O(1) 空間複雜度**：每個 API Key 只需一個計數器
3. **足夠應對多數場景**：邊界突波問題在實務中影響有限
4. **可觀測性佳**：計數器值直接反映當前使用量

---

## 3. 資料儲存：MySQL 8.0

| 用途 | 說明 |
|------|------|
| 持久化 | 儲存限流規則設定（apiKey, limit, windowSeconds） |
| 來源 | 作為快取的 Source of Truth |

**為什麼不用 Redis 儲存設定？**

```
設定資料特性：
- 寫入頻率：低（管理員偶爾調整）
- 讀取頻率：高（每次請求都需查詢）
- 重要性：高（遺失設定會影響業務）

→ 適合用 RDBMS 持久化 + 快取加速讀取
```

**替代方案考量：**
- PostgreSQL：功能更強但此場景不需要
- MongoDB：Schema-less 對結構化設定沒優勢

---

## 4. 計數器與快取：Redis 7

| 用途 | 實作方式 |
|------|----------|
| 請求計數 | `INCR` + `EXPIRE` (Lua Script 保證原子性) |
| 設定快取 | String 類型，TTL 同步視窗時間 |

**為什麼用 Redis？**

```
請求計數的需求：
1. 高併發寫入（每個請求都要 INCR）
2. 原子操作（避免 race condition）
3. 自動過期（視窗結束後清零）
4. 低延遲（<1ms）

→ Redis 是唯一滿足全部需求的選擇
```

**Lua Script 的必要性：**

```lua
-- 不用 Lua 的問題（兩個命令，非原子）：
INCR key        -- T1: count = 1
EXPIRE key 60   -- T2: 另一個請求在這之間進來，count = 2，但沒設 TTL

-- 用 Lua 保證原子性：
local count = redis.call('INCR', KEYS[1])
if count == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
return {count, redis.call('TTL', KEYS[1])}
```

---

## 5. 多層快取：Caffeine → Redis → MySQL

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Caffeine   │────▶│    Redis    │────▶│    MySQL    │
│   (L1)      │     │    (L2)     │     │    (L3)     │
│   ~1μs      │     │   ~1ms      │     │   ~10ms     │
│  JVM 內存   │     │   網路存取   │     │   磁碟 I/O  │
└─────────────┘     └─────────────┘     └─────────────┘
```

| 層級 | 技術 | 容量 | 延遲 | 用途 |
|------|------|------|------|------|
| L1 | Caffeine | 1000 條 | ~1μs | 熱點資料，減少網路開銷 |
| L2 | Redis | 無限制 | ~1ms | 分散式快取，跨實例共享 |
| L3 | MySQL | 無限制 | ~10ms | 持久化，Source of Truth |

**為什麼需要 L1 (Caffeine)？**

```
假設 QPS = 10,000：
- 無 L1：每秒 10,000 次 Redis 網路往返
- 有 L1：熱點 key 命中率 90%，只需 1,000 次 Redis 往返

→ 減少 90% 的網路開銷
```

**快取一致性策略：**

```
寫入流程：
1. 更新 MySQL
2. 刪除 Redis 快取
3. 發送 MQ 事件
4. 其他實例收到事件，刪除本地 Caffeine 快取

讀取流程：
Caffeine miss → Redis miss → MySQL → 回填 Redis → 回填 Caffeine
```

---

## 6. 訊息佇列：RocketMQ 5.1

| 用途 | 說明 |
|------|------|
| 非同步記錄 | 限流事件記錄不阻塞主流程 |
| 快取同步 | 設定變更時通知其他實例清除快取 |

**為什麼選 RocketMQ？**

| MQ | 優點 | 缺點 |
|----|------|------|
| **RocketMQ** ✓ | 高吞吐、延遲低、有管理介面 | 部署較複雜 |
| Kafka | 極高吞吐 | 對此場景過重 |
| RabbitMQ | 功能豐富、易用 | 吞吐量較低 |
| Redis Pub/Sub | 最簡單 | 不持久化，訊息可能遺失 |

**事件類型設計：**

```java
public enum EventType {
    RATE_LIMIT_EXCEEDED,  // 觸發限流時發送
    CONFIG_CREATED,       // 建立/更新設定時發送
    CONFIG_DELETED        // 刪除設定時發送
}
```

---

## 7. 失敗策略：Fail-Open

```
當 Redis 不可用時：

Fail-Open（選用）：
  請求 → Redis 失敗 → 允許通過 ✓
  理由：限流器不應成為系統單點故障

Fail-Closed（未選用）：
  請求 → Redis 失敗 → 拒絕請求 ✗
  理由：可能因限流器故障而中斷整個服務
```

**設計考量：**
- Rate Limiter 是保護機制，不是核心業務
- Redis 短暫故障時，允許流量通過比全部拒絕更好
- 搭配監控告警，快速發現並修復問題

---

## 8. HTTP 狀態碼設計

| 情境 | 狀態碼 | 原因 |
|------|--------|------|
| 請求允許 | 200 OK | 正常回應 |
| 超過限額 | 429 Too Many Requests | RFC 6585 標準 |
| 缺少 apiKey 參數 | 400 Bad Request | 缺少必要參數是客戶端錯誤 |
| 找不到設定 | 404 Not Found | 資源不存在 |
| 無設定規則 | 200 OK + allowed:true | 預設允許策略 |

**為什麼缺少 apiKey 是 400 而不是 401？**

```
401 Unauthorized：身份驗證失敗（認證問題）
400 Bad Request：請求格式錯誤（缺少必要參數）

apiKey 在此設計中是「查詢參數」而非「認證憑證」
→ 缺少必要參數 = 400 Bad Request
```

---

## 9. 測試策略

| 測試類型 | 工具 | 涵蓋範圍 |
|----------|------|----------|
| 單元測試 | JUnit 5 + Mockito | Service、RateLimiter 邏輯 |
| 整合測試 | Testcontainers | 完整 API 流程、Redis/MySQL 互動 |

**為什麼用 Testcontainers？**

```
傳統做法：
- Mock Redis/MySQL → 無法驗證真實行為
- 共用測試環境 → 測試互相干擾

Testcontainers：
- 每次測試啟動獨立容器
- 測試真實的 Redis/MySQL 行為
- 測試隔離，結果可重現
```

---

## 10. 分頁策略：Offset-Based

| 策略 | 優點 | 缺點 |
|------|------|------|
| **Offset-Based** ✓ | 直覺、支援跳頁 | 大數據集效能差 |
| Cursor-Based | 效能穩定 | 不支援跳頁 |

**選擇 Offset 的理由：**
- 限流規則數量通常不多（百~千級）
- 管理介面需要跳頁功能
- 實作簡單，Spring Data JPA 原生支援

---

## 11. 未來可優化項目 (Future Enhancements)

以下是目前設計中「知道可以做得更好，但在當前場景下尚未實作」的項目。記錄這些是為了說明技術債的存在，而非遺漏。

### 11.1 Redis Fail-Open 完整實作

**目前狀態：** 部分實作（RateLimiter 有處理 null，但 ConfigService 沒有 try-catch）

**應該要：**
```java
// ConfigService.getConfig() 應該要這樣
try {
    Map<Object, Object> redisData = redisTemplate.opsForHash().entries(...);
    if (!redisData.isEmpty()) {
        return Optional.of(fromRedisHash(key, redisData));
    }
} catch (Exception e) {
    log.warn("Redis failure, falling back to MySQL", e);
}
// Fallback to MySQL...
```

**為什麼還沒做：**
- 目前是單機開發環境，Redis 不太會掛
- 需要搭配監控告警才有意義
- 完整的 Fail-Open 需要考慮 Circuit Breaker pattern

**何時該做：**
- 上 Production 之前
- 當 Redis 開始有 HA 架構時

---

### 11.2 標準 Cache-Aside Pattern

**目前狀態：** 寫入時是「更新 Cache」而非「刪除 Cache」

```java
// 目前的做法 (Write-Through)
RateLimitConfig saved = repository.save(config);
saveToRedis(saved);  // 更新 Redis

// 標準 Cache-Aside 應該是
RateLimitConfig saved = repository.save(config);
redisTemplate.delete(key);  // 刪除 Redis，下次讀取時回填
```

**為什麼還沒改：**
- 設定變更頻率極低（管理員偶爾操作）
- 目前單實例，不一致窗口很小
- 兩種做法在此場景差異不大

**何時該做：**
- 多實例部署時
- 設定變更頻率變高時
- 需要強一致性保證時

**進階方案（更複雜但更可靠）：**
- 延遲雙刪 (Delayed Double Delete)
- 訂閱 MySQL Binlog (Canal/Debezium)

---

### 11.3 X-RateLimit-Reset Header 精確計算

**目前狀態：** 寫死 `+60` 秒

```java
// 目前
headers.set("X-RateLimit-Reset", String.valueOf(Instant.now().getEpochSecond() + 60));

// 應該要
headers.set("X-RateLimit-Reset", String.valueOf(Instant.now().getEpochSecond() + actualTtl));
```

**為什麼還沒做：**
- 需要在 `CheckResponse` 加上 `ttlSeconds` 欄位
- 需要從 `IncrementResult` 傳遞 TTL 到 Controller

**何時該做：**
- Client 需要精確的 retry 時間時
- 這是個 bug，應該盡快修復

---

### 11.4 全域例外處理補完

**目前狀態：** 只處理了業務異常，未處理基礎設施異常

```java
// 目前有處理
@ExceptionHandler(RateLimitExceededException.class)   // 429
@ExceptionHandler(ApiKeyRequiredException.class)      // 400
@ExceptionHandler(ConfigNotFoundException.class)      // 404
@ExceptionHandler(MethodArgumentNotValidException.class) // 400

// 還沒處理
@ExceptionHandler(DataAccessException.class)  // DB 錯誤 → 503
@ExceptionHandler(Exception.class)            // 兜底 → 500（隱藏 stack trace）
```

**為什麼還沒做：**
- 開發階段需要看到完整錯誤訊息
- 尚未定義錯誤回應的標準格式

**何時該做：**
- 上 Production 之前（避免洩漏內部資訊）

---

### 11.5 Rate Limiter 演算法升級

**目前狀態：** Fixed Window

**可升級為：**

| 演算法 | 優點 | 實作複雜度 |
|--------|------|------------|
| Sliding Window Counter | 消除邊界突波 | 中 |
| Token Bucket | 允許短期突發 | 中 |
| Sliding Window Log | 最精確 | 高（記憶體用量大） |

**為什麼還沒做：**
- Fixed Window 足以應付目前需求
- 邊界突波問題在實務中影響有限

**何時該做：**
- 當 2x burst 問題真的造成困擾時
- 有精確限流需求時（如計費系統）

---

### 11.6 可觀測性 (Observability)

**目前狀態：** 只有基本的 log

**應該加上：**

```java
// Micrometer Metrics
@Timed("rate.limit.check")
public CheckResponse check(String apiKey) { ... }

// 自訂指標
Counter.builder("rate.limit.blocked")
    .tag("apiKey", apiKey)
    .register(meterRegistry)
    .increment();
```

**建議的監控指標：**
- `rate_limit_check_total` — 總請求數
- `rate_limit_blocked_total` — 被阻擋的請求數
- `cache_hit_ratio` — 快取命中率 (Caffeine / Redis)
- `redis_latency_seconds` — Redis 延遲

**為什麼還沒做：**
- 需要 Prometheus + Grafana 基礎設施
- 開發階段用 log 足夠

**何時該做：**
- 上 Production 之前
- 需要效能調優時

---

### 11.7 API Key 格式驗證

**目前狀態：** 只驗證 `@NotBlank`

```java
// 目前
@NotBlank(message = "apiKey is required")
String apiKey

// 可以加上
@NotBlank
@Size(min = 3, max = 64)
@Pattern(regexp = "^[a-zA-Z0-9-_]+$", message = "apiKey contains invalid characters")
String apiKey
```

**為什麼還沒做：**
- 不確定 apiKey 的命名規範
- 過度限制可能影響彈性

**何時該做：**
- 確定 apiKey 格式規範後
- 有安全考量時（防止注入攻擊）

---

### 11.8 分頁最大值限制

**目前狀態：** 沒有限制 `size` 參數上限

```java
// 目前
@RequestParam(defaultValue = "10") int size

// 應該要
@RequestParam(defaultValue = "10") @Max(100) int size
```

**為什麼還沒做：**
- 限流規則數量通常不多
- 不太可能有人請求超大頁面

**何時該做：**
- 開放給外部使用時
- 防止 DoS 攻擊

---

## 總結

```
┌────────────────────────────────────────────────────────────┐
│                     設計原則                                │
├────────────────────────────────────────────────────────────┤
│  1. 簡單優先：Fixed Window > Sliding Window                 │
│  2. 效能優先：多層快取減少延遲                               │
│  3. 可靠優先：Fail-Open 避免單點故障                         │
│  4. 解耦優先：MQ 非同步處理次要邏輯                          │
│  5. 可測優先：Testcontainers 確保測試品質                    │
└────────────────────────────────────────────────────────────┘
```

---

## 技術債清單 (Tech Debt Summary)

| 項目 | 優先級 | 預估工時 | 何時該做 |
|------|--------|----------|----------|
| X-RateLimit-Reset 精確計算 | 🔴 高 | 30 min | 盡快 |
| ConfigService Fail-Open | 🔴 高 | 20 min | 上線前 |
| 全域例外處理補完 | 🟡 中 | 30 min | 上線前 |
| Cache-Aside Pattern | 🟡 中 | 30 min | 多實例部署前 |
| 可觀測性 Metrics | 🟡 中 | 2 hr | 上線前 |
| API Key 格式驗證 | 🟢 低 | 10 min | 有需要時 |
| 分頁最大值限制 | 🟢 低 | 5 min | 開放外部使用時 |
| 演算法升級 | 🟢 低 | 4 hr | 有精確限流需求時 |
