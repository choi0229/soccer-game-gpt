package game.simulator;
import game.domain.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
class StrategyTest {
    @Test void allStrategiesFinishDeterministically() throws Exception {
        Config c=Config.load(Path.of("../config"));var policy=Config.JSON.readTree(Path.of("../config/strategies.json").toFile());
        for(String strategy:new String[]{"random","training","balanced"}) {
            var a=Main.run(c,strategy,19,policy);var b=Main.run(c,strategy,19,policy);
            assertTrue(a.completed);assertEquals(336,a.day);
            assertEquals(Config.JSON.writeValueAsString(a),Config.JSON.writeValueAsString(b));
        }
    }
}
