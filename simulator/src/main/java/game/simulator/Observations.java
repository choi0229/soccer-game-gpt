package game.simulator;

import game.domain.EngineObserver;
import java.util.*;

/** Simulator-owned counters; never serialized into domain State or given a RNG. */
public class Observations implements EngineObserver {
    public static final Map<String,String> MENU_IDS=Map.of("shooting","shooting","power","power","aerial","aerial",
        "dribble","breakthrough","link","link","pressing","press","stamina","fitness");
    public static String menuName(String id) { return switch(id) {case "breakthrough"->"dribble";case "press"->"pressing";case "fitness"->"stamina";default->id;}; }
    public static String roleName(String id) { return id.equals("sub")?"substitute":id; }
    public record Summary(int count,Double mean,Double min,Double p10,Double p50,Double p90,Double max) {}
    public static final class Samples {
        private final List<Double> values=new ArrayList<>();
        public void add(double value) { values.add(value); }
        public void merge(Samples other) { values.addAll(other.values); }
        public Summary summary() {
            double[] a=values.stream().mapToDouble(Double::doubleValue).sorted().toArray();int n=a.length;
            if(n==0) return new Summary(0,null,null,null,null,null,null);
            return new Summary(n,Arrays.stream(a).average().orElseThrow(),a[0],a[(int)((n-1)*.1)],a[(n-1)/2],a[(int)((n-1)*.9)],a[n-1]);
        }
    }
    public static final class MenuCount {
        public int policySelections,selected,executed;
        public Map<String,Integer> replaced=new TreeMap<>();
        void merge(MenuCount b) {policySelections+=b.policySelections;selected+=b.selected;executed+=b.executed;b.replaced.forEach((k,v)->replaced.merge(k,v,Integer::sum));}
        int replacementCount() {return replaced.values().stream().mapToInt(Integer::intValue).sum();}
    }
    public static final class TrainingGroup {
        public final Samples stamina=new Samples(),ratio=new Samples();
        public int below30,below10,executed,excluded;
        void add(Training x) {stamina.add(x.stamina());ratio.add(x.stamina()/x.maxStamina());if(x.stamina()<30)below30++;if(x.stamina()<10)below10++;if(x.outcome().equals("executed"))executed++;if(x.outcome().equals("excluded"))excluded++;}
        void merge(TrainingGroup b) {stamina.merge(b.stamina);ratio.merge(b.ratio);below30+=b.below30;below10+=b.below10;executed+=b.executed;excluded+=b.excluded;}
        Map<String,Object> report() {return Map.of("stamina",stamina.summary(),"currentToMaximumRatio",ratio.summary(),"below30",below30,"below10",below10,"executedSlots",executed,"excludedSlots",excluded);}
    }
    public static final class Delta {
        public int positiveCalls,negativeCalls;
        public double requestedIncrease,requestedDecrease,appliedIncrease,appliedDecrease,lostIncrease,lostDecrease;
        void add(Change x) {
            double applied=x.after()-x.before();
            if(x.requested()>0) {positiveCalls++;requestedIncrease+=x.requested();appliedIncrease+=applied;lostIncrease+=Math.max(0,x.before()+x.requested()-x.after());}
            if(x.requested()<0) {negativeCalls++;requestedDecrease-=x.requested();appliedDecrease-=applied;lostDecrease+=Math.max(0,x.after()-(x.before()+x.requested()));}
        }
        void merge(Delta x) {positiveCalls+=x.positiveCalls;negativeCalls+=x.negativeCalls;requestedIncrease+=x.requestedIncrease;requestedDecrease+=x.requestedDecrease;appliedIncrease+=x.appliedIncrease;appliedDecrease+=x.appliedDecrease;lostIncrease+=x.lostIncrease;lostDecrease+=x.lostDecrease;}
        double net() {return appliedIncrease-appliedDecrease;}
        public double getNetChange() {return net();}
    }
    public static final class SceneCount {
        public int occurred,success,failure;
        void merge(SceneCount b) {occurred+=b.occurred;success+=b.success;failure+=b.failure;}
    }
    public static final class Goals {
        public int matches,basePoissonGoals,playerGoals,assistAddedGoals,pressingAddedGoals,finalGoals,opponentBaseGoals,opponentFinalGoals;
        public int totalAtLeast6,eitherTeamAtLeast5,maxOwn,maxOpponent,maxCombined;
        public String scoreAtMaxCombined;
        public Map<String,Integer> scoreHistogram=new TreeMap<>();
        void add(Score x) {
            matches++;basePoissonGoals+=x.basePoissonGoals();playerGoals+=x.playerGoals();assistAddedGoals+=x.assistAddedGoals();pressingAddedGoals+=x.pressingAddedGoals();finalGoals+=x.finalGoals();opponentBaseGoals+=x.opponentBaseGoals();opponentFinalGoals+=x.opponentFinalGoals();
            int total=x.finalGoals()+x.opponentFinalGoals();if(total>=6)totalAtLeast6++;if(Math.max(x.finalGoals(),x.opponentFinalGoals())>=5)eitherTeamAtLeast5++;
            maxOwn=Math.max(maxOwn,x.finalGoals());maxOpponent=Math.max(maxOpponent,x.opponentFinalGoals());
            if(scoreAtMaxCombined==null || total>maxCombined) {maxCombined=total;scoreAtMaxCombined=x.finalGoals()+":"+x.opponentFinalGoals();}
            scoreHistogram.merge(x.finalGoals()+":"+x.opponentFinalGoals(),1,Integer::sum);
        }
        void merge(Goals b) {
            matches+=b.matches;basePoissonGoals+=b.basePoissonGoals;playerGoals+=b.playerGoals;assistAddedGoals+=b.assistAddedGoals;pressingAddedGoals+=b.pressingAddedGoals;finalGoals+=b.finalGoals;opponentBaseGoals+=b.opponentBaseGoals;opponentFinalGoals+=b.opponentFinalGoals;
            totalAtLeast6+=b.totalAtLeast6;eitherTeamAtLeast5+=b.eitherTeamAtLeast5;maxOwn=Math.max(maxOwn,b.maxOwn);maxOpponent=Math.max(maxOpponent,b.maxOpponent);
            if(b.scoreAtMaxCombined!=null && (scoreAtMaxCombined==null || b.maxCombined>maxCombined)) {maxCombined=b.maxCombined;scoreAtMaxCombined=b.scoreAtMaxCombined;}
            b.scoreHistogram.forEach((k,v)->scoreHistogram.merge(k,v,Integer::sum));
        }
        Map<String,Object> report() {
            Map<String,Object> m=new LinkedHashMap<>();m.put("totals",Map.of("matches",matches,"basePoissonGoals",basePoissonGoals,"playerGoals",playerGoals,"assistAddedGoals",assistAddedGoals,"pressingAddedGoals",pressingAddedGoals,"finalGoals",finalGoals,"opponentBaseGoals",opponentBaseGoals,"opponentFinalGoals",opponentFinalGoals));
            m.put("perMatch",Map.of("basePoissonGoals",rate(basePoissonGoals,matches),"playerGoals",rate(playerGoals,matches),"assistAddedGoals",rate(assistAddedGoals,matches),"pressingAddedGoals",rate(pressingAddedGoals,matches),"finalGoals",rate(finalGoals,matches),"opponentBaseGoals",rate(opponentBaseGoals,matches),"opponentFinalGoals",rate(opponentFinalGoals,matches)));
            m.put("playerAddedFractionOfFinal",rate(playerGoals+assistAddedGoals+pressingAddedGoals,finalGoals));
            m.put("averageFinalScore",List.of(rate(finalGoals,matches),rate(opponentFinalGoals,matches)));
            m.put("totalAtLeast6",Map.of("count",totalAtLeast6,"ratio",rate(totalAtLeast6,matches)));
            m.put("eitherTeamAtLeast5",Map.of("count",eitherTeamAtLeast5,"ratio",rate(eitherTeamAtLeast5,matches)));
            Map<String,Object> maximum=new LinkedHashMap<>();maximum.put("own",maxOwn);maximum.put("opponent",maxOpponent);maximum.put("combined",maxCombined);maximum.put("scoreAtMaxCombined",scoreAtMaxCombined);m.put("maximum",maximum);m.put("scoreHistogram",scoreHistogram);
            return m;
        }
    }
    public static final class Ratings {
        public Samples values=new Samples();public int below55,atLeast7,atLeast8;
        void add(double v) {values.add(v);if(v<5.5)below55++;if(v>=7)atLeast7++;if(v>=8)atLeast8++;}
        void merge(Ratings b) {values.merge(b.values);below55+=b.below55;atLeast7+=b.atLeast7;atLeast8+=b.atLeast8;}
        Map<String,Object> report() {int n=values.summary().count();return Map.of("distribution",values.summary(),"below5_5",Map.of("count",below55,"ratio",rate(below55,n)),"atLeast7",Map.of("count",atLeast7,"ratio",rate(atLeast7,n)),"atLeast8",Map.of("count",atLeast8,"ratio",rate(atLeast8,n)));}
    }
    public long seed,gameRngState,choiceRngState;
    public Map<String,MenuCount> menus=new TreeMap<>();
    public Map<String,Map<String,MenuCount>> menuSlots=new TreeMap<>();
    public Map<String,TrainingGroup> trainingGroups=new TreeMap<>();
    public Map<String,Integer> classes=new TreeMap<>();
    public Map<String,Delta> academicSources=new TreeMap<>(),coachSources=new TreeMap<>();
    public Map<String,SceneCount> sceneKinds=new TreeMap<>();
    public Map<String,Integer> basicScenesByRole=new TreeMap<>(),totalScenesByRole=new TreeMap<>();
    public Map<String,Samples> goalFactors=new TreeMap<>();
    public Map<String,Goals> goals=new TreeMap<>();
    public Map<String,Ratings> ratings=new TreeMap<>();
    public List<Appearance> appearances=new ArrayList<>();
    public Samples preMatchCoach=new Samples();
    public int injuryRolls,injuries,injuryDurationDays,injuredDays,baseScenes,addedScenes,boostedGoalScenes;
    public Integer firstAcademic100Week,firstStarterWeek;
    public Double academicWeek19,academicWeek40,primaryWeek18,trainableWeek18;
    public boolean failedWeek19,failedWeek40;
    public double academicInitial,academicFinal,coachInitial,coachFinal,primaryFinal,trainableFinal;
    public Observations() {
        for(String name:MENU_IDS.keySet())menus.put(name,new MenuCount());
        for(String slot:List.of("morning","afternoon","night")) {var entries=new TreeMap<String,MenuCount>();for(String name:MENU_IDS.keySet())entries.put(name,new MenuCount());menuSlots.put(slot,entries);}
        for(String source:List.of("classroom","event","supplement"))academicSources.put(source,new Delta());
        for(String source:List.of("sundayMeet","event","rating","other"))coachSources.put(source,new Delta());
        for(String role:List.of("starter","substitute"))ratings.put(role,new Ratings());
        for(String term:List.of("school","vacation"))trainingGroups.put(term,new TrainingGroup());
        for(String group:List.of("all","league","cup"))goals.put(group,new Goals());
        for(String factor:List.of("chance","ability","opponentPower","condition","passive","boost"))goalFactors.put(factor,new Samples());
        for(String attitude:List.of("focus","nap","teacher","friends"))classes.put(attitude,0);
    }
    @Override public void created(long seed,double academic,double coach) {this.seed=seed;academicInitial=academic;coachInitial=coach;if(academic>=100)firstAcademic100Week=1;}
    @Override public void policySelection(int day,String slot,String menu) {menus.get(menuName(menu)).policySelections++;menuSlots.get(slot).get(menuName(menu)).policySelections++;}
    @Override public void training(Training x) {
        for(MenuCount count:List.of(menus.get(menuName(x.menu())),menuSlots.get(x.slot()).get(menuName(x.menu())))) {
            count.selected++;if(x.outcome().equals("executed"))count.executed++;else count.replaced.merge(x.outcome(),1,Integer::sum);
        }
        String term=x.vacation()?"vacation":"school";trainingGroups.get(term).add(x);trainingGroups.computeIfAbsent(term+"/"+x.slot(),k->new TrainingGroup()).add(x);
    }
    @Override public void injuryRoll() {injuryRolls++;}
    @Override public void injury(int days) {injuries++;injuryDurationDays+=days;}
    @Override public void classroom(String attitude) {classes.merge(attitude,1,Integer::sum);}
    @Override public void academic(Change x) {academicSources.computeIfAbsent(x.source(),k->new Delta()).add(x);if(firstAcademic100Week==null && x.after()>=100)firstAcademic100Week=x.week();}
    @Override public void coach(Change x) {coachSources.computeIfAbsent(x.source(),k->new Delta()).add(x);}
    @Override public void appearance(Appearance x) {
        var v=new Appearance(x.day(),x.week(),x.competition(),x.coach(),x.trainable(),x.primary(),x.score(),x.starterMargin(),x.substituteMargin(),roleName(x.role()));
        appearances.add(v);preMatchCoach.add(x.coach());if(firstStarterWeek==null && x.role().equals("starter"))firstStarterWeek=x.week();
    }
    @Override public void baseScenes(String role,int count) {baseScenes+=count;basicScenesByRole.merge(roleName(role),count,Integer::sum);}
    @Override public void addedScene() {addedScenes++;}
    @Override public void scene(Scene x) {
        var kind=sceneKinds.computeIfAbsent(x.id(),k->new SceneCount());kind.occurred++;if(x.success())kind.success++;else kind.failure++;
        totalScenesByRole.merge(roleName(x.role()),1,Integer::sum);
        if(x.goal()) {
            goalFactors.get("chance").add(x.chance());goalFactors.get("ability").add(x.ability());goalFactors.get("opponentPower").add(x.opponentPower());
            goalFactors.get("condition").add(x.condition());goalFactors.get("passive").add(x.passive());goalFactors.get("boost").add(x.boost());if(x.boost()!=0)boostedGoalScenes++;
        }
    }
    @Override public void score(Score x) {goals.get("all").add(x);goals.get(x.competition().startsWith("주말리그")?"league":"cup").add(x);if(x.rating()!=null)ratings.get(roleName(x.role())).add(x.rating());}
    @Override public void day(Day x) {
        if(x.injured())injuredDays++;academicFinal=x.academic();coachFinal=x.coach();primaryFinal=x.primary();trainableFinal=x.trainable();
        if(x.week()==18 && x.day()%7==6) {primaryWeek18=x.primary();trainableWeek18=x.trainable();}
        if(x.review() && x.week()==19) {academicWeek19=x.academic();failedWeek19=x.failed();}
        if(x.review() && x.week()==40) {academicWeek40=x.academic();failedWeek40=x.failed();}
    }
    @Override public void finished(long game,long choice) {gameRngState=game;choiceRngState=choice;}
    public void merge(Observations b) {
        b.menus.forEach((k,v)->menus.get(k).merge(v));b.menuSlots.forEach((slot,entries)->entries.forEach((k,v)->menuSlots.get(slot).get(k).merge(v)));
        b.trainingGroups.forEach((k,v)->trainingGroups.computeIfAbsent(k,x->new TrainingGroup()).merge(v));b.classes.forEach((k,v)->classes.merge(k,v,Integer::sum));
        b.academicSources.forEach((k,v)->academicSources.computeIfAbsent(k,x->new Delta()).merge(v));b.coachSources.forEach((k,v)->coachSources.computeIfAbsent(k,x->new Delta()).merge(v));
        b.sceneKinds.forEach((k,v)->sceneKinds.computeIfAbsent(k,x->new SceneCount()).merge(v));b.basicScenesByRole.forEach((k,v)->basicScenesByRole.merge(k,v,Integer::sum));b.totalScenesByRole.forEach((k,v)->totalScenesByRole.merge(k,v,Integer::sum));
        b.goalFactors.forEach((k,v)->goalFactors.get(k).merge(v));b.goals.forEach((k,v)->goals.get(k).merge(v));b.ratings.forEach((k,v)->ratings.get(k).merge(v));preMatchCoach.merge(b.preMatchCoach);
        injuryRolls+=b.injuryRolls;injuries+=b.injuries;injuryDurationDays+=b.injuryDurationDays;injuredDays+=b.injuredDays;baseScenes+=b.baseScenes;addedScenes+=b.addedScenes;boostedGoalScenes+=b.boostedGoalScenes;
    }
    public void verify() {
        for(MenuCount count:menus.values())if(count.selected!=count.executed+count.replacementCount())throw new IllegalStateException("menu reconciliation");
        for(Goals g:goals.values())if(g.finalGoals!=g.basePoissonGoals+g.playerGoals+g.assistAddedGoals+g.pressingAddedGoals || g.opponentBaseGoals!=g.opponentFinalGoals)throw new IllegalStateException("score reconciliation");
        int scenes=sceneKinds.values().stream().mapToInt(x->x.occurred).sum();if(scenes!=baseScenes+addedScenes)throw new IllegalStateException("scene reconciliation");
        if(Math.abs(coachResidual())>1e-8 || Math.abs(academicResidual())>1e-8)throw new IllegalStateException("resource reconciliation");
    }
    public double coachResidual() {return coachFinal-coachInitial-coachSources.values().stream().mapToDouble(Delta::net).sum();}
    public double academicResidual() {return academicFinal-academicInitial-academicSources.values().stream().mapToDouble(Delta::net).sum();}
    public Map<String,Object> report(boolean season) {
        Map<String,Object> m=new LinkedHashMap<>();m.put("menus",menus);m.put("menuSlots",menuSlots);
        var stamina=new TreeMap<String,Object>();trainingGroups.forEach((k,v)->stamina.put(k,v.report()));m.put("trainingStamina",stamina);
        m.put("injury",Map.of("rollsN",injuryRolls,"occurrences",injuries,"durationDaysAwarded",injuryDurationDays,"injuredCalendarDaysWithinSeason",injuredDays,"occurrencesPerRoll",rate(injuries,injuryRolls)));
        Map<String,Object> academic=new LinkedHashMap<>();academic.put("classes",classes);academic.put("sources",academicSources);
        if(season) {academic.put("initial",academicInitial);academic.put("final",academicFinal);academic.put("first100Week",firstAcademic100Week);academic.put("week19",academicWeek19);academic.put("week40",academicWeek40);academic.put("failedWeek19",failedWeek19);academic.put("failedWeek40",failedWeek40);academic.put("reconciliationResidual",academicResidual());}
        m.put("academic",academic);
        Map<String,Object> coach=new LinkedHashMap<>();coach.put("sources",coachSources);coach.put("preMatch",preMatchCoach.summary());
        if(season) {coach.put("initial",coachInitial);coach.put("final",coachFinal);coach.put("reconciliationResidual",coachResidual());}m.put("coach",coach);
        var scene=new LinkedHashMap<String,Object>();scene.put("baseScenes",baseScenes);scene.put("addedScenes",addedScenes);scene.put("kinds",sceneKinds);scene.put("basicByRole",basicScenesByRole);scene.put("totalByRole",totalScenesByRole);
        var factors=new TreeMap<String,Object>();goalFactors.forEach((k,v)->factors.put(k,v.summary()));scene.put("goalFactors",factors);scene.put("boostedGoalScenes",boostedGoalScenes);m.put("scenes",scene);
        var scores=new TreeMap<String,Object>();goals.forEach((k,v)->scores.put(k,v.report()));m.put("scores",scores);
        var rs=new TreeMap<String,Object>();ratings.forEach((k,v)->rs.put(k,v.report()));m.put("ratings",rs);
        if(season) {m.put("seed",seed);m.put("firstStarterWeek",firstStarterWeek);Map<String,Object> growth=new LinkedHashMap<>();growth.put("primaryWeek18",primaryWeek18);growth.put("trainableWeek18",trainableWeek18);growth.put("primaryFinal",primaryFinal);growth.put("trainableFinal",trainableFinal);m.put("growth",growth);m.put("appearances",appearances);m.put("gameRngState",gameRngState);m.put("choiceRngState",choiceRngState);}
        return m;
    }
    private static double rate(double numerator,double denominator) {return denominator==0?0:numerator/denominator;}
}
