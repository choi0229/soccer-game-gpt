package game.domain;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
class BoundaryTest {
    Config c;Engine e;State s;
    @BeforeEach void setup() throws Exception { c=Config.load(Path.of("../config"));c.events.clear();e=new Engine(c);s=e.create(99,null); }
    @Test void weekdayCupKeepsFirstTwoSlotsAndReplacesLastTwo() {
        s.day=2;double academic=s.academic;
        e.apply(s,Action.day(Map.of(),null));assertEquals(1,s.todayMatches.size());
        assertTrue(s.academic>academic);assertEquals(0,s.trainingCounts.values().stream().mapToInt(Integer::intValue).sum());
    }
    @Test void eliminatedPlayerHasNormalWeekdayAndEmptySaturday() {
        s.cupAlive=new ArrayList<>(c.schools.stream().map(Config.School::id).filter(id->!id.equals(s.schoolId)).limit(16).toList());s.day=2;
        e.apply(s,Action.day(Map.of(),null));assertTrue(s.todayMatches.isEmpty());assertEquals(2,s.trainingCounts.values().stream().mapToInt(Integer::intValue).sum());
        s.day=5; e.apply(s,Action.day(Map.of(),null));assertTrue(s.todayMatches.isEmpty());assertEquals(2,s.trainingCounts.values().stream().mapToInt(Integer::intValue).sum());
    }
    @Test void injuryAtSlotStartStopsGrowthAndFutureTraining() {
        c.rules.put("injuryChance",1.);s.stamina=20;double before=s.stats.get("shotPower");
        e.apply(s,Action.day(Map.of(),null));assertEquals(1,s.injuries);assertTrue(e.injured(s));
        assertEquals(before,s.stats.get("shotPower"));assertEquals(0,s.trainingCounts.get("aerial"));
        assertTrue(s.injuryUntilDay>=7&&s.injuryUntilDay<=28);
    }
    @Test void saturdayConditionDropsBeforeRecoveryAndSundayRestRestores() {
        s.day=26*7+5;s.stamina=29; e.apply(s,Action.day(Map.of(),null));assertEquals(1,s.condition);assertEquals(43,s.stamina);
        e.apply(s,Action.day(Map.of(),"rest"));assertEquals(2,s.condition);assertEquals(Math.min(e.maxStamina(s),43+25+14+20),s.stamina);
    }
    @Test void summerReviewAndTwoWeeksOfSupplement() {
        s.day=18*7+6;s.academic=10;e.apply(s,Action.day(Map.of(),"rest"));assertTrue(s.supplementRequired);
        s.day=24*7;
        for(int day=0;day<14;day++) e.apply(s,Action.day(Map.of(),day%7==6?"rest":null));
        assertEquals(10,s.supplementDays);
        e.apply(s,Action.day(Map.of(),null));assertEquals(10,s.supplementDays);
    }
    @Test void sceneChainsAndSingleUseDribbleBonus() {
        s.stats.replaceAll((id,v)->50.);s.affinity.put("coach",100.);s.defenders.replaceAll((id,v)->"fighterResponse");
        c.scenes=new ArrayList<>(List.of(c.scene("dribble"),c.scene("box"),c.scene("run"),c.scene("oneOnOne")));
        boolean sawAdded=false,sawBoosted=false,sawConsumed=false;
        for(int seed=0;seed<300;seed++) {
            State player=e.create(seed,s.schoolId);player.stats.replaceAll((id,v)->50.);player.affinity.put("coach",100.);
            State.Match m=e.match(player,new Rng(seed),player.schoolId,"s08","test",false);boolean boosted=false;
            for(int i=0;i<m.moments.size();i++) {
                var moment=m.moments.get(i);String id=moment.sceneId();
                if(id.equals("run")&&moment.success()) {assertTrue(i+1<m.moments.size());assertEquals("oneOnOne",m.moments.get(i+1).sceneId());sawAdded=true;}
                if(id.equals("box")||id.equals("oneOnOne")) {
                    assertEquals(boosted?48:33,moment.chance(),1e-9);
                    if(boosted) sawBoosted=true;else if(sawBoosted) sawConsumed=true;boosted=false;
                }
                if(id.equals("dribble")&&moment.success()) boosted=true;
            }
            assertTrue(m.moments.size()<=c.i("starterScenesMax")*2);
        }
        assertTrue(sawAdded);assertTrue(sawBoosted);assertTrue(sawConsumed);
    }
}
