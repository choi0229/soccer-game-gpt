package game.simulator;

import game.domain.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Reproducible Experiment 1; never writes any existing baseline output. */
public final class BalancedMenuExperiment {
    public static void main(String[] args) throws Exception {
        Path directory=Path.of(args.length>0?args[0]:"config");
        Path output=Path.of(args.length>1?args[1]:"reports/experiment-1-balanced-menu.json");
        if(Files.exists(output)) throw new IllegalArgumentException("Output already exists: "+output);
        Config c=Config.load(directory);var policy=Config.JSON.readTree(directory.resolve("strategies.json").toFile());
        var reference=Config.JSON.readTree(Path.of("reports/observability-baseline-v1.json").toFile());
        Map<Long,com.fasterxml.jackson.databind.JsonNode> fingerprints=new HashMap<>();
        for(var x:reference.get("stateFingerprints")) if(x.get("strategy").asText().equals("balanced")) fingerprints.put(x.get("seed").asLong(),x);
        Map<String,Object> results=new LinkedHashMap<>();
        for(String strategy:List.of("balanced-calendar","balanced-training-slot")) {
            List<State> states=new ArrayList<>();List<Object> seasons=new ArrayList<>();Observations pooled=new Observations();
            for(int seed=0;seed<1000;seed++) {
                Observations observed=new Observations();Map<String,Double> week18=new LinkedHashMap<>();
                State s=Main.run(c,strategy,seed,policy,observed,x->{if(x.day==126 && !x.activeDay) week18.putAll(x.stats);});
                Observations repeat=new Observations();State repeated=Main.run(c,strategy,seed,policy,repeat);
                String serialized=Config.JSON.writeValueAsString(s);
                if(!serialized.equals(Config.JSON.writeValueAsString(repeated)) || observed.choiceRngState!=repeat.choiceRngState) throw new IllegalStateException("Repeat mismatch");
                if(strategy.equals("balanced-calendar")) {
                    var f=fingerprints.get((long)seed);
                    String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    if(!sha.equals(f.get("stateSha256").asText()) || s.rngState!=f.get("rngState").asLong() || observed.choiceRngState!=f.get("choiceRngState").asLong()) throw new IllegalStateException("Baseline mismatch "+seed);
                }
                observed.verify();pooled.merge(observed);states.add(s);
                Map<String,Object> row=new LinkedHashMap<>();row.put("seed",seed);row.put("statsWeek18",week18);row.put("statsWeek48",s.stats);row.put("leagueRank",new Engine(c).leagueRank(s));row.put("reputation",s.reputation);
                row.put("observations",observed.report(true));seasons.add(row);
            }
            var legacy=Main.aggregate(c,states);
            if(strategy.equals("balanced-calendar") && !Config.JSON.valueToTree(legacy).equals(Config.JSON.readTree(Path.of("reports/balance.json").toFile()).get("strategies").get("balanced"))) throw new IllegalStateException("Legacy aggregate mismatch");
            results.put(strategy,Map.of("legacyBalance",legacy,"observations",pooled.report(false),"seasons",seasons));
            System.err.println(strategy+": 1000 seasons, repeat and reconciliation passed");
        }
        Map<String,Object> report=new LinkedHashMap<>();report.put("experiment",1);report.put("runsPerArm",1000);report.put("baseSeed",0);report.put("configHash",c.fingerprint());report.put("strategyConfig",policy);
        report.put("verification",Map.of("baselineStateAndBothRngMatches",1000,"baselineLegacyAggregateIdentical",true,"repeatStatesAndBothRngMatches",2000,"seasonReconciliations",2000));report.put("arms",results);
        Config.JSON.writeValue(output.toFile(),report);
    }
}
