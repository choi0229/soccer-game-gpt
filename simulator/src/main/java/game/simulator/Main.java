package game.simulator;

import game.domain.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.ToDoubleFunction;

public final class Main {
    public static State run(Config c,String strategy,long seed,com.fasterxml.jackson.databind.JsonNode policy) {
        Engine e=new Engine(c); State s=e.create(seed,null);
        Rng choices=new Rng(seed^Long.parseLong(policy.get("choiceStreamSalt").asText()));
        while(!s.completed) {
            if(s.pendingEvent!=null) { e.apply(s,Action.event(choices.integer(0,c.event(s.pendingEvent).choices().size()-1))); continue; }
            Map<String,String> changed=new LinkedHashMap<>();String sunday="rest";String menu;
            switch(strategy) {
                case "random" -> {
                    changed.put("dawn",pick(choices,policy.get("randomDawn")));
                    changed.put("class",pick(choices,policy.get("randomClasses")));
                    for(String slot:List.of("morning","afternoon","night")) changed.put(slot,c.menus.get(choices.integer(0,c.menus.size()-1)).id());
                    if(e.weekday(s)==6) { sunday=pick(choices,policy.get("randomSunday")); if(sunday.equals("meet")) sunday=pick(choices,policy.get("meetAxes")); }
                }
                case "training" -> {
                    changed.put("dawn",s.stamina>=policy.get("trainingStaminaThreshold").asDouble()?"exercise":"sleep");
                    changed.put("class",s.academic<policy.get("trainingAcademicThreshold").asDouble()?"focus":"nap");
                    menu=cycle(policy.get("trainingMenus"),s.day);
                    for(String slot:List.of("morning","afternoon","night")) changed.put(slot,menu);
                }
                case "balanced" -> {
                    boolean exercise=false;for(var day:policy.get("balancedExerciseDays")) if(day.asInt()==e.weekday(s)) exercise=true;
                    changed.put("dawn",exercise?"exercise":"sleep");changed.put("class",cycle(policy.get("balancedClasses"),s.day));
                    menu=c.menus.get(s.day%c.menus.size()).id();
                    for(String slot:List.of("morning","afternoon","night")) changed.put(slot,menu);
                    sunday=cycle(policy.get("balancedSunday"),e.week(s)-1);
                }
                default -> throw new IllegalArgumentException("strategy");
            }
            if(e.injured(s)) changed.put("dawn","sleep");
            // API and simulator both send only selections that changed.
            changed.entrySet().removeIf(x->x.getValue().equals(s.selections.get(x.getKey())));
            e.apply(s,Action.day(changed,e.weekday(s)==6?sunday:null));
        }
        return s;
    }
    private static String pick(Rng r,com.fasterxml.jackson.databind.JsonNode list) { return list.get(r.integer(0,list.size()-1)).asText(); }
    private static String cycle(com.fasterxml.jackson.databind.JsonNode list,int index) { return list.get(index%list.size()).asText(); }
    public record Distribution(double mean,double min,double p10,double median,double p90,double max,Map<String,Integer> histogram) {}
    private static Distribution distribution(double[] values) {
        Arrays.sort(values);Map<String,Integer> bins=new LinkedHashMap<>();
        for(double v:values) { int lower=(int)Math.floor(v/10)*10;String key=lower+"–"+(lower+9);bins.merge(key,1,Integer::sum); }
        return new Distribution(Arrays.stream(values).average().orElse(0),values[0],values[(int)((values.length-1)*.1)],values[(values.length-1)/2],values[(int)((values.length-1)*.9)],values[values.length-1],bins);
    }
    private static Distribution dist(List<State> runs,ToDoubleFunction<State> metric) { return distribution(runs.stream().mapToDouble(metric).toArray()); }
    public static Map<String,Object> aggregate(Config c,List<State> runs) {
        Engine e=new Engine(c);Map<String,Object> result=new LinkedHashMap<>();Map<String,Object> stats=new LinkedHashMap<>();
        for(var stat:c.stats) stats.put(stat.id(),dist(runs,s->s.stats.get(stat.id())));
        result.put("stats",stats);
        result.put("primaryAverage",dist(runs,s->c.stats.stream().filter(Config.Stat::primary).mapToDouble(x->s.stats.get(x.id())).average().orElseThrow()));
        result.put("trainableAverage",dist(runs,e::averageStats));
        result.put("allStatAverage",dist(runs,s->s.stats.values().stream().mapToDouble(Double::doubleValue).average().orElseThrow()));
        result.put("academic",dist(runs,s->s.academic));result.put("reputation",dist(runs,s->s.reputation));
        result.put("injuries",dist(runs,s->s.injuries));result.put("exclusions",dist(runs,s->s.exclusions));
        result.put("supplementRate",runs.stream().filter(s->s.supplementRequired).count()/(double)runs.size());
        result.put("winterSupplementRate",runs.stream().filter(s->s.winterSupplementRequired).count()/(double)runs.size());
        result.put("supplementDays",dist(runs,s->s.supplementDays));
        double[] weekly=new double[c.i("weeks")];for(var s:runs) for(int w=0;w<weekly.length;w++) weekly[w]+=s.weeklyStamina[w]/s.weeklyDays[w]/runs.size();
        result.put("weeklyStamina",weekly);
        var matches=runs.stream().flatMap(s->s.matches.stream()).toList();
        var appearances=matches.stream().filter(m->m.rating!=null).toList();
        Map<String,Double> roles=new LinkedHashMap<>();for(String role:List.of("starter","sub","bench")) roles.put(role,matches.stream().filter(m->role.equals(m.role)).count()/(double)matches.size());
        result.put("roleRatios",roles);result.put("matches",matches.size());result.put("appearances",appearances.size());
        result.put("scenesPerMatch",matches.stream().mapToInt(m->m.moments.size()).average().orElse(0));
        result.put("scenesPerAppearance",appearances.stream().mapToInt(m->m.moments.size()).average().orElse(0));
        result.put("goalsPerMatch",matches.stream().mapToInt(m->m.goals).average().orElse(0));
        result.put("assistsPerMatch",matches.stream().mapToInt(m->m.assists).average().orElse(0));
        result.put("rating",appearances.isEmpty()?null:distribution(appearances.stream().mapToDouble(m->m.rating).toArray()));
        double goals=0;int index=0;for(var s:runs) for(var m:s.matches) { goals+=m.home.equals(s.schoolId)?m.homeGoals:m.awayGoals;index++; }
        result.put("teamGoalsPerMatch",goals/index);
        var all=runs.stream().flatMap(s->s.competitionMatches.stream()).toList();
        result.put("allTeamsGoalsPerMatch",all.stream().mapToInt(m->m.homeGoals+m.awayGoals).average().orElse(0)/2);
        result.put("seasonGoals",dist(runs,s->s.matches.stream().mapToInt(m->m.goals).sum()));
        result.put("seasonAssists",dist(runs,s->s.matches.stream().mapToInt(m->m.assists).sum()));
        Map<String,Integer> ranks=new LinkedHashMap<>();for(var s:runs) ranks.merge(Integer.toString(e.leagueRank(s)),1,Integer::sum);result.put("leagueRanks",ranks);
        return result;
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path directory=Path.of(args.length>0?args[0]:"config"),output=Path.of(args.length>2?args[2]:"reports");
        int count=args.length>1?Integer.parseInt(args[1]):1000;if(count<=0) throw new IllegalArgumentException("runs > 0");
        Config c=Config.load(directory);var policy=Config.JSON.readTree(directory.resolve("strategies.json").toFile());
        Map<String,Object> report=new LinkedHashMap<>();report.put("runsPerStrategy",count);report.put("baseSeed",0);report.put("configHash",c.fingerprint());
        report.put("strategyConfig",policy);
        Map<String,Map<String,Object>> results=new LinkedHashMap<>();
        for(String strategy:List.of("random","training","balanced")) {
            List<State> runs=new ArrayList<>();for(int seed=0;seed<count;seed++) runs.add(run(c,strategy,seed,policy));
            results.put(strategy,aggregate(c,runs));System.err.println(strategy+": "+count+"시즌 완료");
        }
        report.put("strategies",results);Files.createDirectories(output);
        Config.JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("balance.json").toFile(),report);
        StringBuilder md=new StringBuilder("# 밸런스 관찰 보고서\n\n전략별 "+count+"시즌, 시드 0–"+(count-1)+". 설정 SHA-256: `"+c.fingerprint()+"`. 수치 변경 없음.\n\n");
        md.append("| 전략 | 주력 평균 | 훈련 능력 평균 | 부상/시즌 | 제외/시즌 | 학업 평균 | 보충 비율 | 선발 비율 | 골/경기 | 도움/경기 | 평점 | 팀 골/경기 | 평판 평균 |\n|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for(var entry:results.entrySet()) {
            var a=entry.getValue();var roles=(Map<String,Double>)a.get("roleRatios");
            md.append(String.format("| %s | %.2f | %.2f | %.2f | %.2f | %.2f | %.1f%% | %.1f%% | %.3f | %.3f | %.2f | %.3f | %.2f |%n",entry.getKey(),mean(a,"primaryAverage"),mean(a,"trainableAverage"),mean(a,"injuries"),mean(a,"exclusions"),mean(a,"academic"),(double)a.get("supplementRate")*100,roles.get("starter")*100,a.get("goalsPerMatch"),a.get("assistsPerMatch"),mean(a,"rating"),a.get("teamGoalsPerMatch"),mean(a,"reputation")));
        }
        md.append("\n주별 체력은 하루의 자동 회복 뒤 값을 평균했습니다. 장면 수에는 연쇄 추가 장면이 포함됩니다. 평점은 출전 경기만 집계합니다.\n");
        for(var entry:results.entrySet()) {
            var a=entry.getValue();md.append("\n## "+entry.getKey()+"\n\n| 항목 | 평균 | 최소 | P10 | 중앙 | P90 | 최대 |\n|---|---:|---:|---:|---:|---:|---:|\n");
            var statMap=(Map<String,Distribution>)a.get("stats");for(var stat:c.stats) row(md,stat.label(),statMap.get(stat.id()));
            for(String key:List.of("primaryAverage","trainableAverage","allStatAverage","academic","reputation","injuries","exclusions","seasonGoals","seasonAssists","rating")) row(md,key,(Distribution)a.get(key));
            md.append("\n선발/교체/벤치 비율: "+a.get("roleRatios")+"\n\n리그 순위 분포: "+a.get("leagueRanks")+"\n\n장면/전체 경기: "+a.get("scenesPerMatch")+", 장면/출전 경기: "+a.get("scenesPerAppearance")+"\n");
            md.append("\n겨울 성적 미달 비율(보충수업 적용일 확인 대기): "+a.get("winterSupplementRate")+"\n");
        }
        md.append("\n## 주별 평균 체력\n\n| 주 | random | training | balanced |\n|---|---:|---:|---:|\n");
        for(int w=0;w<c.i("weeks");w++) md.append(String.format("| %d | %.2f | %.2f | %.2f |%n",w+1,((double[])results.get("random").get("weeklyStamina"))[w],((double[])results.get("training").get("weeklyStamina"))[w],((double[])results.get("balanced").get("weeklyStamina"))[w]));
        md.append("\n## 관찰 포인트\n\n");
        for(var entry:results.entrySet()) {
            var a=entry.getValue();double primary=mean(a,"primaryAverage"),academic=mean(a,"academic");
            md.append("- "+entry.getKey()+": ");
            if(primary<60) md.append("주력 평균이 C등급(60)에 못 미칩니다. 초기 성장량과 1년 성장 속도를 검토할 수 있습니다. ");
            if(academic>95) md.append("학업 평균이 상한에 가깝습니다. 수업·이벤트 성장량이 누적되는 효과를 확인하세요. ");
            if(mean(a,"injuries")<.1) md.append("부상은 드뭅니다. 체력 30 미만이라는 발생 조건과 자동 회복의 조합을 확인하세요. ");
            md.append("팀 득점/경기 "+String.format("%.3f",a.get("teamGoalsPerMatch"))+". 포아송 팀 득점에 개인 골·도움·압박 득점이 추가되는 명세를 유지했습니다.\n");
        }
        md.append("\n각 수치의 10점 간격 히스토그램과 모든 분포는 `balance.json`에 있습니다.\n");
        Files.writeString(output.resolve("balance.md"),md.toString());System.out.print(md);
    }
    private static double mean(Map<String,Object> a,String key) { var d=(Distribution)a.get(key);return d==null?0:d.mean(); }
    private static void row(StringBuilder md,String name,Distribution d) { if(d!=null) md.append(String.format("| %s | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f |%n",name,d.mean(),d.min(),d.p10(),d.median(),d.p90(),d.max())); }
}
