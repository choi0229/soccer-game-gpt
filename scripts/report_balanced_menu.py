"""Summarize Experiment 1 from its saved 2,000 seasons; does not simulate games."""
import hashlib
import json
import math
from pathlib import Path
import statistics
import subprocess
import sys

path = Path(sys.argv[1] if len(sys.argv) > 1 else 'reports/experiment-1-balanced-menu.json')
md_path = path.with_suffix('.md')
if md_path.exists():
    raise SystemExit(f'Report already exists: {md_path}')
r = json.loads(path.read_text())
arms = list(r['arms'].values())
menu_ids = ['shooting', 'power', 'aerial', 'breakthrough', 'link', 'press', 'fitness']
alias = {'breakthrough': 'dribble', 'press': 'pressing', 'fitness': 'stamina'}
stat_defs = json.loads(Path('config/stats.json').read_text())
trainable = [s['id'] for s in stat_defs if s['trainable']]
primary = [s['id'] for s in stat_defs if s['primary']]

def summary(values):
    a = sorted(v for v in values if v is not None)
    if not a:
        return dict(count=0, mean=None, p10=None, p50=None, p90=None)
    n = len(a)
    return dict(count=n, mean=statistics.mean(a), min=a[0], p10=a[int((n-1)*.1)], p50=a[(n-1)//2], p90=a[int((n-1)*.9)], max=a[-1])

def season_metrics(row):
    o = row['observations']
    apps = o['appearances']
    goals = o['scores']['all']['totals']
    return {'trainable': statistics.mean(row['statsWeek48'][s] for s in trainable),
            'primary': statistics.mean(row['statsWeek48'][s] for s in primary),
            'fitness': row['statsWeek48']['fitness'], 'coach': o['coach']['final'],
            'starterRatio': sum(x['role']=='starter' for x in apps)/len(apps),
            'goals': goals['playerGoals'], 'assists': goals['assistAddedGoals'],
            'reputation': row['reputation'], 'leagueRank': row['leagueRank']}

comp = []
for arm in arms:
    seasons = arm['seasons']; o = arm['observations']; legacy = arm['legacyBalance']
    appearances = [x for s in seasons for x in s['observations']['appearances']]
    finals = [season_metrics(s) for s in seasons]
    metrics = {k: summary(x[k] for x in finals) for k in finals[0]}
    metrics['firstStarterWeek'] = summary(s['observations']['firstStarterWeek'] for s in seasons)
    metrics['neverStarterSeasons'] = sum(s['observations']['firstStarterWeek'] is None for s in seasons)
    metrics['pregame'] = {k: summary(x[k] for x in appearances) for k in ['coach', 'trainable', 'primary', 'score']}
    metrics['statsByWeek'] = {str(w): {s['id']: summary(row[f'statsWeek{w}'][s['id']] for row in seasons) for s in stat_defs} for w in [18, 48]}
    for w in [18, 48]:
        metrics[f'trainableWeek{w}'] = summary(statistics.mean(row[f'statsWeek{w}'][s] for s in trainable) for row in seasons)
        metrics[f'primaryWeek{w}'] = summary(statistics.mean(row[f'statsWeek{w}'][s] for s in primary) for row in seasons)
    counts = [o['menus'][alias.get(m,m)]['executed'] for m in menu_ids]
    metrics['menuDispersion'] = {'totalsAcrossMenus': counts, 'populationStdDev': statistics.pstdev(counts),
        'coefficientOfVariation': statistics.pstdev(counts)/statistics.mean(counts), 'maxMinusMin': max(counts)-min(counts),
        'seasonExecutionRange': summary(max(s['observations']['menus'][alias.get(m,m)]['executed'] for m in menu_ids)-min(s['observations']['menus'][alias.get(m,m)]['executed'] for m in menu_ids) for s in seasons)}
    metrics['trainingStaminaOverallMean'] = sum(o['trainingStamina'][t]['stamina']['mean']*o['trainingStamina'][t]['stamina']['count'] for t in ['school','vacation'])/sum(o['trainingStamina'][t]['stamina']['count'] for t in ['school','vacation'])
    metrics['rankFirstRatio'] = sum(x['leagueRank']==1 for x in seasons)/len(seasons)
    metrics['rankTop3Ratio'] = sum(x['leagueRank']<=3 for x in seasons)/len(seasons)
    metrics['allRatingAtLeast7Ratio'] = sum(x['atLeast7']['count'] for x in o['ratings'].values())/legacy['appearances']
    metrics['allRatingBelow55Ratio'] = sum(x['below5_5']['count'] for x in o['ratings'].values())/legacy['appearances']
    arm['comparison'] = metrics; comp.append(metrics)
paired = {}
for a,b in zip(arms[0]['seasons'], arms[1]['seasons']):
    assert a['seed']==b['seed']
    ma,mb = season_metrics(a),season_metrics(b)
    for k in ma: paired.setdefault(k,[]).append(mb[k]-ma[k])
r['pairedDifferences'] = {k: summary(v) for k,v in paired.items()}
r['definitions'] = {'weekSnapshots': 'End of Sunday of week 18 / end of week 48',
    'percentiles': 'Sorted sample index floor((n-1)*q); P50 uses lower middle',
    'menuSelections': 'selected=scheduled slots including replacement; policySelections=all generated slot menu fields, including inactive slots',
    'cursor': 'Sum of actually completed trainingCounts; zero advance on every replacement',
    'stamina': 'All scheduled training slots, including replacement, same as Experiment 0',
    'gameRng': 'Same RNG algorithm and engine call sites/order; different choices may trigger different branches and RNG states'}
r['sourceCommit'] = subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
r['sourceSha256'] = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in [Path('domain/src/main/java/game/domain/Engine.java'),Path('domain/src/main/java/game/domain/Rng.java'),Path('simulator/src/main/java/game/simulator/Main.java'),Path('simulator/src/main/java/game/simulator/BalancedTrainingSlots.java'),Path('simulator/src/main/java/game/simulator/BalancedMenuExperiment.java'),Path(__file__)]}
path.write_text(json.dumps(r,ensure_ascii=False,separators=(',',':'))+'\n')

lines = ['# Experiment 1 — 균형 전략의 훈련 메뉴 순환 비교','',
         '2026-10-07 · A/B 각 1,000시즌, 동일 시드 0~999, 기본 학교 s07(전력 40). 게임 규칙·설정·RNG·다른 전략·Experiment 0 계측은 그대로 유지했다.','',
         '| 지표 | A 절대 날짜 | B 실행 슬롯 순환 | B−A |','|---|---:|---:|---:|']
def row(label,a,b,digits=3,percent=False):
    scale=100 if percent else 1; suffix='%' if percent else ''; diff_suffix='%p' if percent else ''
    lines.append(f'| {label} | {a*scale:.{digits}f}{suffix} | {b*scale:.{digits}f}{suffix} | {(b-a)*scale:+.{digits}f}{diff_suffix} |')
for name,key in [('12개 평균','trainable'),('주력 평균','primary'),('기초체력','fitness')]:row(name,comp[0][key]['mean'],comp[1][key]['mean'])
for name,key in [('활동량','workRate'),('팀워크','teamwork')]:row(name,comp[0]['statsByWeek']['48'][key]['mean'],comp[1]['statsByWeek']['48'][key]['mean'])
row('선발률',arms[0]['legacyBalance']['roleRatios']['starter'],arms[1]['legacyBalance']['roleRatios']['starter'],2,True)
for name,key in [('골/시즌','goals'),('도움/시즌','assists'),('평판','reputation'),('리그 순위','leagueRank')]:row(name,comp[0][key]['mean'],comp[1][key]['mean'])
lines += ['', '## 실험 조건과 구현','',
'A는 `balanced-calendar`, B는 `balanced-training-slot`이다. 기존 `balanced` 이름과 기본 밸런스 실행은 A를 유지한다. `Main.run(config, 이름, seed, policy, observer)`로 각각 재실행할 수 있다.', '',
'B는 시즌 `trainingCounts` 합계를 결정론적 인덱스로 사용한다. 하루 요청 시 실제 예정 슬롯만 세어 서로 다른 메뉴를 배정한다. 학기 오전·주말·경기 대체·보충 오후·기존 부상은 계획의 인덱스를 소비하지 않는다. 방학 오전은 소비한다. 예상하지 못한 새 부상과 체력 제외는 그날 이후 모든 훈련을 중단시키므로 실제 실행은 예정 슬롯의 접두 부분이 된다. 다음 날은 완료 횟수로 다시 시작해 취소된 슬롯을 건너뛰지 않는다. 이벤트 중단 뒤에도 같은 하루 선택을 재사용한다. 도메인 엔진을 수정하거나 난수로 실행을 미리 예측하지 않았다.', '',
'순서: `shooting → power → aerial → breakthrough → link → press → fitness`. 여기서 인덱스 증가의 의미는 정상 훈련 함수가 성장 처리를 수행하고 `trainingCounts`를 증가시킨 경우다. 능력 상한으로 일부 증가가 잘리는 경우도 정상 훈련은 완료로 센다. 두 집단에서 모든 대상 능력이 동시에 상한에 막힌 훈련은 없었다.', '',
'같은 시드는 같은 초기 상태를 짝짓지만 메뉴가 바뀌면 장면 성공·이벤트 분기 등에 따라 이후 난수의 대응과 게임 RNG 종료값은 달라질 수 있다. 고정한 것은 엔진의 RNG 알고리즘·호출 위치·판정 순서이며 A와 B의 최종 RNG가 같아야 한다는 조건은 아니다.', '',
'## 메뉴 선택·실행·대체','',
'횟수는 1,000시즌 합계다. 선택은 예정 슬롯(대체 포함), 정책 선택은 주말·비활성 오전에도 생성된 필드까지 포함한다. 슬롯별/시즌별 원자료는 JSON에 보존했다.', '',
'| 메뉴 | A 정책 선택 | A 선택 | A 실행 | A 대체 | B 정책 선택 | B 선택 | B 실행 | B 대체 |','|---|---:|---:|---:|---:|---:|---:|---:|---:|']
for m in menu_ids:
    vals=[]
    for arm in arms:
        x=arm['observations']['menus'][alias.get(m,m)];vals += [x['policySelections'],x['selected'],x['executed'],sum(x['replaced'].values())]
    lines.append('| '+m+' | '+' | '.join(map(str,vals))+' |')
lines += ['', '| 실행 편차 | A | B |','|---|---:|---:|']
for k in ['populationStdDev','coefficientOfVariation','maxMinusMin']:
    lines.append(f"| {k} | {comp[0]['menuDispersion'][k]:.4f} | {comp[1]['menuDispersion'][k]:.4f} |")
lines += ['', '표준편차/CV는 메뉴 7개의 누적 실행 횟수 간 모집단 편차다. 시즌별 메뉴 최다−최소 실행 차이는 A 평균 '+f"{comp[0]['menuDispersion']['seasonExecutionRange']['mean']:.3f}, B 평균 {comp[1]['menuDispersion']['seasonExecutionRange']['mean']:.3f}"+'이다. B는 연속된 7메뉴 순환이므로 모든 시즌에서 최대 차이가 1회다. 대체 사유별·슬롯별 합계는 JSON의 `observations.menus/menuSlots`에 있다.', '', '## 18주와 48주 능력치','', '일요일 하루 종료 시점이다. 18주는 마지막 리그 경기 뒤이며 경기 직전 값과 구분한다.', '', '| 능력 | A 18주 | B 18주 | 차이 | A 48주 | B 48주 | 차이 |','|---|---:|---:|---:|---:|---:|---:|']
for s in stat_defs:
    k=s['id'];a18,b18=[x['statsByWeek']['18'][k]['mean'] for x in comp];a48,b48=[x['statsByWeek']['48'][k]['mean'] for x in comp]
    lines.append(f"| {s['label']} ({k}) | {a18:.3f} | {b18:.3f} | {b18-a18:+.3f} | {a48:.3f} | {b48:.3f} | {b48-a48:+.3f} |")
for label,key in [('12개 평균','trainable'),('주력 평균','primary')]:
    a18,b18=[x[key+'Week18']['mean'] for x in comp];a48,b48=[x[key+'Week48']['mean'] for x in comp]
    lines.append(f'| {label} | {a18:.3f} | {b18:.3f} | {b18-a18:+.3f} | {a48:.3f} | {b48:.3f} | {b48-a48:+.3f} |')
lines += ['', '## 체력·부상','', '| 지표 | A | B | B−A |','|---|---:|---:|---:|']
row('훈련 직전 평균 체력',comp[0]['trainingStaminaOverallMean'],comp[1]['trainingStaminaOverallMean'])
for t,label in [('school','학기'),('vacation','방학')]:
    gs=[a['observations']['trainingStamina'][t] for a in arms]
    row(label+' 평균 체력',gs[0]['stamina']['mean'],gs[1]['stamina']['mean'])
    row(label+' 최대 체력 대비 비율',gs[0]['currentToMaximumRatio']['mean'],gs[1]['currentToMaximumRatio']['mean'],2,True)
    for k,n in [('below30','<30 슬롯'),('below10','<10 슬롯'),('excludedSlots','제외 슬롯')]:row(label+' '+n,gs[0][k],gs[1][k],0)
for k,label in [('rollsN','유효 부상 판정 N'),('occurrences','부상 발생'),('injuredCalendarDaysWithinSeason','시즌 내 부상 일수')]:row(label,*[a['observations']['injury'][k] for a in arms],digits=0)
lines += ['', '슬롯 직전 체력은 대체 슬롯까지 포함한 Experiment 0의 같은 정의다. 최대 체력은 `100+(기초체력−50)/2`이므로 기초체력 증가가 최대 체력을 올린다. <30 전체 노출과 실제 확률 판정 N은 부상/제외 가드 때문에 다를 수 있다. 부상 1건 대 0건으로 발생률 개선을 확정할 표본은 없다.', '', '## 출전·경기 생산성','', '| 지표 | A | B | B−A |','|---|---:|---:|---:|']
for role,label in [('starter','선발률'),('sub','교체률'),('bench','벤치률')]:row(label,*[a['legacyBalance']['roleRatios'][role] for a in arms],digits=2,percent=True)
row('최초 선발 주차 평균',comp[0]['firstStarterWeek']['mean'],comp[1]['firstStarterWeek']['mean'])
for k,label in [('trainable','경기 직전 12개 평균'),('primary','경기 직전 주력 평균'),('coach','경기 직전 감독 관계'),('score','출전 점수')]:row(label,*[x['pregame'][k]['mean'] for x in comp])
for k,label in [('scenesPerMatch','장면/경기'),('goalsPerMatch','골/경기'),('assistsPerMatch','도움/경기')]:row(label,*[a['legacyBalance'][k] for a in arms])
for role,label in [('starter','선발 평균 평점'),('substitute','교체 평균 평점')]:row(label,*[a['observations']['ratings'][role]['distribution']['mean'] for a in arms])
for k,label in [('allRatingAtLeast7Ratio','전체 평점 ≥7'),('allRatingBelow55Ratio','전체 평점 <5.5')]:row(label,*[x[k] for x in comp],digits=2,percent=True)
for role in ['starter','substitute']:
    for k,label in [('atLeast7','≥7'),('below5_5','<5.5')]:row(role+' 평점 '+label,*[a['observations']['ratings'][role][k]['ratio'] for a in arms],digits=2,percent=True)
row('시즌 종료 감독 관계',*[x['coach']['mean'] for x in comp])
lines += ['', '최초 선발 주차 P10/P50/P90: '+ ' / '.join(f"{name} {x['firstStarterWeek']['p10']}/{x['firstStarterWeek']['p50']}/{x['firstStarterWeek']['p90']} (미선발 {x['neverStarterSeasons']}시즌)" for name,x in zip(['A','B'],comp))+'.', '', '## 리그 순위','', '| 지표 | A | B | B−A |','|---|---:|---:|---:|']
for k,label in [('mean','평균'),('p10','P10'),('p50','중앙'),('p90','P90')]:row(label,*[x['leagueRank'][k] for x in comp])
for k,label in [('rankFirstRatio','1위 비율'),('rankTop3Ratio','3위 이내 비율')]:row(label,*[x[k] for x in comp],digits=2,percent=True)
lines += ['', '## 동일 시드 짝차이 B−A','', '선발률은 시즌별 경기 비율의 차이(%p), 다른 값은 원래 단위다. 위 핵심표의 선발률은 경기 전체를 합친 비율이므로 짝차이 평균과 약간 다를 수 있다. 순위 차이가 음수이면 순위 개선이다.', '', '| 지표 | 평균 차이 | 중앙 | P10 | P90 |','|---|---:|---:|---:|---:|']
for k,v in r['pairedDifferences'].items():
    scale=100 if k=='starterRatio' else 1
    lines.append('| '+k+' | '+' | '.join(f'{v[x]*scale:+.4f}' for x in ['mean','p50','p10','p90'])+' |')
lines += ['', '## 압박 장면과 해석','', '| 지표 | A | B | B−A |','|---|---:|---:|---:|']
press=[a['observations']['scenes']['kinds']['press'] for a in arms]
row('압박 장면 수',*[x['occurred'] for x in press],digits=0)
row('압박 성공 수',*[x['success'] for x in press],digits=0)
row('압박 성공률',*[x['success']/x['occurred'] for x in press],digits=2,percent=True)
row('압박 추가 팀 골/경기',*[a['observations']['scores']['all']['perMatch']['pressingAddedGoals'] for a in arms])
lines += ['', '**Q1 — 높은 선발률이 유지되는가?** A 69.09%, B 68.72%로 거의 유지된다(−0.375%p). 메뉴 편향이 높은 선발률의 주원인이라고 볼 결과는 아니다. 감독 만남·관계 정책과 출전 공식·일정은 고정되었다.', '',
'**Q2 — 12개 평균은 증가하는가?** 43.531→41.573으로 −1.958이다. press는 활동량·팀워크를 올리지만 fitness는 출전용 12개 평균에 들어가지 않는다. 기존 5개 메뉴의 훈련 횟수 감소와 기초체력으로 분산되는 슬롯 때문에 12개 평균과 주력은 낮아진다. 주력은 −4.830, 활동량·팀워크는 각각 약 +10.75다.', '',
'**Q3 — 체력·부상 노출은 의미 있게 달라지는가?** 기초체력은 +18.777, 종료 최대 체력은 평균 +9.388이다. 직전 체력과 최대치 대비 비율이 상승하며 유효 N은 12→2로 줄었다. 노출은 감소하지만 본래 극히 적어 부상 경험을 유의하게 줄였다고 단정할 수 없다. 부상 발생 1→0은 확률 효과 추론에 충분하지 않다.', '',
'**Q4 — 압박 성과는 달라지는가?** 압박 성공률은 약 30.19%→32.05%(+1.86%p), 성공 수 1,813→1,921이다. 활동량 상승과 양립하는 변화다. 현재 press 판정은 활동량을 직접 사용하고 팀워크는 사용하지 않는다(팀워크는 패스 장면에 사용). 컨디션·패시브·상대·난수 경로도 달라질 수 있으므로 성공률 차이 전체를 활동량의 단독 인과 효과로 단정하지 않는다.', '',
'**Q5 — 다음 기준선은 어느 쪽인가?** 모든 메뉴를 실행하는 균형 전략의 기준선은 B를 권장한다. 성능 우월성 때문이 아니라 메뉴가 요일에 의해 구조적으로 제외되지 않고 슬롯당 순환이 검증되었기 때문이다. A는 과거 비교용으로 보존한다. 이번 코드에서는 기존 `balanced` 기본값을 자동으로 B로 교체하지 않았다.', '',
'이 실험은 메뉴 선택 규칙군 하나를 비교했다. 낮아진 주력·골·평판을 보상하기 위해 성장량·출전 가중치 등을 동시에 수정하지 않았다. 학업·평점 목표 등 후속 수치의 합격선은 별도 실험에서 정해야 한다.', '',
'## 검증과 재실행','',
'- A 1,000시즌: 계측 이전 독립 기준선의 전체 최종 State SHA-256·게임 RNG·선택 RNG 일치. 기존 `balance.json`의 균형 집계 전체 일치.',
'- A/B 2,000시즌: 각각 다시 실행한 전체 State·두 RNG 일치, 계측 분모·증감·득점·장면 합계 검증 통과.',
'- Java 전체 36개(기존 32개+추가 4개), 프론트엔드 빌드, 브라우저 2개, API 두 시즌 재현/동시 요청, PostgreSQL 무결성 검증 통과.',
'- 실제 메뉴 실행 순서 테스트는 이벤트 중단을 포함한 40개 시드의 전 슬롯을 검증한다. 별도 경계 테스트는 경기·부상·새 부상·보충·체력 제외·주말·방학 오전을 확인한다.',
'- 이번 작업 전후 `config/`, `domain/src/`, 기존 리포트와 `Observations.java`의 파일 SHA-256 동일. 이전 Experiment 0의 미커밋 엔진 계측 변경은 유지했으며 이번에는 엔진을 변경하지 않았다.', '',
'게임 설정 hash: `'+r['configHash']+'`. HEAD: `'+r['sourceCommit']+'` + JSON에 기록한 작업 파일 SHA-256. 전략 설정 JSON과 시드별 두 RNG는 결과에 보존했다.', '',
'Java 21과 Maven으로 `mvn package` 후 저장소 루트에서 실행한다. 결과 파일이 이미 있으면 실행을 거부하므로 재실행 시 새로운 출력 경로를 지정한다.', '',
'```bash',
'java -Xmx1200m -cp simulator/target/simulator-1.0.0-jar-with-dependencies.jar \\',
'  game.simulator.BalancedMenuExperiment config reports/experiment-1-balanced-menu.json',
'python3 scripts/report_balanced_menu.py reports/experiment-1-balanced-menu.json',
'```', '',
'원자료·분포·슬롯별 계측·시즌별 짝차이·출처 hash: [experiment-1-balanced-menu.json](experiment-1-balanced-menu.json). 기존 balance·observability 결과는 덮어쓰지 않았다.', '']
md_path.write_text('\n'.join(lines))
print(json.dumps({'core': [{k:v[k] for k in ['trainable','primary','fitness','leagueRank','reputation']} for v in comp], 'paired':r['pairedDifferences']},ensure_ascii=False,indent=2))
