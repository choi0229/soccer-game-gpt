package game.domain;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
class DayTest {
    Config c; Engine e; State s;
    @BeforeEach void setup() throws Exception { c=Config.load(Path.of("../config")); c.events.clear(); e=new Engine(c); s=e.create(7,null); }
    @Test void datesVacationsAndBoundaries() {
        assertEquals("3월 1주 월",e.date(s)); s.day=19*7; assertTrue(e.vacation(s));
        s.day=24*7; assertFalse(e.vacation(s)); s.day=40*7; assertTrue(e.vacation(s));
        s.day=47*7+6; assertEquals("2월 4주 일",e.date(s));
    }
    @Test void dailyGrowthAndPersistentMenus() {
        double power=s.stats.get("shotPower"),head=s.stats.get("heading");
        e.apply(s,Action.day(Map.of(),null));
        assertEquals(power+c.n("teamGrowth")*c.n("powerGrowth"),s.stats.get("shotPower"),1e-9);
        assertEquals(head+c.n("personalGrowth")*c.n("powerGrowth"),s.stats.get("heading"),1e-9);
        e.apply(s,Action.day(Map.of("night","link"),null)); e.apply(s,Action.day(Map.of(),null));
        assertEquals("link",s.selections.get("night")); assertEquals(3,s.day); assertEquals(3,s.seq);
    }
    @Test void excludedAndRehabHaveNoGrowth() {
        s.stamina=0; e.apply(s,Action.day(Map.of("dawn","exercise","class","focus"),null));
        assertEquals(1,s.exclusions); assertEquals(c.n("coachInitial")-c.n("excludeCoachCost"),s.affinity.get("coach"));
        double before=s.stats.get("shotPower"); s.injuryUntilDay=20;
        e.apply(s,Action.day(Map.of("dawn","sleep"),null)); assertEquals(before,s.stats.get("shotPower"));
        assertThrows(IllegalArgumentException.class,()->e.apply(s,Action.day(Map.of("dawn","exercise"),null)));
    }
    @Test void growthDiminishesAtExactThresholds() {
        for(double v:new double[]{59.9,60,79.9,80}) {
            s.stats.put("shotPower",v);
            double decay=v<60?1:v<80?.7:.4;
            assertEquals(.15*1.2*decay,e.growth(s,"shotPower",.15,1),1e-9);
        }
    }
    @Test void summerSupplementReplacesAfternoonOnly() {
        s.day=24*7; s.supplementRequired=true; s.academic=10;
        e.apply(s,Action.day(Map.of("class","nap"),null));
        assertEquals(1,s.supplementDays); assertEquals(10.5,s.academic); assertEquals(0,s.trainingCounts.get("power"));
    }
    @Test void yearRunsAndReplays() throws Exception {
        State other=e.create(7,null);
        for(int day=0;day<c.totalDays();day++) {
            Action a=Action.day(Map.of("dawn","sleep"),day%7==6?"rest":null);
            e.apply(s,a);e.apply(other,a);
        }
        assertTrue(s.completed); assertEquals(c.totalDays(),s.day);
        assertEquals(Config.JSON.writeValueAsString(s),Config.JSON.writeValueAsString(other));
    }
}
