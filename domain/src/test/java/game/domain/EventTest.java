package game.domain;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
class EventTest {
    Config c;Engine e;State s;
    @BeforeEach void setup() throws Exception { c=Config.load(Path.of("../config"));e=new Engine(c);s=e.create(42,null); }
    @Test void exactlyThirtyStoriesWithSpecifiedAxes() {
        assertEquals(30,c.events.size());assertEquals(8,c.events.stream().filter(x->x.axis().equals("coach")).count());
        assertEquals(8,c.events.stream().filter(x->x.axis().equals("teammate")).count());assertEquals(5,c.events.stream().filter(x->x.axis().equals("family")).count());
        assertEquals(6,c.events.stream().filter(x->x.axis().equals("school")).count());assertEquals(3,c.events.stream().filter(x->x.axis().equals("common")).count());
        assertTrue(c.events.stream().filter(x->!x.once()).count()>=12);
        assertTrue(c.events.stream().allMatch(x->x.body()!=null&&!x.body().isBlank()&&x.choices().size()>=2&&x.choices().size()<=3));
    }
    @Test void classroomPausesThenContinuesRemainingSlotsExactlyOnce() {
        c.rules.put("schoolEventChance",1.);double initial=s.stats.get("shotPower");
        e.apply(s,Action.day(Map.of("class","teacher"),null));assertNotNull(s.pendingEvent);assertEquals(0,s.day);assertEquals(2,s.stage);
        assertEquals(initial,s.stats.get("shotPower"));assertThrows(IllegalArgumentException.class,()->e.apply(s,Action.day(Map.of(),null)));
        e.apply(s,Action.event(0));assertNull(s.pendingEvent);assertEquals(1,s.day);assertEquals(1,s.trainingCounts.get("power"));assertEquals(2,s.seq);
    }
    @Test void sundayCanQueueTwoEventsWithoutRepeatingRecovery() {
        c.rules.put("sundayEventChance",1.);s.day=6;s.stamina=0;
        e.apply(s,Action.day(Map.of(),"coach"));String first=s.pendingEvent;assertNotNull(first);
        e.apply(s,Action.event(0));assertNotNull(s.pendingEvent);assertNotEquals(first,s.pendingEvent);assertEquals(6,s.day);
        e.apply(s,Action.event(0));assertEquals(7,s.day);assertFalse(s.activeDay);assertEquals(1,s.weeklyDays[0]);
    }
    @Test void onceFlagsAndFourWeekCooldown() {
        var repeat=c.event("coach_002");s.eventLastWeek.put(repeat.id(),1);s.day=3*7;assertFalse(e.eligible(s,repeat));s.day=4*7;assertTrue(e.eligible(s,repeat));
        var once=c.event("coach_001");s.eventLastWeek.put(once.id(),1);s.day=30*7;assertFalse(e.eligible(s,once));
        s.pendingEvent=once.id();s.activeDay=true;s.stage=4;e.apply(s,Action.event(0));assertTrue(s.flags.contains("introduced"));
    }
    @Test void injuryAndAffinityTriggersAreDataDriven() {
        var bench=c.event("coach_004");assertFalse(e.eligible(s,bench));s.day=7*7;assertTrue(e.eligible(s,bench));s.affinity.put("coach",41.);assertFalse(e.eligible(s,bench));
        var rehab=c.event("coach_005");assertFalse(e.eligible(s,rehab));s.injuryUntilDay=s.day+7;assertTrue(e.eligible(s,rehab));
    }
    @Test void replayIncludesAllEventChoicesAndJsonRestoration() throws Exception {
        State b=e.create(42,null);
        while(!s.completed) {
            Action a=s.pendingEvent!=null?Action.event(0):Action.day(Map.of("dawn","sleep","class","teacher"),e.weekday(s)==6?"coach":null);
            e.apply(s,a);e.apply(b,a);
            // Server-style JSON snapshot round trip at every boundary.
            b=Config.JSON.readValue(Config.JSON.writeValueAsBytes(b),State.class);
        }
        assertEquals(Config.JSON.writeValueAsString(s),Config.JSON.writeValueAsString(b));
        assertTrue(s.seq>c.totalDays());
    }
}
