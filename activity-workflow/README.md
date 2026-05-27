# Activity Workflow

Generic workflow engine: **Activity → State → Action**.
Spring Boot 3.2 + Java 17, JPA + PostgreSQL, OpenAPI/Swagger UI, Docker.

No Kafka. No Flyway. No security. No tenant scoping.
Hibernate creates the schema automatically from `@Entity` classes (`ddl-auto=update`).

---

## Quick start

### Option A — Docker Compose

```bash
docker-compose up --build
```

Postgres + app come up together. App at `http://localhost:8080/activity-workflow`.

### Option B — Local dev

1. Start Postgres locally:
   ```bash
   docker run -d --name aw-pg -p 5432:5432 \
     -e POSTGRES_DB=activity_workflow \
     -e POSTGRES_USER=postgres \
     -e POSTGRES_PASSWORD=postgres \
     postgres:16-alpine
   ```
2. Build & run:
   ```bash
   ./mvnw spring-boot:run
   ```

On first boot Hibernate creates `aw_activity`, `aw_state`, `aw_action`
(and the `seq_aw_state` sequence) automatically.

---

## Configuration

All PostgreSQL settings are in `src/main/resources/application.properties`:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/activity_workflow
spring.datasource.username=postgres
spring.datasource.password=postgres
spring.jpa.hibernate.ddl-auto=update
```

For production, override the datasource via env vars (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`)
and set `SPRING_PROFILES_ACTIVE=prod`. The prod profile uses `ddl-auto=validate`
so Hibernate only verifies the existing schema rather than modifying it.

---

## API

Base path: `/activity-workflow`

- Swagger UI: <http://localhost:8080/activity-workflow/swagger-ui.html>
- OpenAPI JSON: <http://localhost:8080/activity-workflow/v3/api-docs>

### Create an Activity workflow

```bash
curl -sX POST http://localhost:8080/activity-workflow/activities \
  -H 'Content-Type: application/json' \
  -d '{
    "activities": [{
      "activityName": "TradeLicenseApproval",
      "businessModule": "Trade",
      "activitySla": 432000000,
      "states": [
        { "stateName": "INITIATED",  "applicationStatus": "INITIATED",  "isStartState": true,
          "actions": [{ "actionName": "FORWARD", "nextState": "PENDINGAPPROVAL", "roles": ["TL_CEMP"] }] },
        { "stateName": "PENDINGAPPROVAL", "applicationStatus": "PENDINGAPPROVAL",
          "actions": [{ "actionName": "APPROVE", "nextState": "APPROVED", "roles": ["TL_APPROVER"] }] },
        { "stateName": "APPROVED",  "applicationStatus": "APPROVED",  "isTerminateState": true }
      ]
    }]
  }'
```

### Search / fetch

```bash
curl -s "http://localhost:8080/activity-workflow/activities?activityName=TradeLicenseApproval"
curl -s "http://localhost:8080/activity-workflow/activities/<uuid>"
```

### Delete

```bash
curl -X DELETE "http://localhost:8080/activity-workflow/activities/<uuid>"
```

---

## Project layout

```
src/main/java/com/example/activityworkflow
├── ActivityWorkflowApplication.java
├── config/         OpenAPI / Swagger
├── controller/     REST endpoints
├── enrichment/     UUID + audit stamping
├── entity/         JPA @Entity classes
├── exception/      Global handler + error types
├── mapper/         DTO ↔ Entity
├── repository/     Spring Data JPA
├── service/        Business logic
└── web/
    ├── models/     DTOs
    ├── request/    Request envelopes
    └── response/   Response envelopes

src/main/resources
├── application.properties           base config + Postgres + JPA + Swagger
├── application-dev.properties       extra logging for local dev
└── application-prod.properties      env-driven datasource for prod
```

---

## Notes

- **No Flyway.** Schema comes from JPA. If you want versioned migrations later,
  add `flyway-core`, set `ddl-auto=validate`, and drop migration files in
  `src/main/resources/db/migration/`.
- **No security.** All endpoints are open. Add `spring-boot-starter-security`
  when you're ready to lock things down.
- **PostgreSQL TEXT[] for roles.** Requires Hibernate 6 + Spring Boot 3.x.
