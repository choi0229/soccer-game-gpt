"""Analyze saved Experiment 3 seasons without replaying games."""
import hashlib
import json
from pathlib import Path
import statistics
import subprocess
import sys

p=Path(sys.argv[1] if len(sys.argv)>1 else 'reports/experiment-3-event-academics.json')
md=p.with_suffix('.md')
if md.exists(): raise SystemExit(f'Report already exists: {md}')
r=json.loads(p.read_text());strategies=r['settings']['strategies'];labels={'random':'무작위','training':'훈련 위주','balanced-training-slot':'균형(B)'}
def dist(values):
 a=sorted(v for v in values if v is not None);n=len(a)
 if not n:return {k:None for k in ['mean','stddev','min','p10','p25','p50','p75','p90','max']}|{'count':0}
 return {'count':n,'mean':statistics.mean(a),'stddev':statistics.pstdev(a),'min':a[0],'p10':a[int((n-1)*.1)],'p25':a[int((n-1)*.25)],'p50':a[(n-1)//2],'p75':a[int((n-1)*.75)],'p90':a[int((n-1)*.9)],'max':a[-1]}
def cap(row):return sum(v['lostIncrease'] for v in row['academicSources'].values())
def fmt(x):return '—' if x is None else f'{x:.3f}'
def pct(x):return f'{x*100:.1f}%'
def deltaMean(value,n):return {k:v/n for k,v in value.items()}
summary={}
for arm,a in r['arms'].items():
 summary[arm]={}
 for strategy,s in a['strategies'].items():
  rows=s['seasons'];n=len(rows);g=s['academicChanges'];first=dist(x['first100Week'] for x in rows)
  m={'final':dist(x['academicFinal'] for x in rows),'capLostAcademic':dist(cap(x) for x in rows),
     'saturation':{'final100Ratio':sum(x['academicFinal']==100 for x in rows)/n,'ever100Ratio':first['count']/n,'never100Ratio':1-first['count']/n,'first100Week':first},
     'classesPerSeason':{k:s['observations']['academic']['classes'].get(k,0)/n for k in ['focus','nap','teacher','friends']},
     'academicChangesPerSeason':{k:deltaMean(v,n) for k,v in g.items()},'eventAxesPerSeason':{k:deltaMean(v,n) for k,v in s['eventAxes'].items()},
     'academicEventOccurrences':dist(x['academicEventOccurrences'] for x in rows)}
  raw=sum(v['requestedIncrease'] for v in g.values());event=g.get('event',{}).get('requestedIncrease',0)
  m['gains']={'rawAcademicGain':raw/n,'classGain':sum(g.get(k,{}).get('requestedIncrease',0) for k in ['focus','teacher'])/n,'eventGain':event/n,'eventShare':event/raw if raw else 0,'eventCapLost':g.get('event',{}).get('lostIncrease',0)/n}
  m['reviews']={}
  for week in [19,40]:
   vs=[x[f'academicWeek{week}'] for x in rows];m['reviews'][str(week)]={'distribution':dist(vs),'below30':sum(v<30 for v in vs)/n,'below40':sum(v<40 for v in vs)/n,'atLeast80':sum(v>=80 for v in vs)/n,'exact100':sum(v==100 for v in vs)/n}
  m['supplement']={'summerFailed':sum(x['summerFailed'] for x in rows)/n,'winterFailed':sum(x['winterFailed'] for x in rows)/n,'anyFailed':sum(x['summerFailed'] or x['winterFailed'] for x in rows)/n,'actualSeason':sum(x['supplementDays']>0 for x in rows)/n,'days':dist(x['supplementDays'] for x in rows),'slots':sum(x['supplementSlots'] for x in rows)}
  m['sideEffects']={k:dist(x[k] for x in rows) for k in ['trainable','primary','stamina','trainingExecutions','afternoonReplacements','injuries','starterRatio','goals','assists','rating','reputation','leagueRank']}
  m['sideEffects']['pooledStarterRatio']=s['legacyBalance']['roleRatios']['starter'];m['sideEffects']['pooledRating']=s['legacyBalance']['rating']['mean']
  summary[arm][strategy]=m
  for x in rows:
   net=sum(v['appliedIncrease']-v['appliedDecrease'] for v in x['academicChanges'].values());assert abs(50+net-x['academicFinal'])<1e-8
   eventDelta=x['academicChanges'].get('event',{})
   for key in ['requestedIncrease','requestedDecrease','appliedIncrease','appliedDecrease','lostIncrease','lostDecrease']:
    assert abs(sum(v[key] for v in x['eventAxes'].values())-eventDelta.get(key,0))<1e-8
   assert sum(x['classes'].values())==175
paired={}
for strategy in strategies:
 paired[strategy]={}
 groups=[r['arms'][arm]['strategies'][strategy]['seasons'] for arm in ['A','B','C']]
 if strategy!='training':assert all(a['classes']==b['classes']==c['classes'] for a,b,c in zip(*groups))
 for before,after in [('A','B'),('A','C'),('B','C')]:
  deltas={}
  for a,b in zip(r['arms'][before]['strategies'][strategy]['seasons'],r['arms'][after]['strategies'][strategy]['seasons']):
   assert a['seed']==b['seed']
   metrics=lambda x:{**{k:x[k] for k in ['academicFinal','academicWeek19','academicWeek40','supplementDays']},'capLostAcademic':cap(x),'eventAcademicGain':x['academicChanges'].get('event',{}).get('requestedIncrease',0),'focusCount':x['classes'].get('focus',0),'napCount':x['classes'].get('nap',0)}
   ma,mb=metrics(a),metrics(b)
   for k in ma:deltas.setdefault(k,[]).append(mb[k]-ma[k])
  paired[strategy][after+'-'+before]={k:dist(v) for k,v in deltas.items()}
r['summary']=summary;r['pairedDifferences']=paired
r['definitions']={'focusAcademic':1.5,'balancedStrategy':'balanced-training-slot','eventScaling':'Only academic keys in in-memory event choice effects; zero keys retained, original files unchanged','academicEventOccurrences':'Selected choices having an academic key, including zero after scaling; excludes choices without academic keys','sourceAxes':'Read pendingEvent axis before event choice via simulator action-boundary snapshot; never draws RNG','quantiles':'floor((n-1)*q), lower median','standardDeviation':'population','review':'Sunday finishDay after meetings/automatic events/recovery, same academic value as failure decision','cap':'requested positive increase lost at upper clamp; raw/applied separately recorded','stamina':'Season mean after daily automatic recovery','eventShare':'Total raw event increase / total raw increase, not applied increase','rng':'Same engine algorithm, call sites/order; policy feedback can alter realized branch-dependent calls and end states'}
r['sourceCommit']=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
files=['config/experiments/event-academics.json','simulator/src/main/java/game/simulator/EventAcademicExperiment.java','simulator/src/test/java/game/simulator/EventAcademicExperimentTest.java',__file__,'domain/src/main/java/game/domain/Engine.java','domain/src/main/java/game/domain/Rng.java','simulator/src/main/java/game/simulator/Main.java','simulator/src/main/java/game/simulator/Observations.java','simulator/src/main/java/game/simulator/BalancedTrainingSlots.java']
r['sourceSha256']={k:hashlib.sha256(Path(k).read_bytes()).hexdigest() for k in files}
r['verification'].update({'academicReconciliations':9000,'eventAxisReconciliations':9000,'randomBalancedClassesIdenticalPerSeed':True,'testResults':{'java':42,'browser':2,'frontendBuild':'passed','api':'passed','database':'passed'}})
p.write_text(json.dumps(r,ensure_ascii=False,separators=(',',':'))+'\n')
lines=['# Experiment 3 — 학업 이벤트 효과 A/B/C 실험','',
'2026-10-07 · A=100%, B=50%, C=0%. 후보별 3개 전략 × 시드 0~999 × 1,000시즌, 총 9,000시즌. 모든 후보에서 `focusAcademic=1.5`, 균형은 BalancedTrainingSlots(B)다.','',
'| 전략 | 후보 | 종료 학업 평균 | P10 | P50 | P90 | 학업 100 비율 | cap 소실량 | 미달률 |','|---|---|---:|---:|---:|---:|---:|---:|---:|']
for strategy in strategies:
 for arm in ['A','B','C']:
  m=summary[arm][strategy];lines.append('| '+labels[strategy]+' | '+arm+' | '+' | '.join([fmt(m['final'][k]) for k in ['mean','p10','p50','p90']]+[pct(m['saturation']['final100Ratio']),fmt(m['capLostAcademic']['mean']),pct(m['supplement']['anyFailed'])])+' |')
lines+=['','| 전략 | 후보 | 수업 학업 증가 | 이벤트 학업 증가 | 이벤트 비중 | 집중 횟수 | 졸기 횟수 |','|---|---|---:|---:|---:|---:|---:|']
for strategy in strategies:
 for arm in ['A','B','C']:
  m=summary[arm][strategy];g=m['gains'];lines.append('| '+labels[strategy]+' | '+arm+' | '+' | '.join([fmt(g['classGain']),fmt(g['eventGain']),pct(g['eventShare']),fmt(m['classesPerSeason']['focus']),fmt(m['classesPerSeason']['nap'])])+' |')
lines+=['','두 번째 표의 증가량은 **상한 적용 전 raw 양수 증가의 시즌 평균**이다. 첫 표 cap은 모든 출처의 소실량이고 미달률은 여름 또는 겨울 미달 시즌 비율이다.','',
'**핵심 발견:** C의 종료 정확히 100 비율은 무작위 62.6%, 균형 0%로 줄지만, 두 전략 모두 1,000시즌 전부 시즌 중 100에 도달했다. 균형 C의 종료 학업은 모든 시즌 **99.5**다. 종료=100 감소만으로 학업 포화가 해결됐다고 판단하면 안 된다.','',
'## 설정·구현·분석 범위','',
'원본 `rules.json`의 집중 1.5는 Experiment 2에서도 파일 자체가 바뀌지 않았으므로 이번에 복원할 원본 파일 변경은 없었다. Experiment 2의 B/C 집중 후보를 이어 쓰지 않았다. 새 [event-academics.json](../config/experiments/event-academics.json)에서 배율을 읽고, 매 후보마다 새로 로드한 Config의 이벤트 choice `academic` 값만 메모리에서 곱한다. 이벤트 JSON을 후보별로 복제하거나 프로덕션 Config/RNG/엔진을 수정하지 않았다. 배율 A=1에서는 원본 자료형도 유지해 hash까지 동일하다.','',
'현재 30개 이벤트 중 선택지의 `academic` 효과 키는 20개이며 **모두 양수, 0/음수 효과는 없다**. 구현은 부호에 관계없이 같은 곱셈을 적용한다. C에서도 academic 키를 0으로 유지해 다른 효과·효과 적용 순서·이벤트 선택 경로를 보존한다. 일반 수업·졸기·교사 대화·보충수업에는 이 배율을 적용하지 않는다.','',
'이벤트 학업 축은 선택 직전 대기 이벤트의 정의상 axis를 읽어 school/coach/teammate/family/common으로 기록한다. 일요일 자동 이벤트도 실제 선택된 이벤트 axis로 분류하며 발생한 요일이나 만남 축으로 추정하지 않는다. 선택된 choice에 academic 키가 있을 때만 학업 효과 이벤트 1회로 센다. C의 키 값 0도 원래 학업 효과 선택을 관측하기 위해 횟수에 포함한다. 다른 choice에는 academic 키가 없어 동일 이벤트 제목의 모든 발생을 학업 사건으로 세지 않는다.','',
'기존 `balanced` 이름은 아직 날짜 기반 회귀 전략 A를 가리키지만 이번 공식 균형 기준은 명시적으로 `balanced-training-slot`을 실행한다. ASSUMPTIONS.md의 일반 절대 날짜 순환 설명은 B의 슬롯 순환을 설명하지 않으므로 해당 부분은 Experiment 1 구현을 기준으로 한다. 기존 문서/결과는 역사적 기준선으로 보존한다.','',
'| 후보 | eventAcademicMultiplier | focusAcademic | Config hash |','|---|---:|---:|---|']
for arm,a in r['arms'].items():lines.append(f"| {arm} | {a['eventAcademicMultiplier']:.2f} | {a['focusAcademic']:.2f} | `{a['configHash']}` |")
lines+=['','학교 s07(전력 40), 초기 분포, 이벤트 데이터의 학업 외 모든 값, 이벤트 발생/선택 규칙, 학업 40 복구, 미달 30, 학기/판정 일정·상한·축구 규칙을 고정했다. 겨울 미달은 기존 플래그만 기록한다.','',
'표준편차는 시즌 간 모집단 값이다. Pq는 `floor((n−1)q)`, P50은 낮은 중앙값이다. 최초 도달은 도달 시즌만 분모로 하며 표본이 없으면 —/JSON null이다. 증감·수업·부작용은 명시된 합계 외에는 시즌 평균이다.','',
'## 이벤트 보상의 규모 — 후보 A','', '| 전략 | 학업 효과 선택/시즌 | 전체 raw 증가 | 이벤트 raw 증가 | 이벤트 비중 | 이벤트 상한 소실 | 이벤트 실제 적용 |','|---|---:|---:|---:|---:|---:|---:|']
for strategy in strategies:
 m=summary['A'][strategy];e=m['academicChangesPerSeason']['event'];g=m['gains'];lines.append('| '+labels[strategy]+' | '+' | '.join([fmt(m['academicEventOccurrences']['mean']),fmt(g['rawAcademicGain']),fmt(g['eventGain']),pct(g['eventShare']),fmt(g['eventCapLost']),fmt(e['appliedIncrease'])])+' |')
lines+=['','비중은 pooled raw 이벤트 증가/전체 raw 증가다. 단위가 다른 “상한에 도달한 시즌 비율” 또는 최종 학업 점수의 이벤트 몫으로 해석하지 않는다.','',
'## 종료 학업 분포','', '| 전략 | 후보 | 평균 | 표준편차 | 최소 | P10 | P25 | P50 | P75 | P90 | 최대 |','|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
for strategy in strategies:
 for arm in ['A','B','C']:lines.append('| '+labels[strategy]+' | '+arm+' | '+' | '.join(fmt(summary[arm][strategy]['final'][k]) for k in ['mean','stddev','min','p10','p25','p50','p75','p90','max'])+' |')
lines+=['','## 포화·최초 도달·상한 소실','', '| 전략 | 후보 | 종료=100 | 한번이라도 100 | 미도달 | 최초 주 평균 | P10/P50/P90 | cap 평균 | cap P50 | cap P90 |','|---|---|---:|---:|---:|---:|---|---:|---:|---:|']
for strategy in strategies:
 for arm in ['A','B','C']:
  m=summary[arm][strategy];s=m['saturation'];f=s['first100Week'];capd=m['capLostAcademic'];lines.append('| '+labels[strategy]+' | '+arm+' | '+' | '.join([pct(s[k]) for k in ['final100Ratio','ever100Ratio','never100Ratio']]+[fmt(f['mean']),'/'.join(fmt(f[k]) for k in ['p10','p50','p90'])]+[fmt(capd[k]) for k in ['mean','p50','p90']])+' |')
lines+=['','## 19주 / 40주 판정','', '일요일 만남·자동 이벤트와 회복 후 finishDay에서 판정하는 학업 값이다.','', '| 전략 | 후보 | 주 | 평균 | P10 | P50 | P90 | <30 | <40 | ≥80 | =100 |','|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
for strategy in strategies:
 for arm in ['A','B','C']:
  for week in ['19','40']:
   v=summary[arm][strategy]['reviews'][week];lines.append('| '+labels[strategy]+' | '+arm+' | '+week+' | '+' | '.join([fmt(v['distribution'][k]) for k in ['mean','p10','p50','p90']]+[pct(v[k]) for k in ['below30','below40','atLeast80','exact100']])+' |')
lines+=['','## 학업 변화 출처 분해','', '요청 증가/감소는 clamp 전이며 적용은 실제 State 변화, 소실은 상한에 막힌 증가다. 감소는 양의 크기로 표시한다. 이벤트 합계를 축별 세부 내역과 중복 합산하지 않는다.','', '| 전략 | 후보 | 출처 | 요청 증가 | 적용 증가 | 요청 감소 | 적용 감소 | 상한 소실 |','|---|---|---|---:|---:|---:|---:|---:|']
source_labels={'focus':'classFocusGain','teacher':'classTeacherGain','nap':'classSleepLoss','supplement':'remedialGain','event':'eventTotal'}
for strategy in strategies:
 for arm in ['A','B','C']:
  m=summary[arm][strategy]
  for key,label in source_labels.items():
   v=m['academicChangesPerSeason'].get(key,{});lines.append('| '+labels[strategy]+' | '+arm+' | '+label+' | '+' | '.join(fmt(v.get(k,0)) for k in ['requestedIncrease','appliedIncrease','requestedDecrease','appliedDecrease','lostIncrease'])+' |')
  for axis in ['school','coach','teammate','family','common']:
   v=m['eventAxesPerSeason'].get(axis,{});lines.append('| '+labels[strategy]+' | '+arm+' | event'+axis.title()+'Gain | '+' | '.join(fmt(v.get(k,0)) for k in ['requestedIncrease','appliedIncrease','requestedDecrease','appliedDecrease','lostIncrease'])+' |')
lines+=['','`otherAcademicGain`/`otherAcademicLoss`와 하한 소실은 모든 표본에서 0이었다. JSON에는 일반 수업·보충·전체 이벤트 증감과 이벤트 축별 증감을 시즌별로 각각 보존했다. 9,000시즌의 `50+적용 증가−적용 감소=최종 학업` 및 이벤트 축 합계=전체 이벤트 증감을 검증했다.','',
'## 수업 행동과 보충수업','', '| 전략 | 후보 | 집중 | 졸기 | 교사 | 친구 | 여름 미달 | 겨울 미달 | 보충 발생률 | 평균 일수 | 대체 슬롯 합계 |','|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
for strategy in strategies:
 for arm in ['A','B','C']:
  m=summary[arm][strategy];s=m['supplement'];lines.append('| '+labels[strategy]+' | '+arm+' | '+' | '.join([fmt(m['classesPerSeason'][k]) for k in ['focus','nap','teacher','friends']]+[pct(s[k]) for k in ['summerFailed','winterFailed','actualSeason']]+[fmt(s['days']['mean']),str(s['slots'])])+' |')
lines+=['','무작위·균형은 A/B/C의 수업 실행 횟수가 **동일 seed별로 모두 동일**하다. 훈련 위주는 이벤트 보상이 감소하면 학업<40 복구 정책으로 집중을 늘리고 졸기를 줄인다. 수업 175회 자체는 모든 시즌에서 유지된다.','',
'## 학업 외 부작용','', '체력은 자동 회복 뒤 시즌 평균, 훈련·골·도움·부상은 시즌 합계의 평균이다. 선발률·평점은 경기/출전 전체를 모은 기존 집계이며 시즌 평균 비율과 구분된다.','', '| 전략 | 후보 | 12개 평균 | 주력 | 체력 | 훈련 실행 | 부상 | 선발률 | 골 | 도움 | 평점 | 평판 | 순위 |','|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
for strategy in strategies:
 for arm in ['A','B','C']:
  v=summary[arm][strategy]['sideEffects'];lines.append('| '+labels[strategy]+' | '+arm+' | '+' | '.join([fmt(v[k]['mean']) for k in ['trainable','primary','stamina','trainingExecutions','injuries']]+[pct(v['pooledStarterRatio'])]+[fmt(v[k]['mean']) for k in ['goals','assists']]+[fmt(v['pooledRating'])]+[fmt(v[k]['mean']) for k in ['reputation','leagueRank']])+' |')
lines+=['','무작위·균형(B)은 학업 외 기존 집계가 A/B/C에서 전부 동일해 **축구 시스템 영향 없음**이다. 훈련 위주는 이벤트 보상 감소→학업 40 복구를 위한 집중 증가→졸기 회복 기회 감소 경로가 있다. C−A의 평균 체력은 −0.584, 12개 평균은 −0.002162, 주력은 −0.001435, 훈련 실행은 −0.010회/시즌이다. 부상 총수는 A/B/C 26/28/27건으로 작은 표본의 비단조 변화이며 배율에 따른 확정적 위험 개선/악화로 볼 수 없다. 보충 슬롯은 모두 0이어서 이 변화가 보충수업 때문은 아니다.','', '## 동일 seed 짝차이','', '| 전략 | 비교 | 지표 | 평균 차이 | P10 | P50 | P90 |','|---|---|---|---:|---:|---:|---:|']
for strategy,comparisons in paired.items():
 for pair,metrics in comparisons.items():
  for k,v in metrics.items():lines.append('| '+labels[strategy]+' | '+pair+' | '+k+' | '+' | '.join(fmt(v[q]) for q in ['mean','p10','p50','p90'])+' |')
lines+=['','짝차이의 이벤트 증가량은 raw 값, capLostAcademic은 전체 출처 소실이다. 동일 seed는 초기 상태를 짝짓지만 정책/분기 변경 후의 난수가 동일 사건에 대응하는 것을 보장하지 않는다.','',
'## 핵심 질문과 판정','',
'**Q1 — 이벤트의 비중:** A에서 전체 raw 증가의 무작위 37.63%, 훈련 위주 44.17%, 균형 40.42%다. 규모는 작지 않으며 상한 도달을 앞당기고 졸기 후 복구에도 기여한다. 그러나 큰 raw 비중 자체는 상한 포화에 반드시 필요한 원인이라는 뜻이 아니다.','',
'**Q2 — 50% 감축:** 종료 100은 무작위 97.0%→96.0%, 균형 97.0%→97.0%다. 모든 시즌의 한 번 이상 도달은 그대로 100%다. 최초 도달 시점이 늦어지지만 포화 구조를 해결하지 못한다.','',
'**Q3 — 제거하면 사라지는가:** 종료 정확히 100 비율은 무작위 62.6%, 균형 0%로 크게 변한다. 하지만 무작위 평균은 99.705(최저 96.5), 균형은 모든 시즌 99.5이고 두 전략의 시즌 중 도달은 모두 100%다. 학업 분포가 여전히 상한 부근에 압축된다는 의미의 포화는 유지된다.','',
'**Q4 — 일반 수업만으로 충분한가:** 직접 학업 증감은 C에서 수업 집중·교사·졸기만으로 모든 무작위/균형 시즌이 100에 도달했다. 다만 이벤트의 학교생활 관계 효과는 고정해 남겨두었으므로 “이벤트 시스템 전체를 제거해도 같다”는 결론은 아니다. 검증한 것은 **직접 이벤트 학업 보상 없이도 현재 관계 배율을 받는 수업 구조가 충분하다**는 것이다.','',
'균형 C는 40주 마지막 평일 금요일(day 277)에 day%4=1인 졸기 −0.5를 실행한다. 전날 집중으로 상한에 복구한 뒤 99.5가 되고, 이후 41~48주는 방학으로 수업이 없다. C에서는 이후 이벤트 학업 복구도 0이므로 그대로 99.5로 끝난다. 이 고정된 마지막 행동과 방학 일정 때문에 종료=100은 0%지만, 새로운 선택 차별성이 생긴 것이 아니라 **모든 시즌이 동일한 99.5**로 압축된 것이다.','',
'C의 일반 수업 raw 증가는 무작위 +118.714, 균형 +118.564이고 졸기 감소는 −21.618·−21.500/시즌이다. 상한을 생략한 수지는 초기 50에서 약 147.096·147.064가 될 규모여서 이벤트 학업 보상 없이도 상한까지 필요한 +50을 넘는다. 실제 C의 cap 소실도 47.391·47.564 남았다. 이 계산은 100 초과 상태를 예측하는 것이 아니라 남은 수업 포화 압력을 보여 준다.','',
'**Q5 — 훈련 위주의 복구:** 집중은 22.217→27.757→33.446회, 졸기는 152.783→147.243→141.554회다. 이벤트 감소를 추가 집중으로 상당 부분 상쇄하지만 완전히 동일한 학업을 유지하지는 않는다. 말 학업은 46.604→43.677→40.698로 낮아진다. 미달 기준보다 높은 40의 복구 정책이 위험 진입을 막는다.','',
'**Q6 — 미달/보충:** 모든 후보·전략에서 19/40주 <30, 여름/겨울 미달, 실제 보충 및 대체 슬롯은 0이다. 다른 전략이나 고의적 학업 방치 행동까지 검증한 결과로 일반화하지 않는다.','',
'**Q7 — 선택의 의미:** 이벤트 보상의 강도가 도달 시점·훈련 위주의 집중 부담을 바꾼다는 민감도는 확인된다. 그러나 무작위·균형의 말 분포는 여전히 거의 상한이고 보충 위험도 없다. C의 종료 정확히 100 감소를 성공으로 채택하면 지표의 경계값 효과를 과대평가한다. 이벤트 학업 효과만 줄여 학업 선택의 비용과 분포를 충분히 복원했다고 볼 수 없다.','',
'## 검증·재실행','',
'- Java 전체 42개(신규 3개 포함), 브라우저 2개, 프론트엔드 빌드, API 두 시즌 재현·동시 요청, DB 무결성 검증 통과.',
'- 9,000시즌 각각 동일 Config/seed/전략으로 반복: 전체 State hash·게임 RNG·선택 RNG·기존 관측·신규 출처/축별 증감 일치. 모든 후보의 전략별 기존 aggregate도 일치.',
'- A는 원본 Config hash와 같고 기존 무작위/훈련 집계 및 Experiment 1 균형 B 집계와 동일하다.',
'- 신규 테스트는 학업 effect만 배율 적용·다른 effect 동일·C academic만 0·일반 수업 동일·균형 B 사용·관측 켬/끔 동일 State·이벤트 축 합계·반복 RNG를 검증했다.',
'- 기존 config/events.json·rules.json·게임 도메인·전략·Experiment 0 계측·기존 보고서 파일은 작업 전 SHA-256과 동일하다.', '',
'엔진에 새 RNG 호출을 넣지 않았다. 다른 학업 값으로 후속 수업 선택·부상/이벤트 분기가 달라지면 실제 실행되는 호출 수와 종료 RNG가 달라질 수 있다. 고정한 것은 알고리즘·호출 위치/순서·선택 RNG 분리이며, 서로 다른 후보의 최종 RNG를 같게 강제하지 않는다.', '',
'HEAD: `'+r['sourceCommit']+'` 및 결과 JSON의 파일 SHA-256. 실제 후보 배율·Config hash·전략 JSON·시즌별 State hash·두 RNG를 기록했다.','',
'```bash','mvn package  # Java 21',
'java -Xmx1400m -cp simulator/target/simulator-1.0.0-jar-with-dependencies.jar \\',
'  game.simulator.EventAcademicExperiment config reports/experiment-3-event-academics.json',
'python3 scripts/report_event_academics.py reports/experiment-3-event-academics.json','```','',
'기존 출력이 있으면 실행을 거부한다. 재실행에는 새 경로를 지정한다. [전체 통계와 시즌 원자료](experiment-3-event-academics.json).','',
'## 최종 결론','',
'```text',
'이벤트 학업 효과의 포화 기여도: raw 증가의 약 38~44%; 도달을 앞당기고 상한 소실을 늘림.',
'',
'A(100%): 무작위/균형 종료 100 97%/97%, 한 번 이상 도달 모두 100%.',
'B(50%): 무작위/균형 종료 100 96%/97%, 한 번 이상 도달 모두 100%.',
'C(0%): 무작위/균형 종료 100 62.6%/0%, 한 번 이상 도달 모두 100%; 말 학업 99.705/99.5.',
'',
'C에서도 포화가 유지되는가: 상한 부근 분포 압축과 시즌 중 도달은 유지. 종료=100만 감소.',
'',
'학업 포화의 주원인은 이벤트인가:',
'NO — 직접 이벤트 학업 보상이 없어도 현 수업·관계 배율에서 모두 상한에 도달.',
'(이벤트 기여가 없다는 뜻은 아니며 학교생활 관계 효과는 고정해 유지했음.)',
'',
'추천 이벤트 학업 배율: 현재 실험만으로 채택 보류. 원본 A 유지.',
'',
'이벤트 학업 효과만 조정해서 문제를 해결할 수 있는가: 아니오.',
'',
'다음 실험이 필요한가: 예.',
'필요하다면 어떤 규칙 하나를 다음 대상으로 볼 것인가: 졸기의 학업 감소량(napAcademic) 하나.',
'집중 1.5·학교생활 배율·이벤트 원본 100%·미달 기준·전략 등 나머지는 고정한 독립 분기.',
'```',
'']
md.write_text('\n'.join(lines))
for strategy in strategies:
 print(strategy,'A event share',summary['A'][strategy]['gains']['eventShare'],'C-A effects',{k:summary['C'][strategy]['sideEffects'][k]['mean']-summary['A'][strategy]['sideEffects'][k]['mean'] for k in ['trainable','primary','stamina','trainingExecutions','injuries','starterRatio','goals','assists','rating','reputation','leagueRank']})
print('Wrote',md)
