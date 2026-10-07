# Experiment 0 계측과 재현

게임 규칙, 설정, 자동 전략, RNG를 바꾸지 않고 `EngineObserver`의 값 스냅샷을 시뮬레이터의 `Observations`가 집계한다. 기본 `new Engine(config)`는 계측을 끈다. API 응답·저장 State·행동 로그 스키마는 그대로다. 계측기에는 State/Config/RNG 객체를 전달하지 않는다.

## 실행

관측은 별도 진입점을 사용한다. 기존 시뮬레이터 명령은 기존과 같이 balance 파일을 쓰므로 다음 명령으로 구분한다.

```bash
# 저장소 루트; DB나 웹 서비스 없이 실행 가능
docker compose --profile tools run --build --rm --entrypoint java simulator \
  -Xmx1g -cp /app/simulator.jar game.simulator.ObservabilityMain \
  /app/config 1000 /app/reports
```

Java 21에서 빌드된 JAR를 직접 실행할 수도 있다.

```bash
java -Xmx1g -cp simulator/target/simulator-1.0.0-jar-with-dependencies.jar \
  game.simulator.ObservabilityMain config 1000 reports
```

출력은 `reports/observability-v1.json`, `reports/observability-v1.md`다. 기존 `balance.md/json`은 읽어서 비교하며 덮어쓰지 않는다. 관측 결과를 다시 실행하면 관측 파일만 갱신된다. 다른 출력 디렉터리는 세 번째 인자로 지정한다.

`reports/observability-baseline-v1.json`은 **계측 변경 전** 소스를 따로 컴파일하여 만든 3,000시즌의 최종 State SHA-256·게임 RNG·선택 RNG와 원래 집계다. 원본 Engine/Main SHA-256·Config hash·전략 설정·기준 커밋 `5231715`도 기록한다. 현재 계측 버전으로 다시 생성해서 기준본을 바꾸면 안 된다.

진입점은 기본적으로 설정 디렉터리의 형제 `reports/observability-baseline-v1.json`을 읽는다. 다른 위치의 기준본은 네 번째 인자로 지정한다. 기준본이 있으면 모든 실행 시드와 두 RNG 및 전체 기존 집계가 일치해야 출력한다. 기준본이 없으면 계측 켬/끔 비교만 수행하고 `preChangeStatesAndBothRng=0`을 명시한다. 표준 1,000회 실행은 현재 저장된 balance의 모든 지표도 비교한다. 기준 설정/전략 불일치나 기준에 없는 시드는 실패 처리한다.

## 지표 정의

- `policySelections`: 기존 전략이 매일 생성한 세 훈련 메뉴. 주말·학기 오전 등 비활성 선택도 포함한다. HTTP changes의 변경 항목 수와 다르다.
- 메뉴 `selected`: 예정 훈련 슬롯의 수. 평일 오후/야간과 방학 평일 오전만 포함한다. `selected=executed+sum(replaced)`다. 선택된 비활성 메뉴는 정책 선택만 증가한다.
- 메뉴 이름 대응: `dribble=breakthrough`, `pressing=press`, `stamina=fitness`. 게임의 실제 ID는 수정하지 않는다. 출력 역할 `substitute`는 기존 `sub`다.
- 훈련 체력: 예정 슬롯 처리 직전 값이다. 부상·제외·보충·경기 대체도 포함한다. 경기 대체 두 슬롯은 경기 직전 동일 체력이며 가상의 경기 후 야간 체력을 만들지 않는다. 학기/방학 및 슬롯별 분포와 실행·제외 수를 함께 읽는다.
- N: 부상/제외 guard 이후 실제 3% 호출 횟수. 현재 `10≤체력<30`이다. `<30` 슬롯 수에는 이미 부상·제외된 슬롯도 있어 N과 다르다.
- 부상 일수: 발생일을 포함해 하루 종료 시 부상 상태인 시즌 내 날짜 수다. 부여된 기간 총합과 구분한다. 시즌 밖으로 남은 부상 기간은 실제 플레이 일수에 넣지 않는다.
- 자원 증감: 요청/적용 증가·감소는 각각 양수 크기다. `netChange=appliedIncrease−appliedDecrease`. 상한/하한 소실을 별도 기록하고 적용량 합계로 최종값과 대조한다. 소실은 실제 clamp 전후 차이로 계산하여 부동소수점 덧셈의 미세한 오차를 clamp 손실로 세지 않는다.
- 학업 최초 100: 변경 처리 직후 처음 100에 도달한 주차다. 못 도달한 시즌은 null이다. 19/40주 값과 미달은 실제 일요일 성적 판정 시점이다. 수업/이벤트/보충을 분리한다.
- 18주 성장: 마지막 리그 다음 일요일 하루 처리 종료 값이다. 실제 토요일 경기 시점과 다르므로 모든 경기 직전 스냅샷도 함께 제공한다.
- 골 장면의 능력은 그 장면에 사용된 능력 평균, 확률은 실제 clamp 후 백분율이다. 컨디션·패시브·돌파는 성공 판정 당시 보정이다. 원래 성공 확률 계산을 다시 판정하지 않으며 추가 RNG를 사용하지 않는다. 개인 장면 로그 대신 종류별 발생/성공/실패와 요소별 분포를 저장한다.
- 득점은 소속 팀 관점이다. `finalGoals=basePoissonGoals+playerGoals+assistAddedGoals+pressingAddedGoals`. 상대 기본/최종은 현 모델에서 동일하다. 승부차기 1/0 표시는 스코어에 더하지 않는다. all=league+cup이다.
- 최대 스코어는 우리/상대 개별 최대, 합계 최대와 실제 그 합계의 관측 스코어를 구분한다. 두 개별 최대를 조합한 가짜 스코어를 만들지 않는다. 합계≥6과 어느 한 팀≥5 비율의 분모는 해당 대회의 경기 수다.
- 평점은 반올림 전 내부값을 사용하며 벤치를 제외한다. 감독 평점 증감 호출 수는 상한으로 적용량 0이 되어도 세며 실제 순변화는 clamp 적용 후다.
- P10/P50/P90은 기존 방식과 같이 정렬 인덱스를 선택한다. P50은 낮은 중앙값이다. 빈 분포는 count 0와 null 통계, 빈 분모 비율은 0이며 count도 함께 확인해야 한다.

JSON에는 전략별 pooled 집계, 1,000개 시즌별 통계, 모든 플레이어 경기 직전 값, 기존 balance 집계가 있다. 원래 종료 State 전체와 개별 장면 로그를 중복 저장하지 않고 State 해시·RNG를 남긴다.

## 검증

회귀 기준 fixture `simulator/src/test/resources/observability-baseline.json`은 세 전략×시드 0/19/42/999의 12시즌이다. 변경 전 독립 Engine/Main에서 각 행동 JSON·행동 후 State JSON·선택 RNG 상태를 순서대로 SHA-256에 넣었다. 테스트는 모든 행동 경계의 누적 해시와 최종 State 해시 및 두 RNG를 비교한다. 따라서 동일 종료 평균만 확인하는 테스트가 아니다.

```bash
# 기존 28개 + 계측 테스트 4개
docker run --rm -v "$PWD:/work" -w /work maven:3.9-eclipse-temurin-21 mvn -B -ntp test
```

추가 테스트는 예정 슬롯/실행 카운터 일치, 균형 주말 메뉴 유지, 경기/부상/보충 대체, 상한 소실, 성적 판정 시점, 부상 N과 제외 슬롯 분모를 검사한다. 경계 테스트의 직접 State 설정은 경로 검증을 위한 것으로 시뮬레이션 정책 변경이 아니다.

2026-10-07 실행 결과:

- Java: 기존 28개와 추가 4개, 총 32개 통과, 실패/오류/skip 0.
- 프론트엔드: TypeScript/Vite 빌드 성공. Chromium 기존 2개 통과(48주 완주·재로드·재현, 모바일).
- 기존 API 검증: 동일 시드 두 시즌, 각 445개 행동, State 일치·저장·재현·동시 200/409 통과.
- 기존 DB 검증: append-only UPDATE/DELETE 및 연속 seq guard 통과.
- 3,000시즌: 계측 켬/끔 State 동일, 독립 변경 전 State와 게임/선택 RNG 동일, 원래 balance 전체 지표 동일.
- 모든 시즌: 메뉴 선택/실행/대체, 득점 분해, 기본/추가 장면, 학업/감독 적용 증감 합계 검증 통과.

기존 설정 JSON, RNG/State, balance 리포트 및 전략 선택의 동작은 유지했다. 균형 메뉴 순환은 Experiment 1까지 그대로 둔다.
