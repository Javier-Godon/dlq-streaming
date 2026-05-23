# Library Integration Guide (Using This Framework in Other Projects)

> **Goal**: Show how to use the Execution Context framework as a library in your own Spring Boot projects.

---

## 🚀 Zero Configuration (Recommended)

If you add this framework as a dependency, **you get everything automatically**.

### Step 1: Add Dependency

```xml
<dependency>
    <groupId>es.bluesolution</groupId>
    <artifactId>railway-framework</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Step 2: Use in Your Application (Nothing Else Needed)

```java
@SpringBootApplication
public class YourApplication {
    public static void main(String[] args) {
        SpringApplication.run(YourApplication.class, args);
    }
}
```

That's it. Spring auto-discovers and wires the framework configuration.

### Step 3: Inject and Use

```java
@Service @RequiredArgsConstructor
public class CreateOrderHandler {
    private final TransactionExecutionContext txContext;

    public Result<Order, Error> handle(CreateOrderCommand cmd) {
        return Result.of(cmd.order())
            .flatMap(CreateOrderStages::validate)
            .flatMap(CreateOrderStages::persist)
            .within(txContext);  // ← Automatically available
    }
}
```

**How it works**:
- The framework provides a default `ExecutionContextConfiguration`
- Spring's `@Configuration` class is auto-discovered via classpath scanning
- All 9 execution contexts are automatically wired as beans
- You inject what you need, ignore the rest

---

## 🎯 Selective Override (Custom Contexts)

If you want to **override specific contexts** for your use case:

### Option 1: Override a Single Bean

```java
@Configuration
public class MyCustomContextConfig {

    // Override the transaction context with custom settings
    @Bean
    @Override  // Replaces the default bean
    public TransactionExecutionContext transactionExecutionContext(
        PlatformTransactionManager tm) {

        // Custom implementation
        return new CustomTransactionContext(tm);
    }

    // All other beans still provided by framework
}
```

Spring prioritizes your bean over the framework's default.

### Option 2: Add Additional Contexts

```java
@Configuration
public class MyAdditionalContexts {

    // Add custom context alongside framework defaults
    @Bean
    public ExecutionContext metricsContext(
        TransactionExecutionContext txContext) {

        return new MetricsExecutionContext(txContext);
    }

    @Bean
    public ExecutionContext cachingContext(
        TransactionExecutionContext txContext) {

        return new CachingExecutionContext(txContext);
    }
}
```

Inject with `@Qualifier`:

```java
@Service @RequiredArgsConstructor
public class ExpensiveQueryHandler {
    @Qualifier("cachingContext")
    private final ExecutionContext cachingContext;

    public Result<Data, Error> handle(Query query) {
        return pipeline.within(cachingContext);
    }
}
```

### Option 3: Disable Auto-Configuration

If you want **complete control**:

```properties
# application.properties
spring.autoconfigure.exclude=\
  es.bluesolution.railway_framework.config.ExecutionContextConfiguration
```

Then provide your own:

```java
@Configuration
public class MyOwnExecutionContextConfiguration {
    // Define all beans yourself
}
```

---

## 📊 Available Contexts (Always Ready)

These are **automatically provided** by the framework:

| Bean Name | Purpose | When to Use |
|-----------|---------|------------|
| `transactionExecutionContext` | CRUD operations | Most handlers |
| `noOpExecutionContext` | Testing, dry-runs | Testing, staging |
| `auditedTransactionContext` | Audited operations | Compliance-critical |
| `readOnlyContext` | Read-only queries | GET endpoints |
| `strictIsolationContext` | High isolation | Stock management |
| `sagaExecutionContext` | Distributed tx | DB + Keycloak |
| `sagaWithLogging` | Saga with audit | Critical sagas |
| `outboxExecutionContext` | Event durability | Event publishing |
| `outboxWithLogging` | Outbox with audit | Audit-critical events |

---

## 🔧 Configuration Properties (Optional)

You can configure the framework via `application.properties` or `application.yml`:

### Spring Transaction Settings

```properties
# Transaction timeout (seconds)
spring.jpa.properties.hibernate.jdbc.batch_size=20

# Connection pool
spring.datasource.hikari.maximum-pool-size=10
spring.datasource.hikari.minimum-idle=5
```

### Logging Configuration

```properties
# Framework execution logging
logging.level.es.bluesolution.railway_framework=DEBUG

# SQL logging
logging.level.org.hibernate.SQL=DEBUG
logging.level.org.hibernate.type.descriptor.sql.BasicBinder=TRACE
```

---

## 📋 Use Case Examples

### Example 1: Simple CRUD Application

**No configuration needed**:

```java
@Service @RequiredArgsConstructor
public class ProductService {
    private final TransactionExecutionContext txContext;  // Auto-wired

    public Result<Product, Error> create(CreateProductCommand cmd) {
        return Result.of(cmd.product())
            .flatMap(ProductStages::validate)
            .flatMap(ProductStages::persist)
            .within(txContext);
    }
}
```

### Example 2: Multi-Tenant with Events

```java
@Service @RequiredArgsConstructor
public class TenantService {
    private final SagaExecutionContext sagaContext;      // Auto-wired
    private final OutboxExecutionContext outboxContext;  // Auto-wired

    public Result<Tenant, Error> createTenant(CreateTenantCommand cmd) {
        // Create tenant + update Keycloak (distributed tx)
        return Result.of(cmd.tenant())
            .flatMap(TenantStages::validate)
            .flatMap(TenantStages::persistTenant)
            .flatMap(TenantStages::createKeycloakOrg)
            .within(sagaContext)  // Strong consistency

            .flatMap(TenantStages::publishTenantCreated)
            .within(outboxContext);  // Event durability
    }
}
```

### Example 3: Read-Only Optimized

```java
@Service @RequiredArgsConstructor
public class ReportService {
    @Qualifier("readOnlyContext")
    private final ExecutionContext readOnlyContext;  // Auto-wired

    public Result<Report, Error> generateReport(ReportQuery query) {
        return Result.of(query)
            .flatMap(ReportStages::validate)
            .flatMap(ReportStages::fetchData)
            .flatMap(ReportStages::transform)
            .within(readOnlyContext);  // Optimized for reads
    }
}
```

### Example 4: Custom Context

```java
// Define your custom context
@Configuration
public class MyContextConfig {
    @Bean
    public ExecutionContext customContext(
        TransactionExecutionContext txContext) {

        return new MyCustomContext(txContext);
    }
}

// Use it
@Service @RequiredArgsConstructor
public class MyHandler {
    private final ExecutionContext customContext;

    public Result<T, Error> handle(Command cmd) {
        return pipeline.within(customContext);
    }
}
```

---

## 🛠️ Dependency Requirements

The framework requires:

| Dependency | Version | Purpose |
|-----------|---------|---------|
| Java | 21+ | Virtual threads, records |
| Spring Boot | 4.0+ | Auto-configuration |
| Spring Data JPA | 4.0+ | Transaction management |
| Lombok | 1.18+ | Code generation |

```xml
<!-- Automatically provided if you depend on the framework -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

---

## ❓ FAQ (Library Usage)

### Q: Do I need to configure anything?
**A**: No. Add the dependency and everything works automatically.

### Q: Can I use only some contexts?
**A**: Yes. Inject only what you need. Other beans are available but unused.

### Q: Can I override the default configuration?
**A**: Yes. Create your own `@Configuration` class and Spring will prefer your beans.

### Q: What if I want a completely custom setup?
**A**: Exclude auto-configuration and define your own `ExecutionContextConfiguration`.

### Q: Are there any required beans?
**A**: Only `PlatformTransactionManager` (Spring provides this automatically).

### Q: Can I use this with non-Spring applications?
**A**: No. The framework is Spring-specific. Manual wiring would be needed.

### Q: What about conflicts with other frameworks?
**A**: None expected. The framework is self-contained and doesn't modify Spring's defaults.

---

## 🔐 Spring Profiles (Advanced)

Use Spring profiles to enable/disable contexts per environment:

```java
@Configuration
@Profile("production")
public class ProductionContextConfig {
    @Bean
    public ExecutionContext strictContext(
        PlatformTransactionManager tm) {
        return TransactionExecutionContext.withIsolation(
            tm,
            java.sql.Connection.TRANSACTION_SERIALIZABLE
        );
    }
}

@Configuration
@Profile("development")
public class DevelopmentContextConfig {
    @Bean
    public ExecutionContext devContext(
        TransactionExecutionContext txContext) {
        return new LoggingExecutionContext(txContext);
    }
}
```

Activate in `application.properties`:

```properties
spring.profiles.active=production
```

---

## 🚀 Best Practices

### 1. Use Type-Safe Injection

```java
// ✅ GOOD: Explicitly typed
@Service @RequiredArgsConstructor
public class MyService {
    private final TransactionExecutionContext txContext;
}

// ❌ AVOID: Generic ExecutionContext
@Service @RequiredArgsConstructor
public class MyService {
    private final ExecutionContext context;  // Which one?
}
```

### 2. Use @Qualifier for Named Beans

```java
// ✅ GOOD: Clear which context
@Service @RequiredArgsConstructor
public class MyService {
    @Qualifier("readOnlyContext")
    private final ExecutionContext readOnly;

    private final TransactionExecutionContext txContext;
}

// ❌ AVOID: Ambiguous
@Service @RequiredArgsConstructor
public class MyService {
    private final ExecutionContext context;
}
```

### 3. Document Context Choice

```java
@Service @RequiredArgsConstructor
public class MyService {
    // Use read-only context for queries (better performance)
    @Qualifier("readOnlyContext")
    private final ExecutionContext readOnlyContext;

    // Use transaction context for mutations
    private final TransactionExecutionContext txContext;

    public Result<Order, Error> getOrder(OrderId id) {
        return readOnlyContext.execute(() -> ...);
    }

    public Result<Order, Error> createOrder(CreateOrderCommand cmd) {
        return txContext.execute(() -> ...);
    }
}
```

### 4. Test with NoOpContext

```java
@SpringBootTest
class MyHandlerTest {
    @Autowired
    private NoOpExecutionContext noOpContext;

    @Test
    void testBusinessLogicWithoutTransactions() {
        var handler = new MyHandler(noOpContext);
        Result<T, Error> result = handler.handle(cmd);
        assertTrue(result.isSuccess());
    }
}
```

---

## 📦 Publishing as a Library

If you want to publish this as a Maven/Gradle library:

### Step 1: Create Library Module

```
railway-framework-library/
├── pom.xml
├── src/main/java/
│   └── es/bluesolution/railway_framework/
│       ├── framework/
│       │   └── execution/
│       │       ├── ExecutionContext.java
│       │       ├── TransactionExecutionContext.java
│       │       ├── ... (all framework classes)
│       │       └── INDEX.md
│       └── config/
│           └── ExecutionContextConfiguration.java
└── README.md
```

### Step 2: Configure POM

```xml
<project>
    <modelVersion>4.0.0</modelVersion>
    <groupId>es.bluesolution</groupId>
    <artifactId>railway-framework</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <name>Railway-Oriented Programming Framework</name>
    <description>Functional effect handling for Spring Boot applications</description>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <!-- ... other dependencies ... -->
    </dependencies>
</project>
```

### Step 3: Document for Users

Include this guide in your library's README.

---

## 🎓 Learning Path (For Users of This Library)

1. **New users**: Read `INDEX.md` → `00_FRAMEWORK_START_HERE.md`
2. **Implementing first handler**: Read `02_WITHIN_COMBINATOR.md` + `04_EXECUTION_CONTEXTS_CATALOG.md`
3. **Advanced patterns**: Read `patterns/SAGA_PATTERN_QUICK_REFERENCE.md`
4. **Before shipping**: Review `05_TEAM_RULES_AND_BEST_PRACTICES.md`

---

## ✅ Checklist for Integration

- [ ] Added framework as dependency
- [ ] No custom `ExecutionContextConfiguration` created (unless overriding)
- [ ] Injected context into handler/service
- [ ] Used with `.within(context)` in Result pipeline
- [ ] Tested with both real and NoOp contexts
- [ ] Reviewed team rules in `05_TEAM_RULES_AND_BEST_PRACTICES.md`
- [ ] Ready for production

---

## 📞 Troubleshooting Library Integration

| Problem | Cause | Solution |
|---------|-------|----------|
| "ExecutionContext bean not found" | Framework not on classpath | Add dependency to pom.xml |
| "TransactionExecutionContext not autowired" | PlatformTransactionManager missing | Spring provides this automatically |
| "Multiple beans of type ExecutionContext" | Overriding without @Override | Use @Qualifier or @Primary |
| "Custom bean not preferred" | Framework bean takes precedence | Mark framework bean as @Primary false |

---

**Ready to use as a library! No configuration needed.**
