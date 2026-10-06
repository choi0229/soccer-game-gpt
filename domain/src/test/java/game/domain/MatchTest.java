package game.domain;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
class MatchTest {
    Config c; Engine e; State s;
    @BeforeEach void setup() throws Exception { c=Config.load(Path.of("../config")); c.events.clear(); e=new Engine(c); s=e.create(12,null); }
    @Test void scheduleHasHomeAndAwayForEveryPair() {
        var fixtures=s.leagueFixtures.stream().filter(f->f.home().equals(s.schoolId)||f.away().equals(s.schoolId)).toList();
        assertEquals(14,fixtures.size()); assertEquals(7,fixtures.stream().filter(f->f.home().equals(s.schoolId)).count());
        assertEquals(14,fixtures.stream().map(State.Fixture::round).distinct().count());
    }
    @Test void rolesAndInjury() {
        s.affinity.put("coach",100.); assertEquals("starter",e.role(s));
        s.affinity.put("coach",0.);s.stats.replaceAll((k,v)->0.);assertEquals("bench",e.role(s));
        s.injuryUntilDay=10; assertEquals("bench",e.role(s));
    }
    @Test void chanceCapsAndPassiveConditions() {
        String opp="s01";s.defenders.put(opp,"fighterResponse");
        s.stats.replaceAll((k,v)->100.);s.condition=4;
        assertEquals(10,e.passiveBonus(s,opp,60,1,1,true));
        assertTrue(e.successChance(s,c.scene("box"),opp,60,1,1,true,100)<=95);
        s.stats.replaceAll((k,v)->0.);s.condition=0;
        assertEquals(-10,e.passiveBonus(s,opp,60,0,1,false));
        assertEquals(5,e.successChance(s,c.scene("box"),opp,60,0,1,false,0));
    }
    @Test void poissonHasExpectedMean() {
        Rng r=new Rng(9);double sum=0;int samples=100000;
        for(int i=0;i<samples;i++) sum+=e.poisson(r,1.2);
        assertEquals(1.2,sum/samples,.02); assertEquals(.2,e.expectedGoals(0,100)); assertEquals(3.5,e.expectedGoals(100,0));
    }
    @Test void yearCompletesAllLeaguesAndKnockout() {
        for(int day=0;day<c.totalDays();day++) e.apply(s,Action.day(Map.of("dawn","sleep"),day%7==6?"rest":null));
        assertEquals(1,s.cupAlive.size()); assertEquals(14,s.standings.get(0).played);
        assertEquals(32*14/2+31,s.competitionMatches.size());
        assertEquals(14,s.matches.stream().filter(m->m.competition.startsWith("주말리그")).count());
        assertTrue(s.matches.stream().allMatch(m->m.rating==null||m.rating>=3&&m.rating<=10));
    }
    @Test void tiebreakUsesIdAfterScoring() {
        assertEquals("s01",e.standings(s).get(0).schoolId);
        var row=s.standings.get(2);row.goalsFor=2;row.goalsAgainst=1;
        assertEquals(row.schoolId,e.standings(s).get(0).schoolId);
    }
}
