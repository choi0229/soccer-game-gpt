package game.simulator;

import game.domain.*;
import java.util.*;

/** Simulator-only planning. Completed training counts are the deterministic cursor. */
final class BalancedTrainingSlots {
    static Map<String,String> plan(Config c,Engine e,State s) {
        int cursor=s.trainingCounts.values().stream().mapToInt(Integer::intValue).sum();
        Map<String,String> selections=new LinkedHashMap<>();
        for(String slot:List.of("morning","afternoon","night")) {
            selections.put(slot,c.menus.get(cursor%c.menus.size()).id());
            boolean eligible=e.weekday(s)<5 && !e.injured(s);
            if(slot.equals("morning")) eligible&=e.vacation(s);
            else {
                eligible&=!e.cupDay(s);
                if(slot.equals("afternoon")) eligible&=!(s.supplementRequired && c.calendar.supplementWeeks.contains(e.week(s)));
            }
            if(eligible) cursor++;
        }
        // Unexpected injury/exclusion terminates every later training slot that day.
        // Successful slots therefore form a prefix of this plan. The next request
        // starts from actual completions, including completions after an event pause.
        return selections;
    }
}
