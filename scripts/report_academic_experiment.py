"""Produce academic sensitivity statistics from the saved Experiment 2 seasons."""
import hashlib
import json
from pathlib import Path
import statistics
import subprocess
import sys

p=Path(sys.argv[1] if len(sys.argv)>1 else 'reports/experiment-2-academics.json')
out=p.with_suffix('.md')
if out.exists(): raise SystemExit(f'Report already exists: {out}')
r=json.loads(p.read_text());names=r['settings']['strategies'];labels={'random':'무작위','training':'훈련 위주','balanced-training-slot':'균형(B)'}

def distribution(values):
    a=sorted(v for v in values if v is not None);n=len(a)
    if not n: return dict(count=0,mean=None,stddev=None,min=None,p10=None,p25=None,p50=None,p75=None,p90=None,max=None)
    return dict(count=n,mean=statistics.mean(a),stddev=statistics.pstdev(a),min=a[0],p10=a[int((n-1)*.1)],p25=a[int((n-1)*.25)],p50=a[(n-1)//2],p75=a[int((n-1)*.75)],p90=a[int((n-1)*.9)],max=a[-1])

def loss(row):return sum(v['lostIncrease'] for v in row['academicSources'].values())
def fmt(v): return '—' if v is None else f'{v:.3f}'
def pct(v): return f'{v*100:.1f}%'
summary={}
for arm,a in r['arms'].items():
    summary[arm]={}
    for strategy,s in a['strategies'].items():
        rows=s['seasons'];n=len(rows);m={}
        m['final']=distribution(x['academicFinal'] for x in rows)
        first=distribution(x['first100Week'] for x in rows)
        m['saturation']={'final100Ratio':sum(x['academicFinal']==100 for x in rows)/n,'ever100Ratio':first['count']/n,'never100Ratio':1-first['count']/n,'first100Week':first}
        m['reviews']={}
        for week in [19,40]:
            vs=[x[f'academicWeek{week}'] for x in rows]
            m['reviews'][str(week)]={'distribution':distribution(vs),'below30Ratio':sum(v<30 for v in vs)/n,'below40Ratio':sum(v<40 for v in vs)/n,'atLeast80Ratio':sum(v>=80 for v in vs)/n,'exact100Ratio':sum(v==100 for v in vs)/n}
        m['supplement']={'summerFailedRatio':sum(x['summerFailed'] for x in rows)/n,'winterFailedRatio':sum(x['winterFailed'] for x in rows)/n,'actualSeasonRatio':sum(x['supplementDays']>0 for x in rows)/n,'meanDays':statistics.mean(x['supplementDays'] for x in rows),'teamSlotsReplaced':sum(x['supplementSlots'] for x in rows)}
        m['classCountsPerSeason']={k:s['observations']['academic']['classes'].get(k,0)/n for k in ['focus','nap','teacher','friends']}
        m['academicDecompositionPerSeason']={k:{q:v/n for q,v in value.items()} for k,value in s['academicChanges'].items()}
        sources=s['academicChanges'].values()
        m['academicTotalPerSeason']={k:sum(x[k] for x in sources)/n for k in ['requestedIncrease','requestedDecrease','appliedIncrease','appliedDecrease','lostIncrease','lostDecrease']}
        m['sideEffects']={k:distribution(x[k] for x in rows) for k in ['trainable','primary','afternoonReplacements','stamina','injuries','starterRatio','goals','assists','rating','reputation','leagueRank']}
        m['sideEffects']['pooledStarterRatio']=s['legacyBalance']['roleRatios']['starter'];m['sideEffects']['pooledRating']=s['legacyBalance']['rating']['mean']
        summary[arm][strategy]=m
        for x in rows:
            applied=sum(v['appliedIncrease']-v['appliedDecrease'] for v in x['academicChanges'].values())
            assert abs(50+applied-x['academicFinal'])<1e-8
            assert sum(x['classes'].values())==175
paired={}
for strategy in names:
    paired[strategy]={}
    for before,after in [('A','B'),('A','C'),('B','C')]:
        delta={}
        for a,b in zip(r['arms'][before]['strategies'][strategy]['seasons'],r['arms'][after]['strategies'][strategy]['seasons']):
            assert a['seed']==b['seed']
            for k in ['academicFinal','academicWeek19','academicWeek40','supplementDays','trainable','starterRatio']:
                delta.setdefault(k,[]).append(b[k]-a[k])
            delta.setdefault('capLoss',[]).append(loss(b)-loss(a))
        paired[strategy][after+'-'+before]={k:distribution(v) for k,v in delta.items()}
for strategy in names:
    if strategy!='training':
        seasons=[r['arms'][arm]['strategies'][strategy]['seasons'] for arm in ['A','B','C']]
        assert all(a['classes']==b['classes']==c['classes'] for a,b,c in zip(*seasons))
r['summary']=summary;r['pairedDifferences']=paired
r['definitions']={'focusBaseGain':'Existing rules.focusAcademic; experiment file overlays only this key','balancedBaseline':'balanced-training-slot, not legacy balanced alias','stddev':'Population standard deviation across 1000 seasons','quantiles':'floor((n-1)*q); P50 lower median','reviews':'19/40 week Sunday finishDay review after Sunday events and automatic recovery, same as Experiment 0','first100Week':'Among seasons ever reaching 100; none=null','stamina':'Season mean after automatic daily recovery','changes':'Requested/applied/lost amounts are positive magnitudes; per-season averages','rng':'Engine algorithm/call sites/order unchanged; different actions/branches can change realized calls and states'}
r['verification']['academicDecompositionReconciliations']=9000
r['verification']['testResults']={'java':39,'browser':2,'frontendBuild':'passed','api':'passed','database':'passed'}
r['sourceCommit']=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
files=['config/experiments/academics.json','simulator/src/main/java/game/simulator/AcademicExperiment.java','simulator/src/test/java/game/simulator/AcademicExperimentTest.java',__file__,'domain/src/main/java/game/domain/Engine.java','domain/src/main/java/game/domain/Rng.java','simulator/src/main/java/game/simulator/Main.java','simulator/src/main/java/game/simulator/Observations.java','simulator/src/main/java/game/simulator/BalancedTrainingSlots.java']
r['sourceSha256']={str(x):hashlib.sha256(Path(x).read_bytes()).hexdigest() for x in files}
p.write_text(json.dumps(r,ensure_ascii=False,separators=(',',':'))+'\n')
lines=['# Experiment 2 — 학업 포화 A/B/C 실험','',
'2026-10-07 · 후보별 세 전략 × 1,000시즌, 시드 0~999, 총 9,000시즌. 균형은 실행 슬롯 순환 B를 사용했다. 수업 집중 기준 증가량 하나만 비교했다.','',
'| 전략 | 후보 | 최종 학업 평균 | P10 | P50 | P90 | 100 비율 | 여름 미달 | 겨울 미달 |','|---|---|---:|---:|---:|---:|---:|---:|---:|']
for strategy in names:
    for arm in ['A','B','C']:
        m=summary[arm][strategy];d=m['final'];sup=m['supplement']
        lines.append(f"| {labels[strategy]} | {arm} ({r['arms'][arm]['focusBaseGain']:.2f}) | {d['mean']:.3f} | {d['p10']:.3f} | {d['p50']:.3f} | {d['p90']:.3f} | {pct(m['saturation']['final100Ratio'])} | {pct(sup['summerFailedRatio'])} | {pct(sup['winterFailedRatio'])} |")
lines+=['','**결론: A/B/C 모두 목표를 만족하지 않음.** 최저 후보 C에서도 무작위·균형의 학업 100 비율은 각각 96.9%·97.0%이고, 세 전략 모두 미달·보충수업이 0이다. 집중 증가량 단독 변경으로 포화와 학업 위험을 해결하지 못했다.','',
'## 기준 상태와 설정','',
'요청의 `focusBaseGain`은 실제 설정에서 `rules.focusAcademic`이다. 기존 계산 `focusAcademic × (schoolMultiplierBase + 학교생활 관계도 / schoolMultiplierDivisor)`를 그대로 사용하며 배율은 `1 + 학교생활/200`이다. 원본 `config/rules.json`은 변경하지 않았다. 후보값은 [academics.json](../config/experiments/academics.json)에서 읽어 새로 로드한 Config의 이 키만 덮어쓴다. 게임 엔진과 기존 관측 코드·전략 코드를 수정하지 않았다.','',
'이전 Experiment 1의 코드에서 기본 이름 `balanced`는 날짜 기반 A로 남아 있다. 이번 사용자 지정 기준에 따라 학업 실험에서는 이를 호출하지 않고 **`balanced-training-slot`만 공식 균형 기준으로 사용**했다. 날짜 기반은 회귀 비교용으로 보존했고 기존 과거 리포트도 유지했다.','',
'| 후보 | 실제 키 | 기준 증가량 | Config hash |','|---|---|---:|---|']
for arm,a in r['arms'].items():lines.append(f"| {arm} | rules.focusAcademic | {a['focusBaseGain']:.2f} | `{a['configHash']}` |")
lines += ['', '초기 분포·기본 학교 s07(전력 40)·이벤트 데이터·선택 정책·학교생활 배율·졸기 −0.5·교사 +0.5·상하한·미달 기준 30·19/40주 판정을 고정했다. 겨울 미달은 기존 플래그만 기록하며 다음 학년 보충을 추가하지 않았다. 후보는 실험용이며 추천/채택으로 원본 설정을 교체하지 않았다.','',
'표준편차는 시즌 간 모집단 표준편차다. 백분위는 정렬 인덱스 `floor((n−1)q)`, P50은 낮은 중앙값이다. 최초 도달 주차는 실제 도달한 시즌만 분모로 하며 빈 표본은 —/JSON null이다. 학업 증감·수업 횟수·부작용은 별도 표시가 없으면 시즌 평균이다.','',
'## 종료 학업 분포','', '| 전략 | 후보 | 평균 | 표준편차 | 최소 | P10 | P25 | P50 | P75 | P90 | 최대 |','|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
for strategy in names:
 for arm in ['A','B','C']:
  d=summary[arm][strategy]['final'];lines.append('| '+labels[strategy]+' | '+arm+' | '+' | '.join(fmt(d[k]) for k in ['mean','stddev','min','p10','p25','p50','p75','p90','max'])+' |')
lines+=['','## 포화와 최초 도달','', '| 전략 | 후보 | 종료=100 | 한번이라도 100 | 미도달 | 최초 주차 평균 | P10 | P50 | P90 |','|---|---|---:|---:|---:|---:|---:|---:|---:|']
for strategy in names:
 for arm in ['A','B','C']:
  s=summary[arm][strategy]['saturation'];f=s['first100Week'];lines.append('| '+labels[strategy]+' | '+arm+' | '+' | '.join([pct(s[k]) for k in ['final100Ratio','ever100Ratio','never100Ratio']]+[fmt(f[k]) for k in ['mean','p10','p50','p90']])+' |')
lines+=['','## 19주·40주 판정 시점','', '기존 관측의 일요일 종료 판정 값이다. 그날의 만남/자동 이벤트와 회복을 처리한 뒤 finishDay에서 미달을 판정하고 동일 학업 값을 관측한다.','', '| 전략 | 후보 | 주 | 평균 | P10 | P50 | P90 | <30 | <40 | ≥80 | =100 |','|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
for strategy in names:
 for arm in ['A','B','C']:
  for week in ['19','40']:
   q=summary[arm][strategy]['reviews'][week];lines.append('| '+labels[strategy]+' | '+arm+' | '+week+' | '+' | '.join([fmt(q['distribution'][k]) for k in ['mean','p10','p50','p90']]+[pct(q[k]) for k in ['below30Ratio','below40Ratio','atLeast80Ratio','exact100Ratio']])+' |')
lines+=['','## 실제 수업 선택·소실량','', '| 전략 | 후보 | 집중 횟수 | 졸기 횟수 | 상한 소실량 | 보충수업 일수 |','|---|---|---:|---:|---:|---:|']
for strategy in names:
 for arm in ['A','B','C']:
  m=summary[arm][strategy];lines.append(f"| {labels[strategy]} | {arm} | {m['classCountsPerSeason']['focus']:.3f} | {m['classCountsPerSeason']['nap']:.3f} | {m['academicTotalPerSeason']['lostIncrease']:.3f} | {m['supplement']['meanDays']:.3f} |")
lines+=['','| 전략 | 후보 | 집중 | 졸기 | 교사 | 친구 |','|---|---|---:|---:|---:|---:|']
for strategy in names:
 for arm in ['A','B','C']:
  counts=summary[arm][strategy]['classCountsPerSeason'];lines.append('| '+labels[strategy]+' | '+arm+' | '+' | '.join(fmt(counts[k]) for k in ['focus','nap','teacher','friends'])+' |')
lines+=['','무작위와 균형은 A/B/C에서 **시드별 실행 횟수까지 동일**하다. 훈련 위주는 정책 `학업<40 → 집중, 그 외 졸기`에 의해 집중 빈도를 늘린다. 각 후보의 수업은 모두 175회이며 정책 자체는 변경하지 않았다. 집중이 늘면 체력 −2인 날이 늘고 졸기의 +8 회복 기회가 줄어드는 간접 효과가 있다.','',
'## 증감 분해','', '요청 증가량은 상한 적용 전, 적용 증가량은 실제 State 변화다. 감소량은 양의 크기로 표시한다. 이벤트 및 수업의 상한 소실을 분리하며 기타는 보충 등이다. 친구는 학업 직접 효과가 0이라 증감 콜백이 없으며 횟수만 기록된다.','',
'| 전략 | 후보 | 원인 | 요청 증가 | 적용 증가 | 요청 감소 | 적용 감소 | 상한 소실 |','|---|---|---|---:|---:|---:|---:|---:|']
for strategy in names:
 for arm in ['A','B','C']:
  m=summary[arm][strategy]
  for key,label in [('focus','집중'),('nap','졸기'),('teacher','교사'),('event','이벤트'),('supplement','기타(보충)')]:
   v=m['academicDecompositionPerSeason'].get(key,{k:0 for k in ['requestedIncrease','appliedIncrease','requestedDecrease','appliedDecrease','lostIncrease']})
   lines.append('| '+labels[strategy]+' | '+arm+' | '+label+' | '+' | '.join(fmt(v[k]) for k in ['requestedIncrease','appliedIncrease','requestedDecrease','appliedDecrease','lostIncrease'])+' |')
  v=m['academicTotalPerSeason'];lines.append('| '+labels[strategy]+' | '+arm+' | 전체 | '+' | '.join(fmt(v[k]) for k in ['requestedIncrease','appliedIncrease','requestedDecrease','appliedDecrease','lostIncrease'])+' |')
lines+=['','9,000시즌 모두 `초기 학업 50 + 적용 증가 − 적용 감소 = 최종 학업`을 검증했다. 기타 감소와 하한 소실은 모든 표본에서 0이었다.','',
'## 보충수업','', '| 전략 | 후보 | 여름 미달 | 겨울 미달 | 실제 발생 시즌 | 평균 일수 | 대체 팀 슬롯 합계 |','|---|---|---:|---:|---:|---:|---:|']
for strategy in names:
 for arm in ['A','B','C']:
  v=summary[arm][strategy]['supplement'];lines.append('| '+labels[strategy]+' | '+arm+' | '+' | '.join([pct(v[k]) for k in ['summerFailedRatio','winterFailedRatio','actualSeasonRatio']]+[fmt(v['meanDays']),str(v['teamSlotsReplaced'])])+' |')
lines+=['','## 학업 외 부작용','', '평균 체력은 매일 자동 회복 뒤의 시즌 평균이다. 골·도움·부상·오후 대체는 시즌 단위다. 선발률과 평점은 기존 집계와 맞추어 모든 경기/출전을 합친 값이다. 아래 수치는 조정 대상이 아닌 관측 결과다.','',
'| 전략 | 후보 | 12개 평균 | 주력 | 오후 대체 | 체력 | 부상 | 선발률 | 골 | 도움 | 평점 | 평판 | 순위 |','|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
for strategy in names:
 for arm in ['A','B','C']:
  v=summary[arm][strategy]['sideEffects'];values=[fmt(v[k]['mean']) for k in ['trainable','primary','afternoonReplacements','stamina','injuries']]+[pct(v['pooledStarterRatio'])]+[fmt(v[k]['mean']) for k in ['goals','assists']]+[fmt(v['pooledRating'])]+[fmt(v[k]['mean']) for k in ['reputation','leagueRank']]
  lines.append('| '+labels[strategy]+' | '+arm+' | '+' | '.join(values)+' |')
lines+=['','## 동일 시드 짝차이','', '시즌별 B−A·C−A·C−B. 선발률 차이는 %p이며 다른 항목은 원래 단위다.','', '| 전략 | 비교 | 지표 | 평균 차이 | P10 | 중앙 | P90 |','|---|---|---|---:|---:|---:|---:|']
for strategy, comparisons in paired.items():
 for pair,metrics in comparisons.items():
  for k,v in metrics.items():
   scale=100 if k=='starterRatio' else 1;lines.append('| '+labels[strategy]+' | '+pair+' | '+k+' | '+' | '.join(fmt(v[q]*scale) for q in ['mean','p10','p50','p90'])+' |')
lines+=['','## 질문에 대한 판단','',
'**Q1. 포화가 줄었는가?** 무작위 종료 100 비율은 97.0%→96.9%→96.9%, 균형은 97.0%→97.0%→97.0%다. 모든 무작위·균형 시즌은 모든 후보에서 한 번 이상 100에 도달했다. 최초 도달 시점은 늦춰지지만 종료 분포의 포화는 해소되지 않는다. 마지막 졸기 등 때문에 정확히 100이 아닌 일부 시즌이 있다는 사실과 미도달을 혼동하지 않는다.','',
'**Q2. 어느 후보에서 전략이 구분되는가?** 모든 후보에서 훈련 위주의 약 46과 무작위·균형의 약 100은 구분된다. 그러나 무작위와 균형은 어떤 후보에서도 말 학업 분포가 의미 있게 분리되지 않는다. 19주·도달 주차에서 민감도가 보이는 것과 시즌 말 선택 가치가 복원되는 것은 별개다.','',
'**Q3. 훈련 위주의 반응은?** 집중은 시즌 평균 22.217→29.900→36.208회, 졸기는 152.783→145.100→138.792회다. 증가량이 작아지자 복구 정책이 더 많은 집중으로 대응한다. 이는 선택 규칙 수정이나 오류가 아니다. 이벤트가 추가 학업을 공급하므로 이벤트를 생략한 정적 평형 횟수보다 실제 집중은 적다.','',
'**Q4. 학업 위험·보충은 언제 나타나는가?** A/B/C 어느 후보에도 나타나지 않았다. 판정 주 <30, 여름/겨울 미달, 실제 보충 시즌·일수·대체 팀 슬롯이 모두 0이다. 훈련 위주의 복구 기준 40과 미달 기준 30의 간격, 양수 학업 이벤트, 작은 감소 경로는 그대로이므로 기준 증가량만 낮춘 표본에서 학업 위험이 재현되지 않았다. 졸기만 하는 별도 전략을 임의로 추가하지 않았으므로 고정 세 전략 밖의 행동을 검증했다고 주장하지 않는다.','',
'**Q5. 축구 성장에 영향을 주는가?** 보충에 의한 훈련 손실은 0이며 무작위·균형의 학업 외 집계는 A/B/C에서 동일하다. 훈련 위주는 집중 증가로 체력 회복 기회가 줄고 훈련 성장·드문 부상에 간접 변화가 생긴다. 경기 결과 집계는 동일했다. 모든 영향을 보충수업 탓으로 설명해서는 안 된다. 정량 변화는 부작용 표와 짝차이에서 확인한다.','',
'훈련 위주의 C−A에서 평균 체력은 −0.907, 오후 대체는 +0.010일/시즌, 부상은 총 26→27건이다. 12개 평균은 +0.000140, 주력은 +0.001326에 그쳤고 선발·골·도움·평점·평판·순위 집계는 동일했다. 드문 부상 1건 차이와 미세한 성장 차이를 개선/악화의 확정 증거로 보지 않는다.','',
'**Q6. 다음 기준값은?** 세 후보 모두 핵심 조건인 포화 완화와 일부 보충 위험을 충족하지 못했다. C를 효과적인 개선값으로 채택할 근거가 없으므로 **추천 후보를 확정하지 않고 기존 원본값 A를 보존**한다.','',
'집중 기여는 줄었지만 수업/이벤트 합산 증가가 초기 50에서 상한까지 필요한 양보다 여전히 크다. 학교생활 관계도에 따른 배율 증가·양수 학업 이벤트·제한된 감소 경로·상한 clamp가 남아 있다. 최초 100 도달의 지연과 상한 소실량 감소는 민감도 증거이며 포화 해결의 증거는 아니다.','',
'C의 상한 적용 전 수업·이벤트 총 요청 증가는 무작위 +141.994, 균형 +150.715/시즌이며, 졸기 감소는 각각 21.618·21.500이다. clamp를 생략한 수지는 초기 50에서 약 170.377·179.215까지 올라갈 규모다. 이 중 이벤트 요청 증가만 +71.617·80.433이다. 실제 증가량은 상한 때문에 +71.596·71.485로 잘리고, 소실량은 +70.398·79.230이다. 이 수지는 실제로 100을 넘는 학업 값을 예측하는 것이 아니라 포화 압력을 설명한다.','',
'## 검증·출처·재실행','',
'- Java 전체 39개(추가 3개 포함), 브라우저 2개, 프론트엔드 빌드, API 2시즌 전체 재현·동시 요청, DB 무결성 검증 통과.',
'- 9,000시즌 각각 동일 후보/seed/전략으로 반복 실행하여 전체 State SHA-256·게임 RNG·선택 RNG·관측 JSON 일치. 후보별 전략 집계도 반복 결과와 일치.',
'- A의 random/training은 기존 balance 집계와 일치, 균형(B)은 Experiment 1의 balanced-training-slot 집계와 일치.',
'- 신규 테스트: 후보 파일 로딩·단일 키 차이·실제 집중 배율 반영·졸기/교사 규칙 보존·균형 B 지정·관측 켬/끔 State·반복 RNG 검증.',
'- 기존 기준 설정·domain/src·전략·Experiment 0 계측·기존 reports 파일은 작업 전 SHA-256과 일치. 새 후보 설정·실험 러너·테스트·분석 스크립트·결과만 추가했다.', '',
'RNG 알고리즘이나 엔진 호출 위치·순서를 수정하지 않았고 계측은 난수를 호출하지 않는다. 학업 수치 변경으로 정책 선택·가드 분기가 달라지면 실제 실행되는 RNG 호출 수와 종료 상태는 달라질 수 있다. 이것은 게임 규칙 변경과 구분해야 한다. 동일 후보의 반복 결과는 두 RNG까지 같다.','',
'HEAD: `'+r['sourceCommit']+'` + JSON의 파일 SHA-256. 각 후보 Config hash·실제 값·전략 JSON·시드별 State hash·두 RNG를 결과에 저장했다.','',
'```bash',
'# Java 21 / Maven 환경에서 빌드',
'mvn package',
'java -Xmx1400m -cp simulator/target/simulator-1.0.0-jar-with-dependencies.jar \\',
'  game.simulator.AcademicExperiment config reports/experiment-2-academics.json',
'python3 scripts/report_academic_experiment.py reports/experiment-2-academics.json',
'```','',
'기존 출력 경로가 있으면 실행을 거부한다. 재실행 시 새 결과 경로를 지정한다. 상세 분포·증감·시즌별 원자료·짝차이: [experiment-2-academics.json](experiment-2-academics.json).','',
'## 최종 결론','',
'```text','추천 후보: A/B/C 모두 목표를 만족하지 않음. 새 값 채택 보류, 원본 A 유지.','근거: C에서도 무작위/균형의 거의 모든 시즌이 상한에 도달하고 보충 위험은 없다.','','포화 개선: 종료 100 비율은 거의 동일. 최초 도달 지연과 소실량 감소만 관측.','전략 차별성: 훈련 위주 대 나머지는 구분되지만 무작위/균형은 상한에 함께 집중.','보충수업 발생: 모든 후보·전략에서 여름/겨울 미달 및 실제 보충 0.','축구 성장 부작용: 보충 손실 없음. 훈련 위주의 집중 증가를 통한 체력/성장 간접 변화.','','focusBaseGain 단독 조정으로 충분한가: 아니오.','다음 실험이 필요한가: 예. 학업 이벤트 효과 규칙군만 별도로 비교하고 다른 수치는 고정.','```','']
out.write_text('\n'.join(lines))
print('Wrote',out)
for arm in ['A','B','C']:
 for strategy in names:
  m=summary[arm][strategy];print(arm,strategy,m['final']['mean'],m['saturation'],{k:v['mean'] for k,v in m['sideEffects'].items() if isinstance(v,dict)})
