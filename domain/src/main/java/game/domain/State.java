package game.domain;

import java.util.*;

public final class State {
    public long seed, rngState, seq;
    public String schoolId, configHash;
    public int day, stage, condition, injuryUntilDay;
    public boolean activeDay, excludedToday, supplementRequired, winterSupplementRequired, completed;
    public double stamina, academic, money, reputation;
    public Map<String,Double> stats=new LinkedHashMap<>(), initialStats=new LinkedHashMap<>(), affinity=new LinkedHashMap<>();
    public Map<String,String> selections=new LinkedHashMap<>(), defenders=new LinkedHashMap<>();
    public Map<String,Integer> trainingCounts=new LinkedHashMap<>(), eventLastWeek=new LinkedHashMap<>();
    public LinkedHashSet<String> flags=new LinkedHashSet<>();
    public List<String> cupAlive=new ArrayList<>(), report=new ArrayList<>();
    public List<Standing> standings=new ArrayList<>();
    public List<Fixture> leagueFixtures=new ArrayList<>();
    public List<Match> matches=new ArrayList<>(), todayMatches=new ArrayList<>(), competitionMatches=new ArrayList<>();
    public String pendingEvent, lastMatchRole="bench", sundayAction;
    public int injuries, exclusions, supplementDays;
    public double[] weeklyStamina;
    public int[] weeklyDays;
    public static final class Standing {
        public String schoolId; public int played,won,drawn,lost,goalsFor,goalsAgainst,points;
        public Standing() {}
        public Standing(String id) { schoolId=id; }
        public int goalDifference() { return goalsFor-goalsAgainst; }
    }
    public record Fixture(int round,String home,String away) {}
    public record Moment(int minute,String sceneId,String text,double chance,boolean success,String outcome) {}
    public static final class Match {
        public int day,homeGoals,awayGoals,goals,assists,penaltiesHome,penaltiesAway;
        public String competition,home,away,winner,role;
        public Double rating;
        public List<Moment> moments=new ArrayList<>();
    }
}
