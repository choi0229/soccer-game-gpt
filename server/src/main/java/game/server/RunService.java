package game.server;

import game.domain.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.Path;
import java.util.*;

@Service
public class RunService {
    private final JdbcTemplate db;
    private final Config current;
    public RunService(JdbcTemplate db,@Value("${game.config-dir:../config}") String directory) throws Exception { this.db=db;current=Config.load(Path.of(directory)); }
    public Config config() { return current; }
    private String json(Object x) { try { return Config.JSON.writeValueAsString(x); } catch(Exception e) { throw new IllegalStateException(e); } }
    private <T>T parse(String x,Class<T> type) { try { return Config.JSON.readValue(x,type); } catch(Exception e) { throw new IllegalStateException(e); } }
    private record Run(Config config,State state) {}
    private Run read(UUID id,boolean lock) {
        var rows=db.query("select config_json,state_json from runs where id=?"+(lock?" for update":""),(rs,n)->new Run(parse(rs.getString(1),Config.class),parse(rs.getString(2),State.class)),id);
        if(rows.isEmpty()) throw new NoSuchElementException("판을 찾을 수 없습니다.");return rows.get(0);
    }
    @Transactional
    public Map<String,Object> create(long seed,String schoolId) {
        UUID id=UUID.randomUUID();Engine engine=new Engine(current);State s=engine.create(seed,schoolId);s.seq=1;
        db.update("insert into runs(id,seed,school_id,config_json,state_json) values(?,?,?,?,?)",id,seed,s.schoolId,json(current),json(s));
        Action init=new Action("create",Map.of("schoolId",s.schoolId,"seed",Long.toString(seed)),null,null);
        db.update("insert into actions(run_id,seq,request_json) values(?,?,?)",id,s.seq,json(init));
        return view(id,new Run(current,s));
    }
    @Transactional(readOnly=true)
    public Map<String,Object> get(UUID id) { return view(id,read(id,false)); }
    @Transactional
    public Map<String,Object> act(UUID id,long expectedSeq,Action action) {
        Run run=read(id,true);if(run.state.seq!=expectedSeq) throw new Conflict("다른 요청이 이미 처리되었습니다. 새로고침 후 다시 선택하세요.");
        new Engine(run.config).apply(run.state,action);
        db.update("insert into actions(run_id,seq,request_json) values(?,?,?)",id,run.state.seq,json(action));
        db.update("update runs set state_json=? where id=?",json(run.state),id);
        return view(id,run);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> replay(UUID id) {
        Run run=read(id,false);var entries=db.query("select seq,request_json from actions where run_id=? order by seq",(rs,n)->Map.of("seq",rs.getLong(1),"request",parse(rs.getString(2),Action.class)),id);
        if(entries.isEmpty()) throw new IllegalStateException("초기 선택 기록 누락");
        Action init=(Action)entries.get(0).get("request");Engine e=new Engine(run.config);State s=e.create(Long.parseLong(init.changes().get("seed")),init.changes().get("schoolId"));s.seq=1;
        for(int i=1;i<entries.size();i++) { if(((Number)entries.get(i).get("seq")).longValue()!=s.seq+1) throw new IllegalStateException("행동 순번 누락");e.apply(s,(Action)entries.get(i).get("request")); }
        return Map.of("identical",json(s).equals(json(run.state)),"seed",Long.toString(s.seed),"sequence",s.seq,"configHash",s.configHash,"actions",entries,"state",s);
    }
    public static class Conflict extends RuntimeException { public Conflict(String message) { super(message); } }
    private Map<String,Object> view(UUID id,Run run) {
        var c=run.config;var s=run.state;var e=new Engine(c);Map<String,Object> out=new LinkedHashMap<>();
        // Responses contain server-calculated facts; clients do not implement game rules.
        out.put("totalWeeks",c.i("weeks"));out.put("statMax",c.n("statMax"));out.put("id",id);out.put("state",s);out.put("config",c);out.put("seed",Long.toString(s.seed));
        out.put("date",s.completed?"1학년 시즌 종료":e.date(s));out.put("vacation",e.vacation(s));out.put("weekday",e.weekday(s));out.put("week",Math.min(c.i("weeks"),e.week(s)));
        out.put("maxStamina",e.maxStamina(s));out.put("conditionLabel",c.conditions.get(s.condition).label());out.put("injured",e.injured(s));out.put("injuryDaysRemaining",Math.max(0,s.injuryUntilDay-s.day));
        out.put("school",c.school(s.schoolId));out.put("leagueRank",e.leagueRank(s));out.put("standings",e.standings(s));
        out.put("event",s.pendingEvent==null?null:c.event(s.pendingEvent));
        out.put("matchDay",!s.completed&&e.matchDay(s));
        var attributes=new ArrayList<Map<String,Object>>();for(var stat:c.stats) attributes.add(Map.of("id",stat.id(),"label",stat.label(),"primary",stat.primary(),"trainable",stat.trainable(),"value",s.stats.get(stat.id()),"grade",c.grade(s.stats.get(stat.id())),"change",s.stats.get(stat.id())-s.initialStats.get(stat.id())));
        out.put("attributes",attributes);
        var records=new LinkedHashMap<String,Object>();var appearances=s.matches.stream().filter(m->m.rating!=null).toList();
        records.put("games",s.matches.size());records.put("appearances",appearances.size());records.put("goals",s.matches.stream().mapToInt(m->m.goals).sum());records.put("assists",s.matches.stream().mapToInt(m->m.assists).sum());
        records.put("rating",appearances.stream().mapToDouble(m->m.rating).average().orElse(0));records.put("injuries",s.injuries);records.put("exclusions",s.exclusions);records.put("champion",s.cupAlive.size()==1?c.school(s.cupAlive.get(0)).name():null);out.put("records",records);
        out.put("slots",slots(e,s));return out;
    }
    private List<Map<String,Object>> slots(Engine e,State s) {
        if(e.weekday(s)>4 || s.completed) return List.of();
        List<Map<String,Object>> slots=new ArrayList<>();
        slots.add(slot("dawn","새벽",s.selections.get("dawn"),e.injured(s)?List.of(option("sleep","더 자기")):List.of(option("exercise","개인 운동"),option("sleep","더 자기")),null));
        if(e.vacation(s)) slots.add(slot("morning","오전",s.selections.get("morning"),menus(e.c),e.injured(s)?"재활":null));
        else slots.add(slot("class","오전 · 수업",s.selections.get("class"),List.of(option("focus","수업 집중"),option("nap","졸기"),option("teacher","선생님과 대화"),option("friends","친구와 어울리기")),null));
        String afternoon=e.cupDay(s)?"춘계배 경기":s.supplementRequired&&e.c.calendar.supplementWeeks.contains(e.week(s))?"보충수업":e.injured(s)?"재활":null;
        String night=e.cupDay(s)?"춘계배 경기":e.injured(s)?"재활":null;
        slots.add(slot("afternoon","오후 · 팀 훈련",s.selections.get("afternoon"),menus(e.c),afternoon));slots.add(slot("night","야간 · 개인 훈련",s.selections.get("night"),menus(e.c),night));return slots;
    }
    private Map<String,String> option(String id,String label) { return Map.of("id",id,"label",label); }
    private List<Map<String,String>> menus(Config c) { return c.menus.stream().map(m->option(m.id(),m.label())).toList(); }
    private Map<String,Object> slot(String key,String label,String value,List<Map<String,String>> options,String fixed) { Map<String,Object> m=new LinkedHashMap<>();m.put("key",key);m.put("label",label);m.put("value",value);m.put("options",options);m.put("fixed",fixed);return m; }
}
