package game.simulator;

import game.domain.*;
import game.simulator.Observations.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Experiment settings overlay a fresh Config, leaving the base files untouched. */
public final class AcademicExperiment {
    static Config candidate(Path directory,com.fasterxml.jackson.databind.JsonNode experiment,String arm) throws Exception {
        Config c=Config.load(directory);String key=experiment.get("rule").asText();
        if(!key.equals("focusAcademic")) throw new IllegalArgumentException("Only focusAcademic may change");
        c.rules.put(key,experiment.get("candidates").get(arm).asDouble());c.validate();return c;
    }
    static final class AcademicObserver extends Observations {
        String attitude;
        final Map<String,Delta> decomposition=new TreeMap<>();
        @Override public void classroom(String value) {super.classroom(value);attitude=value;}
        @Override public void academic(Change x) {
            super.academic(x);
            String source=x.source().equals("classroom")?attitude:x.source();
            decomposition.computeIfAbsent(source,k->new Delta()).add(x);
        }
    }
    static String sha(State s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Config.JSON.writeValueAsBytes(s)));
    }
    public static void main(String[] args) throws Exception {
        Path directory=Path.of(args.length>0?args[0]:"config"),output=Path.of(args.length>1?args[1]:"reports/experiment-2-academics.json");
        if(Files.exists(output))throw new IllegalArgumentException("Output already exists: "+output);
        var experiment=Config.JSON.readTree(directory.resolve("experiments/academics.json").toFile());
        var policy=Config.JSON.readTree(directory.resolve("strategies.json").toFile());
        var baseline=Config.JSON.readTree(Path.of("reports/balance.json").toFile());
        var slotBaseline=Config.JSON.readTree(Path.of("reports/experiment-1-balanced-menu.json").toFile()).get("arms").get("balanced-training-slot").get("legacyBalance");
        Map<String,Object> arms=new LinkedHashMap<>();int count=experiment.get("runsPerStrategy").asInt();long baseSeed=experiment.get("baseSeed").asLong();
        for(var arm:experiment.get("candidates").properties()) {
            Config c=candidate(directory,experiment,arm.getKey());Map<String,Object> strategies=new LinkedHashMap<>();
            for(var name:experiment.get("strategies")) {
                String strategy=name.asText();List<State> runs=new ArrayList<>(),repeats=new ArrayList<>();List<Object> seasons=new ArrayList<>();Observations pooled=new Observations();Map<String,Delta> changes=new TreeMap<>();
                for(long seed=baseSeed;seed<baseSeed+count;seed++) {
                    AcademicObserver o=new AcademicObserver();State s=Main.run(c,strategy,seed,policy,o);
                    AcademicObserver repeat=new AcademicObserver();State t=Main.run(c,strategy,seed,policy,repeat);
                    String hash=sha(s);
                    if(!hash.equals(sha(t)) || o.choiceRngState!=repeat.choiceRngState || !Config.JSON.valueToTree(o.report(true)).equals(Config.JSON.valueToTree(repeat.report(true))))throw new IllegalStateException("Repeat mismatch");
                    o.verify();pooled.merge(o);o.decomposition.forEach((k,v)->changes.computeIfAbsent(k,x->new Delta()).merge(v));runs.add(s);repeats.add(t);
                    Map<String,Object> row=new LinkedHashMap<>();row.put("seed",seed);row.put("stateSha256",hash);row.put("gameRngState",s.rngState);row.put("choiceRngState",o.choiceRngState);
                    row.put("academicFinal",s.academic);row.put("academicWeek19",o.academicWeek19);row.put("academicWeek40",o.academicWeek40);row.put("first100Week",o.firstAcademic100Week);
                    row.put("summerFailed",s.supplementRequired);row.put("winterFailed",s.winterSupplementRequired);row.put("supplementDays",s.supplementDays);
                    row.put("classes",o.classes);row.put("academicChanges",o.decomposition);row.put("academicSources",o.academicSources);
                    row.put("trainable",new Engine(c).averageStats(s));row.put("primary",o.primaryFinal);row.put("stats",s.stats);
                    row.put("stamina",Arrays.stream(s.weeklyStamina).sum()/Arrays.stream(s.weeklyDays).sum());row.put("injuries",s.injuries);
                    row.put("afternoonReplacements",o.menuSlots.get("afternoon").values().stream().mapToInt(MenuCount::replacementCount).sum());
                    row.put("supplementSlots",o.menuSlots.get("afternoon").values().stream().mapToInt(x->x.replaced.getOrDefault("supplement",0)).sum());
                    row.put("starterRatio",s.matches.stream().filter(x->x.role.equals("starter")).count()/(double)s.matches.size());
                    row.put("goals",s.matches.stream().mapToInt(x->x.goals).sum());row.put("assists",s.matches.stream().mapToInt(x->x.assists).sum());
                    row.put("rating",s.matches.stream().filter(x->x.rating!=null).mapToDouble(x->x.rating).average().orElse(0));row.put("reputation",s.reputation);row.put("leagueRank",new Engine(c).leagueRank(s));seasons.add(row);
                }
                var aggregate=Main.aggregate(c,runs);
                if(!Config.JSON.valueToTree(aggregate).equals(Config.JSON.valueToTree(Main.aggregate(c,repeats))))throw new IllegalStateException("Aggregate repeat mismatch");
                if(arm.getKey().equals("A") && !Config.JSON.valueToTree(aggregate).equals(strategy.equals("balanced-training-slot")?slotBaseline:baseline.get("strategies").get(strategy)))throw new IllegalStateException("Saved baseline mismatch");
                strategies.put(strategy,Map.of("legacyBalance",aggregate,"observations",pooled.report(false),"academicChanges",changes,"seasons",seasons));
                System.err.println(arm.getKey()+" / "+strategy+": "+count+" seasons; repeat and reconciliation passed");
            }
            arms.put(arm.getKey(),Map.of("focusBaseGain",c.n("focusAcademic"),"configHash",c.fingerprint(),"strategies",strategies));
        }
        Map<String,Object> report=new LinkedHashMap<>();report.put("experiment",2);report.put("settings",experiment);report.put("strategyConfig",policy);report.put("baseConfigHash",Config.load(directory).fingerprint());report.put("arms",arms);
        report.put("verification",Map.of("repeatStateBothRngAndObservations",count*arms.size()*experiment.get("strategies").size(),"repeatLegacyAggregatesIdentical",true,"candidateABaselinesIdentical",true));
        Config.JSON.writeValue(output.toFile(),report);
    }
}
