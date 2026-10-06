# 첫 시즌 — 고교 축구선수 육성 프로토타입

Java 21 / Spring Boot / PostgreSQL, TypeScript / React / Vite / Zustand / TanStack Query.
그림·애니메이션 없이 일과, 경기, 이벤트와 48주 시즌을 텍스트로 진행합니다.

## 실행

```sh
docker compose up --build
```

브라우저에서 **http://localhost:5187**을 엽니다. 첫 실행은 의존성과 이미지를 내려받습니다.
이후 `docker compose up`으로 시작합니다. DB는 Compose 볼륨에 저장됩니다.
평일에는 네 시간대의 선택을 확인하고, 일요일에는 자유 행동 하나를 고릅니다.
이벤트 선택을 마쳐야 일과가 이어집니다. 중계는 다음 장면 또는 건너뛰기로 확인합니다.
페이지를 새로고침하면 현재 판을 이어서 할 수 있습니다. 시작 화면으로 돌아가도 기존 판은 삭제하지 않습니다.

## 구조

- `config/`: 수치, 능력치, 등급, 컨디션, 훈련 메뉴, 장면, 학교, 캘린더, 30개 이벤트, 시뮬레이터 전략 데이터.
- `domain/`: Spring 없는 순수 Java 규칙·상태 머신·저장 가능한 난수와 단위 테스트.
- `simulator/`: 도메인을 직접 호출하는 세 전략의 화면 없는 1년 시뮬레이터.
- `server/`: HTTP API, PostgreSQL 스냅샷, 순번 잠금 및 추가 전용 요청 기록.
- `frontend/`: 서버 사실을 표시하는 최소 React 화면. Query에 서버 값, Zustand에 화면 상태만 저장.
- `ASSUMPTIONS.md`: 명세에 없는 규칙의 단순한 해석과 확인 대기 항목.
- `reports/balance.md`, `reports/balance.json`: 전략별 1,000시즌의 표·분포·관찰 결과.

## 밸런스 시뮬레이터

```sh
docker compose --profile tools run --build --rm simulator
```

DB나 웹 화면을 실행하지 않고 각 전략을 시드 0~999로 1,000회씩 실행합니다.
결과는 `reports/balance.md`와 `reports/balance.json`에 저장되며 표는 표준 출력에도 나옵니다.
실행 횟수를 바꾸려면:

```sh
docker compose --profile tools run --rm simulator /app/config 100 /app/reports
```

주력 3개 및 전체 18개/훈련 12개 평균, 개별 능력치 분포, 주별 체력, 부상·훈련 제외,
학업·보충수업, 출전 비율·장면·득점·도움·평점·팀 득점, 평판·리그 순위를 집계합니다.
설정 수치는 관찰만 하고 자동 조정하지 않습니다.

## 테스트

Java 21와 Maven이 있으면 `mvn test`를 실행합니다. Docker만 있으면:

```sh
docker run --rm -v "$PWD:/work" -w /work maven:3.9-eclipse-temurin-21 mvn -B -ntp test
```

프론트엔드 타입 검사와 빌드:

```sh
cd frontend
npm ci
npm run build
```

Compose 실행 후 API로 시즌 전체·로그 재현·중복 요청을 검증합니다:

```sh
python3 scripts/check_api.py
```

실제 브라우저 테스트 (Compose 실행 후):

```sh
cd frontend
npx playwright install chromium
npm run test:e2e
```

PostgreSQL 행동 로그 보호 검증:

```sh
docker compose exec -T db psql -v ON_ERROR_STOP=1 -U soccer -d soccer < scripts/check_db.sql
```

기본 웹 포트는 5187이며 `WEB_PORT=다른포트 docker compose up`으로 바꿀 수 있습니다.
브라우저 테스트의 주소도 `BASE_URL=http://localhost:다른포트`로 지정할 수 있습니다.

API는 웹 서버의 `/api` 경로를 통해 접근합니다. 새 판 `POST /api/runs`, 판 조회 `GET /api/runs/{id}`,
하루/이벤트 선택 `POST /api/runs/{id}/actions`, 처음부터 로그 재현 `GET /api/runs/{id}/replay`.

```json
{"expectedSeq":1,"action":{"kind":"day","changes":{"night":"aerial"}}}
```

`changes`에는 바꾼 선택만 보냅니다. 일요일은 `sundayAction` (`rest`, `job`, `coach`, `teammate`, `family`),
이벤트는 `{"kind":"event","choice":0}`을 보냅니다. 판 생성 요청은 `{"seed":"2026","schoolId":null}`입니다.

## 재현과 저장

게임 난수는 상태가 명시된 SplitMix64 스트림입니다. 선택용 스트림은 설정된 salt로 분리합니다.
판별 PostgreSQL 행 잠금 아래에서 요청 검사, 순번 추가, 상태 저장을 하나의 트랜잭션으로 처리합니다.
행동 테이블의 UPDATE/DELETE/TRUNCATE 및 순번 건너뛰기는 DB 트리거로 거부합니다.
판 생성 선택, 시드와 당시 설정 전체도 저장하므로 이후 파일을 튜닝해도 기존 판을 재현할 수 있습니다.

## 확인 대기

12월 성적 확인 뒤 보충수업의 적용 날짜가 문서 내에서 충돌해 사용자 확인을 요청했습니다.
미달 여부는 기록하며, 해당 보충수업의 날짜는 아직 확정하지 않았습니다. 자세한 내용은 `ASSUMPTIONS.md`에 있습니다.
