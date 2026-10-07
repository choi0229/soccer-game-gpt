package game.simulator;

import game.domain.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;

/** Dedicated experiment entry point; never writes balance.md or balance.json. */
public final class ObservabilityMain {
    public static String stateHash(State s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Config.JSON.writeValueAsBytes(s)));
    }
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path directory=Path.of(args.length>0?args[0]:"config"),output=Path.of(args.length>2?args[2]:"reports");
        int count=args.length>1?Integer.parseInt(args[1]):1000;if(count<=0)throw new IllegalArgumentException("runs > 0");
        Config c=Config.load(directory);var policy=Config.JSON.readTree(directory.resolve("strategies.json").toFile());
        Map<String,JsonNode> references=new HashMap<>();
        Path referencePath=args.length>3?Path.of(args[3]):directory.resolveSibling("reports/observability-baseline-v1.json");
        JsonNode referenceLegacy=null;
        if(Files.exists(referencePath)) {
            var reference=Config.JSON.readTree(referencePath.toFile());
            if(!reference.get("configHash").asText().equals(c.fingerprint()) || !reference.get("strategyConfig").equals(policy))
                throw new IllegalStateException("reference configuration differs");
            for(var row:reference.get("stateFingerprints"))references.put(row.get("strategy").asText()+":"+row.get("seed").asInt(),row);
            referenceLegacy=reference.get("legacyBalance");
        }
        Map<String,Object> results=new LinkedHashMap<>();int verified=0,oldVerified=0;boolean legacyMatches=true;
        for(String strategy:List.of("random","training","balanced")) {
            Observations pooled=new Observations();List<Object> seasons=new ArrayList<>();List<State> states=new ArrayList<>();
            Observations.Samples first100=new Observations.Samples(),firstStarter=new Observations.Samples(),academic19=new Observations.Samples(),academic40=new Observations.Samples();
            Observations.Samples coachInitial=new Observations.Samples(),coachFinal=new Observations.Samples(),primary18=new Observations.Samples(),trainable18=new Observations.Samples(),primaryFinal=new Observations.Samples(),trainableFinal=new Observations.Samples();
            int never100=0,neverStarter=0,failed19=0,failed40=0;
            for(int seed=0;seed<count;seed++) {
                var obs=new Observations();State observed=Main.run(c,strategy,seed,policy,obs);State disabled=Main.run(c,strategy,seed,policy);
                String hash=stateHash(observed);
                if(!hash.equals(stateHash(disabled)))throw new IllegalStateException("observer changes State: "+strategy+":"+seed);verified++;
                if(!references.isEmpty()) {
                    var reference=references.get(strategy+":"+seed);
                    if(reference==null || !hash.equals(reference.get("stateSha256").asText()) || observed.rngState!=reference.get("rngState").asLong() || obs.choiceRngState!=reference.get("choiceRngState").asLong())throw new IllegalStateException("pre-change baseline mismatch: "+strategy+":"+seed);
                    oldVerified++;
                }
                obs.verify();
                if(obs.injuries!=observed.injuries || obs.goals.get("all").matches!=observed.matches.size() || obs.baseScenes+obs.addedScenes!=observed.matches.stream().mapToInt(m->m.moments.size()).sum())throw new IllegalStateException("observer reconciliation");
                for(var menu:c.menus)if(obs.menus.get(Observations.menuName(menu.id())).executed!=observed.trainingCounts.get(menu.id()))throw new IllegalStateException("executed menu differs from State");
                states.add(observed);pooled.merge(obs);var season=obs.report(true);season.put("stateSha256",hash);seasons.add(season);
                if(obs.firstAcademic100Week==null)never100++;else first100.add(obs.firstAcademic100Week);
                if(obs.firstStarterWeek==null)neverStarter++;else firstStarter.add(obs.firstStarterWeek);
                academic19.add(obs.academicWeek19);academic40.add(obs.academicWeek40);if(obs.failedWeek19)failed19++;if(obs.failedWeek40)failed40++;
                coachInitial.add(obs.coachInitial);coachFinal.add(obs.coachFinal);primary18.add(obs.primaryWeek18);trainable18.add(obs.trainableWeek18);primaryFinal.add(obs.primaryFinal);trainableFinal.add(obs.trainableFinal);
            }
            Map<String,Object> aggregate=pooled.report(false);
            aggregate.put("seasonDistributions",Map.of("academicFirst100Week",first100.summary(),"academicNever100",never100,"academicWeek19",academic19.summary(),"academicWeek40",academic40.summary(),"failedWeek19",failed19,"failedWeek40",failed40,"firstStarterWeek",firstStarter.summary(),"neverStarter",neverStarter));
            aggregate.put("growthDistributions",Map.of("primaryWeek18",primary18.summary(),"trainableWeek18",trainable18.summary(),"primaryFinal",primaryFinal.summary(),"trainableFinal",trainableFinal.summary()));
            aggregate.put("coachDistributions",Map.of("initial",coachInitial.summary(),"final",coachFinal.summary()));
            var legacy=Main.aggregate(c,states);
            if(referenceLegacy!=null && !Config.JSON.valueToTree(legacy).equals(referenceLegacy.get(strategy)))throw new IllegalStateException("independent legacy aggregate mismatch: "+strategy);
            Path saved=directory.resolveSibling("reports/balance.json");
            if(count==1000 && Files.exists(saved)) {
                var original=Config.JSON.readTree(saved.toFile());boolean equal=original.get("configHash").asText().equals(c.fingerprint()) && original.get("strategyConfig").equals(policy) && original.get("strategies").get(strategy).equals(Config.JSON.valueToTree(legacy));
                if(!equal)throw new IllegalStateException("saved balance aggregate mismatch: "+strategy);legacyMatches&=equal;
            } else legacyMatches=false;
            results.put(strategy,Map.of("aggregate",aggregate,"seasons",seasons,"legacyBalance",legacy));System.err.println(strategy+": observed/disabled/reference verified "+count+" seasons");
        }
        Map<String,Object> report=new LinkedHashMap<>();report.put("schemaVersion",1);report.put("runsPerStrategy",count);report.put("baseSeed",0);report.put("configHash",c.fingerprint());report.put("strategyConfig",policy);report.put("menuAliases",Observations.MENU_IDS);
        report.put("definitions",Map.of(
            "policySelections","Every menu generated by unchanged strategy, including inactive morning/weekend slots; not only changed HTTP selections.",
            "selected","Scheduled training slots only: afternoon/night on weekdays and morning on vacation weekdays. Includes replacements; no weekend training.",
            "trainingStamina","Before handling each scheduled slot, including replacement/rehab/exclusion. Match-replaced afternoon/night use the same pre-match snapshot; not post-match hypothetical stamina.",
            "injuryRollsN","Actual 3% chance calls after injury/exclusion guards; current window is 10 <= stamina < 30.",
            "injuredDays","Calendar days inside the season with active injury at day end, including occurrence day; durationDaysAwarded may extend past season.",
            "resourceDeltas","Increase/decrease are positive magnitudes. Requested vs applied deltas and clamp losses reported separately. Rating calls count even if clamped.",
            "weekSnapshots","Week18 is Sunday after all actions/recovery; week19/week40 academics are actual Sunday review values.",
            "scores","Player school perspective; all is league+cup, penalties excluded. playerAddedFractionOfFinal includes goal+assist+press. Score maxima are own/opponent individual maxima and one observed maximum-total score.",
            "percentiles","Sorted index floor((n-1)*p); p50 lower median, no interpolation. Empty samples return count0 and null statistics; zero-denominator ratios return0.",
            "roles","substitute corresponds to domain role sub; bench has no rating. No individual scene logs stored; factors pooled across goal scenes."));
        report.put("verification",Map.of("observerEnabledVsDisabledStates",verified,"preChangeStatesAndBothRng",oldVerified,"savedBalanceAllMetricsIdentical",legacyMatches,"referenceCommit","5231715","seasonReconciliationsPassed",true));report.put("strategies",results);
        Files.createDirectories(output);
        Config.JSON.writeValue(output.resolve("observability-v1.json").toFile(),report);
        Files.writeString(output.resolve("observability-v1.md"),markdown(Config.JSON.valueToTree(report)));
    }
    private static double n(JsonNode node,String... path) {for(String key:path)node=node.get(key);return node==null||node.isNull()?Double.NaN:node.asDouble();}
    private static void table(StringBuilder b,String header) {b.append(header).append('\n');}
    private static String markdown(JsonNode r) {
        var b=new StringBuilder("# Experiment 0 관측 결과\n\n게임 규칙·설정·전략·RNG 변경 없음. 원래 balance 파일은 보존했습니다.\n\n");
        b.append("전략별 ").append(r.get("runsPerStrategy")).append("시즌, 시드 0부터. Config hash: `").append(r.get("configHash").asText()).append("`.\n\n");
        b.append("## 결정론 검증\n\n").append("계측 켬/끔 State 비교: ").append(r.get("verification").get("observerEnabledVsDisabledStates")).append("시즌. 독립 변경 전 기준 State·게임 RNG·선택 RNG 비교: ").append(r.get("verification").get("preChangeStatesAndBothRng")).append("시즌. 기존 balance 모든 집계 일치: ").append(r.get("verification").get("savedBalanceAllMetricsIdentical")).append(".\n\n");
        b.append("## 읽는 기준\n\n선택은 예정 훈련 슬롯의 메뉴이며 정책 선택은 주말·비활성 오전에도 생성된 전체 메뉴입니다. 대체 슬롯도 실행 직전 체력에 포함합니다. 경기 대체 오후·야간은 경기 직전 같은 값입니다. 부상 N은 실제 3% 판정 횟수이며, 부상 일수는 시즌 안의 부상 날짜 수입니다. 증감은 요청량/실제 적용량/상한·하한 소실을 구분합니다. 상세 통계·시즌별 경기 전 값은 [observability-v1.json](observability-v1.json)에 있습니다.\n\n");
        b.append("메뉴 대응: dribble=breakthrough, pressing=press, stamina=fitness. substitute=도메인 sub. P50은 낮은 중앙값, 빈 표본은 null입니다.\n\n");
        for(String name:List.of("random","training","balanced")) {
            JsonNode s=r.get("strategies").get(name),a=s.get("aggregate"),legacy=s.get("legacyBalance");
            b.append("## ").append(name).append("\n\n");
            b.append(String.format("기존 주력 %.2f / 학업 %.2f / 선발 %.1f%% / 평판 %.3f 유지.\n\n",n(legacy,"primaryAverage","mean"),n(legacy,"academic","mean"),n(legacy,"roleRatios","starter")*100,n(legacy,"reputation","mean")));
            table(b,"| 메뉴 | 정책 선택 | 예정 슬롯 선택 | 실행 | 대체 |\n| --- | ---: | ---: | ---: | --- |");
            a.get("menus").fields().forEachRemaining(e->{var v=e.getValue();b.append("| ").append(e.getKey()).append(" | ").append(v.get("policySelections")).append(" | ").append(v.get("selected")).append(" | ").append(v.get("executed")).append(" | ").append(v.get("replaced")).append(" |\n");});
            b.append('\n');table(b,"| 훈련 직전 체력 | 평균 | P10 | P50 | P90 | 최소 | <30 | <10 | 최대치 대비 평균 | 제외 슬롯 |\n| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |");
            for(String term:List.of("school","vacation")) {var v=a.get("trainingStamina").get(term);b.append(String.format("| %s | %.3f | %.3f | %.3f | %.3f | %.3f | %d | %d | %.3f | %d |%n",term,n(v,"stamina","mean"),n(v,"stamina","p10"),n(v,"stamina","p50"),n(v,"stamina","p90"),n(v,"stamina","min"),v.get("below30").asInt(),v.get("below10").asInt(),n(v,"currentToMaximumRatio","mean"),v.get("excludedSlots").asInt()));}
            var injury=a.get("injury");b.append("\n부상 N=").append(injury.get("rollsN")).append(", 발생=").append(injury.get("occurrences")).append(", 시즌 내 부상일=").append(injury.get("injuredCalendarDaysWithinSeason")).append(", 부여된 총 기간=").append(injury.get("durationDaysAwarded")).append("일.\n\n");
            b.append("수업 실행: `").append(a.get("academic").get("classes")).append("`.\n\n");
            table(b,"| 학업 원인 | 요청 증가 | 요청 감소 | 적용 증가 | 적용 감소 | 상한 소실 |\n| --- | ---: | ---: | ---: | ---: | ---: |");
            a.get("academic").get("sources").fields().forEachRemaining(e->{var v=e.getValue();b.append(String.format("| %s | %.3f | %.3f | %.3f | %.3f | %.3f |%n",e.getKey(),n(v,"requestedIncrease"),n(v,"requestedDecrease"),n(v,"appliedIncrease"),n(v,"appliedDecrease"),n(v,"lostIncrease")));});
            double first100Mean=n(a,"seasonDistributions","academicFirst100Week","mean");
            String first100Text=Double.isNaN(first100Mean)?"해당 없음":String.format("%.3f",first100Mean);
            b.append(String.format("%n최초 학업100 도달 주차 평균 %s (미도달 %d시즌), 19주 학업 %.3f, 40주 학업 %.3f. 19주 미달 %d시즌, 40주 미달 %d시즌.%n%n",first100Text,(int)n(a,"seasonDistributions","academicNever100"),n(a,"seasonDistributions","academicWeek19","mean"),n(a,"seasonDistributions","academicWeek40","mean"),(int)n(a,"seasonDistributions","failedWeek19"),(int)n(a,"seasonDistributions","failedWeek40")));
            table(b,"| 성장 | 18주 평균 | 종료 평균 |\n| --- | ---: | ---: |");
            b.append(String.format("| 주력 | %.3f | %.3f |%n| 훈련12개 | %.3f | %.3f |%n%n",n(a,"growthDistributions","primaryWeek18","mean"),n(a,"growthDistributions","primaryFinal","mean"),n(a,"growthDistributions","trainableWeek18","mean"),n(a,"growthDistributions","trainableFinal","mean")));
            table(b,"| 대회 | 경기 | 기본 골/경기 | 최종 우리 골/경기 | 상대 골/경기 | 추가 비중 | 합계≥6 | 한 팀≥5 | 최대 합계 스코어 |\n| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |");
            for(String group:List.of("all","league","cup")) {var v=a.get("scores").get(group);b.append(String.format("| %s | %d | %.4f | %.4f | %.4f | %.2f%% | %.2f%% | %.2f%% | %s |%n",group,(int)n(v,"totals","matches"),n(v,"perMatch","basePoissonGoals"),n(v,"perMatch","finalGoals"),n(v,"perMatch","opponentFinalGoals"),n(v,"playerAddedFractionOfFinal")*100,n(v,"totalAtLeast6","ratio")*100,n(v,"eitherTeamAtLeast5","ratio")*100,v.get("maximum").get("scoreAtMaxCombined")));}
            b.append('\n');table(b,"| 역할 평점 | 표본 | 평균 | P10 | P50 | P90 | <5.5 | ≥7 | ≥8 |\n| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |");
            for(String role:List.of("starter","substitute")) {var v=a.get("ratings").get(role);b.append(String.format("| %s | %d | %.4f | %.2f | %.2f | %.2f | %.2f%% | %.2f%% | %.2f%% |%n",role,(int)n(v,"distribution","count"),n(v,"distribution","mean"),n(v,"distribution","p10"),n(v,"distribution","p50"),n(v,"distribution","p90"),n(v,"below5_5","ratio")*100,n(v,"atLeast7","ratio")*100,n(v,"atLeast8","ratio")*100));}
            b.append(String.format("%n감독 시작 %.3f, 종료 %.3f, 경기 직전 평균 %.3f.%n%n",n(a,"coachDistributions","initial","mean"),n(a,"coachDistributions","final","mean"),n(a,"coach","preMatch","mean")));
            table(b,"| 감독 변화 원인 | 양수 호출 | 음수 호출 | 적용 증가 | 적용 감소 | 상한 소실 | 하한 소실 |\n| --- | ---: | ---: | ---: | ---: | ---: | ---: |");
            a.get("coach").get("sources").fields().forEachRemaining(e->{var v=e.getValue();b.append(String.format("| %s | %d | %d | %.3f | %.3f | %.3f | %.3f |%n",e.getKey(),(int)n(v,"positiveCalls"),(int)n(v,"negativeCalls"),n(v,"appliedIncrease"),n(v,"appliedDecrease"),n(v,"lostIncrease"),n(v,"lostDecrease")));});
            b.append("\n기본 장면 ").append(a.get("scenes").get("baseScenes")).append(", 연쇄 추가 ").append(a.get("scenes").get("addedScenes")).append(", 골 장면 돌파 보너스 적용 ").append(a.get("scenes").get("boostedGoalScenes")).append("회.\n\n");
            table(b,"| 장면 | 발생 | 성공 | 실패 |\n| --- | ---: | ---: | ---: |");
            a.get("scenes").get("kinds").fields().forEachRemaining(e->{var v=e.getValue();b.append("| ").append(e.getKey()).append(" | ").append(v.get("occurred")).append(" | ").append(v.get("success")).append(" | ").append(v.get("failure")).append(" |\n");});
            b.append('\n');table(b,"| 골 장면 판정 요소 | 평균 | P10 | P50 | P90 | 최소 | 최대 |\n| --- | ---: | ---: | ---: | ---: | ---: | ---: |");
            a.get("scenes").get("goalFactors").fields().forEachRemaining(e->{var v=e.getValue();b.append(String.format("| %s | %.4f | %.4f | %.4f | %.4f | %.4f | %.4f |%n",e.getKey(),n(v,"mean"),n(v,"p10"),n(v,"p50"),n(v,"p90"),n(v,"min"),n(v,"max")));});b.append('\n');
        }
        b.append("## 계측에서 바로 확인된 사항\n\n");
        var balanced=r.get("strategies").get("balanced").get("aggregate");
        b.append("- 균형 압박·체력의 실제 실행은 각각 ").append(balanced.get("menus").get("pressing").get("executed")).append("회 / ").append(balanced.get("menus").get("stamina").get("executed")).append("회입니다. 메뉴 순환은 수정하지 않았습니다.\n");
        for(String strategy:List.of("random","training","balanced")) {
            var a=r.get("strategies").get(strategy).get("aggregate");
            b.append(String.format("- %s: 실제 부상 판정 N=%d, 부상=%d. 선발 평점<5.5 %.2f%%, 교체 %.2f%%.%n",strategy,(int)n(a,"injury","rollsN"),(int)n(a,"injury","occurrences"),n(a,"ratings","starter","below5_5","ratio")*100,n(a,"ratings","substitute","below5_5","ratio")*100));
        }
        b.append("\n발생률 I/N은 표본 비율이며, 사건이 적은 전략에서 설정 확률 3%의 적정성을 단독 판단하지 않습니다. 자세한 실행·검증 방법은 [OBSERVABILITY.md](../docs/OBSERVABILITY.md)를 참고하세요.\n");
        return b.toString();
    }
}
