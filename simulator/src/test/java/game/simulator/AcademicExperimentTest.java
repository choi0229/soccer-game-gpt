package game.simulator;

import game.domain.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AcademicExperimentTest {
    Path directory=Path.of("../config");
    @Test void fileCandidatesChangeOnlyTheExistingFocusRule() throws Exception {
        var spec=Config.JSON.readTree(directory.resolve("experiments/academics.json").toFile());
        var base=Config.JSON.valueToTree(Config.load(directory));
        Set<Double> gains=new HashSet<>();
        for(String arm:List.of("A","B","C")) {
            Config c=AcademicExperiment.candidate(directory,spec,arm);gains.add(c.n("focusAcademic"));
            var normalized=Config.JSON.valueToTree(c);((com.fasterxml.jackson.databind.node.ObjectNode)normalized.get("rules")).set("focusAcademic",base.get("rules").get("focusAcademic"));assertEquals(base,normalized);
        }
        assertEquals(Set.of(1.5,1.0,.75),gains);
        assertEquals("balanced-training-slot",spec.get("strategies").get(2).asText());
    }
    @Test void focusGainIsAppliedWithTheUnchangedSchoolMultiplier() throws Exception {
        var spec=Config.JSON.readTree(directory.resolve("experiments/academics.json").toFile());
        for(String arm:List.of("A","B","C")) {
            Config c=AcademicExperiment.candidate(directory,spec,arm);c.events.clear();
            AcademicExperiment.AcademicObserver o=new AcademicExperiment.AcademicObserver();Engine e=new Engine(c,o);State s=e.create(42,null);double initial=s.academic,school=s.affinity.get("school");
            e.apply(s,Action.day(Map.of("class","focus","dawn","sleep"),null));
            assertEquals(initial+c.n("focusAcademic")*(1+school/200),s.academic,1e-12);
            assertEquals(1,o.classes.get("focus"));assertEquals(c.n("focusAcademic")*(1+school/200),o.decomposition.get("focus").appliedIncrease,1e-12);
            s=e.create(42,null);e.apply(s,Action.day(Map.of("class","nap","dawn","sleep"),null));assertEquals(initial-.5,s.academic,1e-12);
            s=e.create(42,null);e.apply(s,Action.day(Map.of("class","teacher","dawn","sleep"),null));assertEquals(initial+.5,s.academic,1e-12);assertEquals(school+3,s.affinity.get("school"),1e-12);
        }
    }
    @Test void everyCandidateAndStrategyHasIdenticalObservedAndUnobservedResults() throws Exception {
        var spec=Config.JSON.readTree(directory.resolve("experiments/academics.json").toFile());var policy=Config.JSON.readTree(directory.resolve("strategies.json").toFile());
        for(String arm:List.of("A","B","C"))for(var strategy:spec.get("strategies")) {
            Config c=AcademicExperiment.candidate(directory,spec,arm);
            var o=new AcademicExperiment.AcademicObserver();State a=Main.run(c,strategy.asText(),19,policy,o),b=Main.run(c,strategy.asText(),19,policy);
            assertEquals(Config.JSON.writeValueAsString(a),Config.JSON.writeValueAsString(b));o.verify();
            var repeat=new AcademicExperiment.AcademicObserver();Main.run(c,strategy.asText(),19,policy,repeat);assertEquals(o.choiceRngState,repeat.choiceRngState);
            if(strategy.asText().equals("balanced-training-slot"))assertTrue(a.trainingCounts.values().stream().allMatch(x->x>0));
        }
    }
}
