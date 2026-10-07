package game.simulator;

import game.domain.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EventAcademicExperimentTest {
    Path directory=Path.of("../config");
    @Test void scalingChangesOnlyAcademicEffectsIncludingNegativeAndZeroValues() throws Exception {
        var spec=Config.JSON.readTree(directory.resolve("experiments/event-academics.json").toFile());Config base=Config.load(directory);
        assertEquals(base.fingerprint(),EventAcademicExperiment.candidate(directory,spec,"A").fingerprint());
        for(String arm:List.of("A","B","C")) {
            Config c=EventAcademicExperiment.candidate(directory,spec,arm);double multiplier=spec.get("candidates").get(arm).asDouble();
            var normalized=Config.JSON.valueToTree(c);((com.fasterxml.jackson.databind.node.ObjectNode)normalized).set("events",Config.JSON.valueToTree(base.events));assertEquals(Config.JSON.valueToTree(base),normalized);
            for(int i=0;i<c.events.size();i++)for(int j=0;j<c.events.get(i).choices().size();j++) {
                var original=base.events.get(i).choices().get(j).effects();var actual=c.events.get(i).choices().get(j).effects();
                assertEquals(original.keySet(),actual.keySet());
                for(String key:original.keySet())if(key.equals("academic"))assertEquals(((Number)original.get(key)).doubleValue()*multiplier,((Number)actual.get(key)).doubleValue());else assertEquals(original.get(key),actual.get(key));
            }
            assertEquals(1.5,c.n("focusAcademic"));assertEquals("balanced-training-slot",spec.get("strategies").get(2).asText());
        }
    }
    @Test void actualEventAcademicGainScalesWhileOtherEffectsAndClassroomRemainEqual() throws Exception {
        var spec=Config.JSON.readTree(directory.resolve("experiments/event-academics.json").toFile());List<State> results=new ArrayList<>();
        for(String arm:List.of("A","B","C")) {
            Config c=EventAcademicExperiment.candidate(directory,spec,arm);Engine e=new Engine(c);State s=e.create(42,null);
            // Apply the real event with a pending day intentionally inactive to isolate effects.
            var event=c.events.stream().filter(x->x.choices().get(0).effects().containsKey("academic") && x.choices().get(0).effects().size()>1).findFirst().orElseThrow();
            s.pendingEvent=event.id();e.apply(s,Action.event(0));
            assertEquals(50+((Number)event.choices().get(0).effects().get("academic")).doubleValue(),s.academic);
            results.add(s);
            c.events.clear();s=e.create(42,null);double initial=s.academic;e.apply(s,Action.day(Map.of("class","focus","dawn","sleep"),null));assertEquals(initial+1.5*1.15,s.academic,1e-12);
        }
        var a=Config.JSON.valueToTree(results.get(0));((com.fasterxml.jackson.databind.node.ObjectNode)a).remove(List.of("academic","configHash"));
        for(State s:results) {var b=Config.JSON.valueToTree(s);((com.fasterxml.jackson.databind.node.ObjectNode)b).remove(List.of("academic","configHash"));assertEquals(a,b);}
    }
    @Test void eventAxesAndBothRngAreDeterministicAndObservationDoesNotChangeResults() throws Exception {
        var spec=Config.JSON.readTree(directory.resolve("experiments/event-academics.json").toFile());var policy=Config.JSON.readTree(directory.resolve("strategies.json").toFile());
        for(String arm:List.of("A","B","C"))for(var strategy:spec.get("strategies")) {
            Config c=EventAcademicExperiment.candidate(directory,spec,arm);var o=new EventAcademicExperiment.AcademicObserver();
            State a=Main.run(c,strategy.asText(),19,policy,o,x->o.snapshot(c,x));State b=Main.run(c,strategy.asText(),19,policy);
            assertEquals(Config.JSON.writeValueAsString(a),Config.JSON.writeValueAsString(b));o.verify();assertTrue(o.academicEventOccurrences>0);
            double axes=o.eventAxes.values().stream().mapToDouble(x->x.appliedIncrease).sum();assertEquals(o.academicSources.get("event").appliedIncrease,axes,1e-8);
            if(arm.equals("C"))assertEquals(0,axes);
            var repeat=new EventAcademicExperiment.AcademicObserver();Main.run(c,strategy.asText(),19,policy,repeat,x->repeat.snapshot(c,x));assertEquals(o.choiceRngState,repeat.choiceRngState);
            if(strategy.asText().equals("balanced-training-slot"))assertTrue(a.trainingCounts.values().stream().allMatch(x->x>0));
        }
    }
}
