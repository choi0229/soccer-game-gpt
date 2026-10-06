package game.domain;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
class ModelTest {
    @Test void configAndGrades() throws Exception {
        Config c=Config.load(Path.of("../config"));
        assertEquals(18,c.stats.size()); assertEquals(7,c.menus.size()); assertEquals(32,c.schools.size());
        assertEquals(14,c.calendar.leagueWeeks.size()); assertEquals(336,c.totalDays());
        assertEquals("G",c.grade(29)); assertEquals("F",c.grade(30)); assertEquals("S",c.grade(100));
        assertEquals(1.2,c.n("powerGrowth")); assertEquals(c.fingerprint(),Config.load(Path.of("../config")).fingerprint());
    }
    @Test void streamsAreReproducibleAndIndependent() {
        Rng a=new Rng(42), b=new Rng(42), choices=new Rng(42^0xD1B54A32D192ED03L);
        for(int i=0;i<1000;i++) { choices.next(); assertEquals(a.nextLong(),b.nextLong()); }
        var restored=new Rng(a.state); assertEquals(a.nextLong(),restored.nextLong());
    }
}
