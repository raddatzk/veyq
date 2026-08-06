# Stack API Notes

> **Purpose:** Growing reference for stack-specific API facts discovered during sessions.
> Read this file (CRL-05 in `@tester`, and the equivalent in `@coder`) when your task touches
> framework APIs. Add a note here immediately when a new gotcha is found — before continuing work.
>
> **Format:** One `##` section per technology version. Entries are append-only.
> Cross-reference the corresponding finding (F-NNN) when one exists.

---

## Cucumber / Spring Boot integration

### @pending tag
`@pending` is a **scenario-level skip tag** implemented by the test runner configuration.
It causes Cucumber to skip the scenario and report it as "skipped" — not as "passing" or "failing".
The build stays green when all remaining failures are skipped scenarios.
`@pending` has no meaning inside a step definition. Step definitions are always complete and real.

---

## Spring Boot 4 test infrastructure

### New modular design — test starters
Spring Boot 4 ships smaller focused modules. Test infrastructure for a technology lives in
`spring-boot-starter-<technology>-test`.
- `@DataMongoTest` moved to package `org.springframework.boot.data.mongodb.test.autoconfigure`.
- Requires dependency: `org.springframework.boot:spring-boot-starter-data-mongodb-test`
  (no explicit version — managed by SB BOM).
- The old `spring-boot-test-autoconfigure` jar no longer contains `@DataMongoTest`.

### MongoDB property prefix
Spring Boot 4 uses `spring.mongodb.*` — the `data.` segment has been removed.
- URI: `spring.mongodb.uri` (not `spring.data.mongodb.uri`)
- Auto-index creation: `spring.mongodb.auto-index-creation=true`


### RestTestClient (Spring Boot 4.0+)

Spring Boot 4.0 introduced `RestTestClient` as the new HTTP testing client, replacing the older
`TestRestTemplate` and `WebTestClient`. It is designed for integration tests (`*IT.java` files)
within a Spring context.

#### Core API facts

**Dependency & annotation:**

- Dependency: `org.springframework.boot:spring-boot-starter-webmvc-test` (includes
  `spring-boot-resttestclient`)
- Annotation: `@AutoConfigureRestTestClient` on `@SpringBootTest` test class
- Import: `org.springframework.test.web.servlet.client.RestTestClient`

**RequestBodySpec — the critical difference from training:**
`RestTestClient.RequestBodySpec` (what you get after `.post().uri(...).contentType(...)`)
exposes **exactly three methods**:

- `contentLength(long)` — set request body length
- `contentType(MediaType)` — set Content-Type header
- **`body(Object)`** — set the request body (accepts `String` for JSON payloads)

**Red flags for confusion with other APIs:**

- ❌ `.content()` does NOT exist (that is `MockMvc`)
- ❌ `.bodyValue()` does NOT exist (that is `WebTestClient`)
- ✅ Use `.body(jsonString)` — pass the JSON as a String directly

**ResponseSpec — assertion fluent API:**
After `.exchange()`, you get `RestTestClient.ResponseSpec`. Key methods:

- `.expectStatus()` — assert HTTP status: `.isBadRequest()`, `.isOk()`, `.isCreated()`, etc.
- `.expectBody()` — fluent JSON assertions: `.jsonPath("$.fieldName").isEqualTo(value)`
- `.expectHeader()` — assert response headers

**Full pattern:**

```java
RestTestClient.ResponseSpec response = client
    .post()
    .uri("/api/endpoint/{id}", idValue)
    .contentType(MediaType.APPLICATION_JSON)
    .body(jsonPayloadString)           // ← Use .body(), not .content() or .bodyValue()
    .exchange();

response.expectStatus().isCreated();
response.expectBody()
    .jsonPath("$.id").isNotEmpty()
    .jsonPath("$.etag").isNotEmpty();
```

**Why it matters for component interface testing:**
The fluent `.expectStatus()` and `.expectBody().jsonPath()` API maps naturally to contract
assertions: precondition violations result in status assertions (e.g. 400), postcondition
verification uses `.jsonPath()`. This is the intended HTTP testing tool for Spring Boot 4
integration tests.


---

## Jackson 3.x

Spring Boot 4 ships Jackson 3.x (`tools.jackson.core:jackson-databind:3.0.x`), not Jackson 2.x.

### Package split
- Core and databind moved to `tools.jackson.*`
- **Annotations stay in `com.fasterxml.jackson.annotation`** — `@JsonIgnore`, `@JsonProperty`,
  `@JsonCreator` etc. are unchanged in Jackson 3.
- Any `import com.fasterxml.jackson.core.*` or `import com.fasterxml.jackson.databind.*` in
  production code is almost certainly wrong. The only correct `com.fasterxml.jackson` imports
  are `com.fasterxml.jackson.annotation.*`.

### Module registration
`findAndRegisterModules()` does not exist in Jackson 3. Modules are auto-discovered via
ServiceLoader at `ObjectMapper` construction time. Use `new ObjectMapper()` — no manual
module registration needed.

### Exception type
`JsonProcessingException` removed — replaced by `tools.jackson.core.JacksonException`
(unchecked `RuntimeException`). `writeValueAsString()` now declares `throws JacksonException`.

---

## Testcontainers 1.21.x

### MongoDBContainer
`MongoDBContainer` always starts a single-node **replica set** by default.
There is no `withReplicaSet()` method.
- Use `getReplicaSetUrl(dbName)` (not `getConnectionString()`) to make the replica-set URI explicit.
- MongoDB multi-document transactions work out of the box — no special setup needed.

---

## Spring Boot 4 test slice annotations — L3 verification

Spring Boot 4.0+ continues to support the same fundamental test slices documented in CO-TL-03.
The annotations remain correct and available:

### REST Controllers
- **Annotation:** `@WebMvcTest` (no changes)
- **Package:** `org.springframework.boot.test.autoconfigure.web.servlet`
- **Infrastructure:** MockMvc, no real HTTP server
- **Verified:** Correct for Spring Boot 4

### OpenFeign clients
- **Annotation:** `@RestClientTest` (for modern HTTP clients)
- **Alternative:** `@SpringBootTest(classes=...)` with WireMock
- **Package:** `org.springframework.boot.test.autoconfigure.restclient`
- **Infrastructure:** WireMock mocks the downstream API
- **Verified:** Both approaches work in Spring Boot 4

### Kafka producer (using embedded broker)
- **Annotation:** `@SpringBootTest` + `@EmbeddedKafka`
- **Package:** `org.springframework.kafka.test.context.EmbeddedKafka`
- **Dependency:** `spring-boot-starter-kafka-test` (modular in SB4)
- **Infrastructure:** Embedded broker starts (not in Docker)
- **Verified:** Available and correct

### MongoDB repository
- **Annotation:** `@DataMongoTest`
- **Package:** `org.springframework.boot.data.mongodb.test.autoconfigure` ← **MOVED** (see above)
- **Dependency:** `spring-boot-starter-data-mongodb-test` ← **REQUIRED** (see above)
- **Infrastructure:** Testcontainers required for real MongoDB (not auto-configured; bring your own)
- **Verified:** Annotation name unchanged, but module structure changed

### ASB consumer adapter (in-memory)
- **Approach:** Plain JUnit, no Spring slice needed
- **Infrastructure:** In-memory service bus port mock
- **Verified:** Correct for Spring Boot 4

**Conclusion:** All annotations in CO-TL-03 remain valid. No corrections needed to the table itself.
The key discovery is the module split: `@DataMongoTest` moved package and requires explicit dependency declaration (already documented in "Spring Boot 4 test infrastructure" section above).

---

## Spring Boot 4 MVC slice tests

### WebMvcTest and AutoConfigureMockMvc package move
In Spring Boot 4, MVC slice annotations are in `org.springframework.boot.webmvc.test.autoconfigure`.
- `@WebMvcTest` import: `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`
- `@AutoConfigureMockMvc` import: `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`

### Mock bean override annotation
`@MockBean` is no longer available in Boot's test package.
Use Spring Framework's bean override annotation instead:
- `@MockitoBean` import: `org.springframework.test.context.bean.override.mockito.MockitoBean`

This keeps `@WebMvcTest`-based tests compatible with Spring Framework 7 bean override mechanics.

---


