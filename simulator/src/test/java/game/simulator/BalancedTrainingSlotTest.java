package game.simulator;

import game.domain.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BalancedTrainingSlotTest {
    Config config() throws Exception {return Config.load(Path.of("../config"));}
    @Test void executedSlotsFollowTheSevenMenuCycleAcrossDaysAndEventPauses() throws Exception {
        Config c=config();var policy=Config.JSON.readTree(Path.of("../config/strategies.json").toFile());
        for(int seed=0;seed<40;seed++) {
            List<String> executed=new ArrayList<>();Observations observer=new Observations() {
                @Override public void training(Training x) {super.training(x);if(x.outcome().equals("executed"))executed.add(x.menu());}
            };
            State result=Main.run(c,"balanced-training-slot",seed,policy,observer);
            for(int i=0;i<executed.size();i++)assertEquals(c.menus.get(i%7).id(),executed.get(i),"seed="+seed+", slot="+i);
            assertEquals(7,new HashSet<>(executed).size());observer.verify();
            State repeat=Main.run(c,"balanced-training-slot",seed,policy);
            assertEquals(Config.JSON.writeValueAsString(result),Config.JSON.writeValueAsString(repeat));
        }
    }
    @Test void calendarAliasPreservesOriginalStateAndBothRngStreams() throws Exception {
        Config c=config();var policy=Config.JSON.readTree(Path.of("../config/strategies.json").toFile());
        Observations a=new Observations(),b=new Observations();
        State old=Main.run(c,"balanced",19,policy,a),alias=Main.run(c,"balanced-calendar",19,policy,b);
        assertEquals(Config.JSON.writeValueAsString(old),Config.JSON.writeValueAsString(alias));
        assertEquals(a.choiceRngState,b.choiceRngState);
    }
    @Test void replacementSlotsDoNotConsumeTheCursorAndVacationMorningDoes() throws Exception {
        Config c=config();c.events.clear();Engine e=new Engine(c);State s=e.create(19,null);
        s.day=0;var p=BalancedTrainingSlots.plan(c,e,s);
        assertEquals("shooting",p.get("afternoon"));assertEquals("power",p.get("night"));
        s.day=133;p=BalancedTrainingSlots.plan(c,e,s);
        assertEquals("shooting",p.get("morning"));assertEquals("power",p.get("afternoon"));assertEquals("aerial",p.get("night"));
        s.day=168;s.supplementRequired=true;p=BalancedTrainingSlots.plan(c,e,s);
        assertEquals("shooting",p.get("night"));
        e.apply(s,Action.day(withNonTraining(p),null));assertEquals(1,total(s));assertEquals("power",BalancedTrainingSlots.plan(c,e,s).get("night"));
        s=e.create(19,null);s.day=2;assertTrue(e.cupDay(s));e.apply(s,Action.day(withNonTraining(BalancedTrainingSlots.plan(c,e,s)),null));assertEquals(0,total(s));
        s=e.create(19,null);s.day=0;s.injuryUntilDay=3;e.apply(s,Action.day(withNonTraining(BalancedTrainingSlots.plan(c,e,s)),null));assertEquals(0,total(s));
        s=e.create(19,null);s.day=133;s.stamina=0;var low=withNonTraining(BalancedTrainingSlots.plan(c,e,s));low.put("dawn","exercise");e.apply(s,Action.day(low,null));assertEquals(0,total(s));assertEquals("shooting",BalancedTrainingSlots.plan(c,e,s).get("morning"));
        for(int day:List.of(5,6)) {s=e.create(19,null);s.day=day;e.apply(s,Action.day(withNonTraining(BalancedTrainingSlots.plan(c,e,s)),day==6?"rest":null));while(s.pendingEvent!=null)e.apply(s,Action.event(0));assertEquals(0,total(s));}
    }
    @Test void newInjuryStopsTheRemainingPlanWithoutAdvancingCancelledSlots() throws Exception {
        Config c=config();c.events.clear();boolean found=false;
        for(int seed=0;seed<1000 && !found;seed++) {
            Observations o=new Observations();Engine e=new Engine(c,o);State s=e.create(seed,null);s.day=133;s.stamina=26;
            var p=withNonTraining(BalancedTrainingSlots.plan(c,e,s));p.put("dawn","exercise");e.apply(s,Action.day(p,null));
            if(s.injuries>0) {found=true;assertTrue(total(s)<=1);s.day=s.injuryUntilDay;String next=c.menus.get(total(s)%7).id();assertEquals(next,BalancedTrainingSlots.plan(c,e,s).get("morning"));}
        }
        assertTrue(found,"Actual configured 3% injury path must be covered");
    }
    private static int total(State s) {return s.trainingCounts.values().stream().mapToInt(Integer::intValue).sum();}
    private static Map<String,String> withNonTraining(Map<String,String> plan) {var m=new LinkedHashMap<>(plan);m.put("dawn","sleep");m.put("class","focus");return m;}
}
