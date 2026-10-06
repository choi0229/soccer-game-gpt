package game.domain;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Data and I/O boundary only; the rule engine has no Spring dependency. */
public final class Config {
    public static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    public Map<String, Object> rules = new LinkedHashMap<>();
    public List<Stat> stats = new ArrayList<>();
    public List<Menu> menus = new ArrayList<>();
    public List<Scene> scenes = new ArrayList<>();
    public List<School> schools = new ArrayList<>();
    public List<Condition> conditions = new ArrayList<>();
    public List<Grade> grades = new ArrayList<>();
    public List<Event> events = new ArrayList<>();
    public Calendar calendar = new Calendar();
    public record Stat(String id, String label, boolean trainable, boolean primary) {}
    public record Menu(String id, String label, List<String> stats, double growthMultiplier, Map<String,Object> unlock) {}
    public record Scene(String id, String label, List<String> stats, String effect) {}
    public record School(String id, String name, int region, String type, String typeLabel, double power) {}
    public record Condition(String label, double training, double match) {}
    public record Grade(String label, double min) {}
    public record Choice(String text, Map<String,Object> effects) {}
    public record Event(String id, String axis, String title, String body, Map<String,Object> trigger,
                        List<Choice> choices, List<String> setFlags, boolean once) {}
    public static final class Calendar {
        public List<Integer> leagueWeeks, cupDays, vacationWeeks, academicReviewWeeks, supplementWeeks;
        public List<String> weekdays;
    }
    public double n(String key) { return ((Number)Objects.requireNonNull(rules.get(key),key)).doubleValue(); }
    public int i(String key) { return (int)n(key); }
    public String s(String key) { return (String)rules.get(key); }
    public Stat stat(String id) { return stats.stream().filter(s->s.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("알 수 없는 능력치: "+id)); }
    public Menu menu(String id) { return menus.stream().filter(m->m.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("알 수 없는 훈련: "+id)); }
    public Scene scene(String id) { return scenes.stream().filter(s->s.id().equals(id)).findFirst().orElseThrow(); }
    public School school(String id) { return schools.stream().filter(s->s.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("알 수 없는 학교")); }
    public Event event(String id) { return events.stream().filter(e->e.id().equals(id)).findFirst().orElseThrow(); }
    public String grade(double value) { return grades.stream().filter(g->value>=g.min()).findFirst().orElseThrow().label(); }
    public int totalDays() { return i("weeks")*i("daysPerWeek"); }
    public static Config load(Path directory) throws IOException {
        var root=JSON.createObjectNode();
        for(String key:List.of("rules","stats","menus","scenes","schools","conditions","grades","events","calendar"))
            root.set(key,JSON.readTree(directory.resolve(key+".json").toFile()));
        var c=JSON.treeToValue(root,Config.class); c.validate(); return c;
    }
    public void validate() {
        if(schools.size()!=32 || schools.stream().map(School::id).distinct().count()!=schools.size()) throw new IllegalArgumentException("32개 학교 ID 필요");
        if(stats.stream().map(Stat::id).distinct().count()!=stats.size()) throw new IllegalArgumentException("중복 능력치");
        for(var m:menus) { if(m.stats().isEmpty() || !m.unlock().isEmpty()) throw new IllegalArgumentException("이번 버전 메뉴는 해금 조건 없이 정의"); m.stats().forEach(this::stat); }
        for(var s:scenes) s.stats().forEach(this::stat);
        if(events.stream().map(Event::id).distinct().count()!=events.size()) throw new IllegalArgumentException("중복 이벤트");
        for(var e:events) if(e.choices().size()<2 || e.choices().size()>3) throw new IllegalArgumentException("선택지 수");
    }
    public String fingerprint() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(this))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
}
