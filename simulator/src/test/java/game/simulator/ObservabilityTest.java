package game.simulator;

import game.domain.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ObservabilityTest {
    private static class Traced extends Observations {
        final MessageDigest digest;
        Traced() throws Exception {digest=MessageDigest.getInstance("SHA-256");}
        @Override public boolean traceActions() {return true;}
        @Override public void actionBoundary(String action,String state,long choice) {digest.update(action.getBytes(StandardCharsets.UTF_8));digest.update(state.getBytes(StandardCharsets.UTF_8));digest.update(Long.toString(choice).getBytes(StandardCharsets.UTF_8));}
    }
    @Test void allActionStatesAndBothRngMatchIndependentPreChangeFixtures() throws Exception {
        Config c=Config.load(Path.of("../config"));var policy=Config.JSON.readTree(Path.of("../config/strategies.json").toFile());
        var fixture=Config.JSON.readTree(getClass().getResourceAsStream("/observability-baseline.json"));assertEquals(c.fingerprint(),fixture.get("configHash").asText());
        for(var row:fixture.get("cases")) {
            var observer=new Traced();String strategy=row.get("strategy").asText();long seed=row.get("seed").asLong();
            State observed=Main.run(c,strategy,seed,policy,observer),disabled=Main.run(c,strategy,seed,policy);
            assertEquals(Config.JSON.writeValueAsString(disabled),Config.JSON.writeValueAsString(observed));
            assertEquals(row.get("stateSha256").asText(),ObservabilityMain.stateHash(observed));
            assertEquals(row.get("actionTraceSha256").asText(),HexFormat.of().formatHex(observer.digest.digest()));
            assertEquals(row.get("rngState").asLong(),observed.rngState);assertEquals(row.get("choiceRngState").asLong(),observer.choiceRngState);observer.verify();
        }
    }
    @Test void scheduledSlotsAndBalancedWeekendMenusReconcileWithActualState() throws Exception {
        Config c=Config.load(Path.of("../config"));var policy=Config.JSON.readTree(Path.of("../config/strategies.json").toFile());
        var observer=new Observations();State s=Main.run(c,"balanced",19,policy,observer);observer.verify();
        assertEquals(175,observer.classes.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(545,observer.menus.values().stream().mapToInt(x->x.selected).sum());
        assertTrue(observer.menus.get("pressing").policySelections>0);assertEquals(0,observer.menus.get("pressing").executed);assertEquals(0,observer.menus.get("stamina").selected);
        for(var menu:c.menus)assertEquals(s.trainingCounts.get(menu.id()).intValue(),observer.menus.get(Observations.menuName(menu.id())).executed);
        assertEquals(s.injuries,observer.injuries);assertEquals(s.matches.size(),observer.appearances.size());
        assertEquals(observer.baseScenes+observer.addedScenes,s.matches.stream().mapToInt(m->m.moments.size()).sum());
    }
    @Test void replacementGuardsReviewSnapshotsAndClampLossesAreObservedWithoutChangingOutcomes() throws Exception {
        Config c=Config.load(Path.of("../config"));c.events.clear();var obs=new Observations();Engine e=new Engine(c,obs);State s=e.create(42,null);
        s.day=24*7;s.supplementRequired=true;s.injuryUntilDay=s.day+7;s.academic=99.8;s.affinity.put("coach",99.);
        e.apply(s,Action.day(Map.of("dawn","sleep","class","focus"),null));
        assertEquals(1,obs.menus.get("power").replaced.get("supplement"));assertEquals(1,obs.menus.get("aerial").replaced.get("injury"));assertEquals(0,obs.injuryRolls);
        assertTrue(obs.academicSources.get("classroom").lostIncrease>0);assertEquals(100,s.academic);
        s.day=18*7+6;s.academic=29;e.apply(s,Action.day(Map.of(),"coach"));assertEquals(29,obs.academicWeek19);assertTrue(obs.failedWeek19);
        assertEquals(1,obs.coachSources.get("sundayMeet").appliedIncrease);assertEquals(4,obs.coachSources.get("sundayMeet").lostIncrease);
        s.day=2;s.injuryUntilDay=0;e.apply(s,Action.day(Map.of("dawn","sleep"),null));assertEquals(2,obs.menus.values().stream().mapToInt(m->m.replaced.getOrDefault("match",0)).sum());
    }
    @Test void actualLowStaminaRollAndExclusionSlotsUseDifferentDenominators() throws Exception {
        Config c=Config.load(Path.of("../config"));c.events.clear();var obs=new Observations();Engine e=new Engine(c,obs);State s=e.create(9,null);
        s.day=19*7;s.stamina=10;e.apply(s,Action.day(Map.of("dawn","exercise"),null));
        assertEquals(0,obs.injuryRolls);assertEquals(3,obs.trainingGroups.get("vacation").excluded);assertEquals(1,s.exclusions);
        State b=e.create(9,null);b.day=19*7;b.stamina=26;e.apply(b,Action.day(Map.of("dawn","exercise"),null));assertTrue(obs.injuryRolls>0);
    }
}
