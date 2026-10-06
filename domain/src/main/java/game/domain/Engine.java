package game.domain;

import java.util.*;
import static game.domain.State.*;

/** Authoritative state machine shared by server, replay and headless simulation. */
public final class Engine {
    public final Config c;
    public Engine(Config config) { c=config; }
    public State create(long seed,String schoolId) {
        State s=new State(); s.seed=seed; s.configHash=c.fingerprint();
        s.schoolId=schoolId==null?c.schools.stream().filter(x->x.type().equals(c.s("defaultSchoolType"))).findFirst().orElseThrow().id():c.school(schoolId).id();
        Rng r=new Rng(seed);
        for(var stat:c.stats) {
            double v=r.integer(c.i(stat.trainable()?"initialStatLow":"initialPassiveLow"),c.i(stat.trainable()?"initialStatHigh":"initialPassiveHigh"));
            if(stat.primary()) v+=c.n("powerStartBonus");
            s.stats.put(stat.id(),clamp(v));
        }
        s.initialStats.putAll(s.stats);
        s.condition=c.i("conditionInitial"); s.stamina=maxStamina(s); s.academic=c.n("academicInitial");
        s.money=c.n("moneyInitial"); s.reputation=c.n("reputationInitial");
        for(String axis:List.of("coach","teammate","family","school")) s.affinity.put(axis,c.n(axis+"Initial"));
        for(String slot:List.of("dawn","class","morning","afternoon","night")) s.selections.put(slot,c.s("initial"+Character.toUpperCase(slot.charAt(0))+slot.substring(1)));
        for(var m:c.menus) s.trainingCounts.put(m.id(),0);
        for(var school:c.schools) {
            s.defenders.put(school.id(),r.chance(c.n("defenderFighterChance"))?"fighterResponse":"commanderResponse");
            s.standings.add(new Standing(school.id()));
        }
        s.cupAlive.addAll(c.schools.stream().map(Config.School::id).toList()); r.shuffle(s.cupAlive);
        buildLeague(s); s.rngState=r.state;
        s.weeklyStamina=new double[c.i("weeks")]; s.weeklyDays=new int[c.i("weeks")];
        s.report.add("고1 파워형 공격수의 첫 시즌이 시작되었습니다."); return s;
    }
    private void buildLeague(State s) {
        for(int region:c.schools.stream().map(Config.School::region).distinct().toList()) {
            List<String> rotation=new ArrayList<>(c.schools.stream().filter(x->x.region()==region).map(Config.School::id).toList());
            int rounds=rotation.size()-1;
            for(int round=0;round<rounds;round++) {
                for(int i=0;i<rotation.size()/2;i++) {
                    String a=rotation.get(i),b=rotation.get(rotation.size()-1-i);
                    boolean swap=(round+i)%2==1;
                    String home=swap?b:a,away=swap?a:b;
                    s.leagueFixtures.add(new Fixture(round+1,home,away));
                    s.leagueFixtures.add(new Fixture(round+1+rounds,away,home));
                }
                rotation.add(1,rotation.remove(rotation.size()-1));
            }
        }
    }
    public int week(State s) { return s.day/c.i("daysPerWeek")+1; }
    public int weekday(State s) { return s.day%c.i("daysPerWeek"); }
    public int month(State s) { return (c.i("startMonth")-1+(week(s)-1)/c.i("weeksPerMonth"))%12+1; }
    public int monthWeek(State s) { return (week(s)-1)%c.i("weeksPerMonth")+1; }
    public boolean vacation(State s) { return c.calendar.vacationWeeks.contains(week(s)); }
    public boolean injured(State s) { return s.day<s.injuryUntilDay; }
    public double maxStamina(State s) { return c.n("staminaBase")+(s.stats.get("fitness")-c.n("staminaReference"))/c.n("staminaDivisor"); }
    public double averageStats(State s) { return c.stats.stream().filter(Config.Stat::trainable).mapToDouble(x->s.stats.get(x.id())).average().orElseThrow(); }
    public boolean cupDay(State s) { return c.calendar.cupDays.contains(s.day) && s.cupAlive.contains(s.schoolId); }
    public boolean matchDay(State s) { return cupDay(s) || weekday(s)==5&&c.calendar.leagueWeeks.contains(week(s)); }
    public String date(State s) { return month(s)+"월 "+monthWeek(s)+"주 "+c.calendar.weekdays.get(weekday(s)); }
    public void apply(State s,Action action) {
        if(s.completed) throw new IllegalArgumentException("시즌이 종료되었습니다.");
        if("event".equals(action.kind())) {
            if(s.pendingEvent==null) throw new IllegalArgumentException("대기 중인 이벤트가 없습니다.");
            var event=c.event(s.pendingEvent);
            if(action.choice()==null || action.choice()<0 || action.choice()>=event.choices().size()) throw new IllegalArgumentException("선택지 범위 오류");
            chooseEvent(s,event,action.choice());
        } else if("day".equals(action.kind())) {
            if(s.pendingEvent!=null || s.activeDay) throw new IllegalArgumentException("이벤트를 먼저 선택해 주세요.");
            validateSelections(s,action);
            if(action.changes()!=null) s.selections.putAll(action.changes());
            s.sundayAction=action.sundayAction(); s.activeDay=true; s.stage=0; s.excludedToday=false;
            s.report.clear(); s.todayMatches.clear(); s.report.add(date(s)+" · "+(vacation(s)?"방학":"학기 중"));
        } else throw new IllegalArgumentException("알 수 없는 요청 종류");
        Rng r=new Rng(s.rngState); resume(s,r); s.rngState=r.state; s.seq++;
    }
    private void validateSelections(State s,Action a) {
        if(a.changes()!=null) for(var e:a.changes().entrySet()) {
            switch(e.getKey()) {
                case "dawn" -> { if(!List.of("exercise","sleep").contains(e.getValue())) throw new IllegalArgumentException("새벽 선택 오류"); }
                case "class" -> { if(!List.of("focus","nap","teacher","friends").contains(e.getValue())) throw new IllegalArgumentException("수업 선택 오류"); }
                case "morning","afternoon","night" -> c.menu(e.getValue());
                default -> throw new IllegalArgumentException("알 수 없는 시간대");
            }
        }
        if(injured(s) && "exercise".equals(a.changes()==null? s.selections.get("dawn"):a.changes().getOrDefault("dawn",s.selections.get("dawn"))))
            throw new IllegalArgumentException("부상 중에는 새벽 개인 운동을 할 수 없습니다. 더 자기를 선택해 주세요.");
        if(weekday(s)==6 && !List.of("rest","job","coach","teammate","family").contains(a.sundayAction()==null?"":a.sundayAction()))
            throw new IllegalArgumentException("일요일 자유 행동을 선택해 주세요.");
    }
    private void resume(State s,Rng r) {
        while(s.pendingEvent==null && s.activeDay) {
            int dow=weekday(s);
            if(dow==6) {
                if(s.stage++==0) sunday(s,r);
                else if(s.stage==2) { if(r.chance(c.n("sundayEventChance"))) triggerEvent(s,r,null); }
                else finishDay(s);
            } else if(dow==5) {
                if(s.stage++==0) {
                    if(c.calendar.cupDays.contains(s.day)) playCupRound(s,r);
                    else if(c.calendar.leagueWeeks.contains(week(s))) playLeagueRound(s,r);
                    else s.report.add("경기 없는 토요일. 휴식을 취했습니다.");
                } else finishDay(s);
            } else {
                switch(s.stage++) {
                    case 0 -> dawn(s);
                    case 1 -> { if(vacation(s)) training(s,r,"morning",true); else classroom(s,r); }
                    case 2 -> {
                        if(c.calendar.cupDays.contains(s.day)) {
                            boolean playing=cupDay(s); playCupRound(s,r);
                            if(playing) s.stage=4; else afternoon(s,r);
                        } else afternoon(s,r);
                    }
                    case 3 -> training(s,r,"night",false);
                    default -> finishDay(s);
                }
            }
        }
    }
    private void dawn(State s) {
        if("exercise".equals(s.selections.get("dawn")) && !injured(s)) {
            stat(s,"fitness",c.n("dawnFitness")); stamina(s,-c.n("dawnCost")); s.report.add("새벽 개인 운동 · 기초체력 +"+c.n("dawnFitness"));
        } else { stamina(s,c.n("sleepRecovery")); s.report.add("더 자기 · 체력을 회복했습니다."); }
    }
    private void classroom(State s,Rng r) {
        switch(s.selections.get("class")) {
            case "focus" -> { s.academic=clamp(s.academic+c.n("focusAcademic")*(c.n("schoolMultiplierBase")+s.affinity.get("school")/c.n("schoolMultiplierDivisor"))); stamina(s,-c.n("focusCost")); s.report.add("수업 집중 · 학업 성취가 올랐습니다."); }
            case "nap" -> { stamina(s,c.n("napRecovery")); s.academic=clamp(s.academic+c.n("napAcademic")); s.report.add("수업 중 졸기 · 체력 회복, 학업 성취 감소"); }
            case "teacher" -> { affinity(s,"school",c.n("talkAffinity")); s.academic=clamp(s.academic+c.n("talkAcademic")); s.report.add("선생님과 이야기했습니다."); if(r.chance(c.n("schoolEventChance"))) triggerEvent(s,r,"school"); }
            case "friends" -> { affinity(s,"school",c.n("friendAffinity")); s.report.add("친구와 어울렸습니다."); if(r.chance(c.n("schoolEventChance"))) triggerEvent(s,r,"school"); }
        }
    }
    private void afternoon(State s,Rng r) {
        if(s.supplementRequired && c.calendar.supplementWeeks.contains(week(s))) {
            s.academic=clamp(s.academic+c.n("supplementGrowth")); s.supplementDays++; s.report.add("오후 보충수업 · 학업 성취 +"+c.n("supplementGrowth"));
        } else training(s,r,"afternoon",true);
    }
    public double growth(State s,String id,double base,double menuMultiplier) {
        double value=s.stats.get(id);
        double decay=value<c.n("decayThreshold1")?c.n("decay1"):value<c.n("decayThreshold2")?c.n("decay2"):c.n("decay3");
        return base*menuMultiplier*(c.stat(id).primary()?c.n("powerGrowth"):1)*c.conditions.get(s.condition).training()*decay;
    }
    private void training(State s,Rng r,String slot,boolean team) {
        if(injured(s)) { s.report.add(slotLabel(slot)+" 재활 · 성장 및 체력 소모 없음"); return; }
        if(s.excludedToday || s.stamina<c.n("excludeThreshold")) {
            if(!s.excludedToday) { s.excludedToday=true; s.exclusions++; affinity(s,"coach",-c.n("excludeCoachCost")); }
            s.report.add(slotLabel(slot)+" 훈련 제외 · 체력 부족"); return;
        }
        var menu=c.menu(s.selections.get(slot));
        boolean injuryRoll=s.stamina<c.n("injuryThreshold");
        if(injuryRoll && r.chance(c.n("injuryChance"))) {
            int duration=r.integer(c.i("injuryMinWeeks"),c.i("injuryMaxWeeks"));
            s.injuryUntilDay=s.day+duration*c.i("daysPerWeek"); s.injuries++;
            s.report.add(slotLabel(slot)+" 훈련 중 부상 · "+duration+"주 재활"); return;
        }
        for(String id:menu.stats()) stat(s,id,growth(s,id,c.n(team?"teamGrowth":"personalGrowth"),menu.growthMultiplier()));
        s.trainingCounts.merge(menu.id(),1,Integer::sum); stamina(s,-c.n(team?"teamCost":"personalCost"));
        s.report.add(slotLabel(slot)+" "+menu.label()+" 훈련 완료");
    }
    private String slotLabel(String slot) { return switch(slot) { case "morning"->"오전"; case "afternoon"->"오후"; default->"야간"; }; }
    private void sunday(State s,Rng r) {
        switch(s.sundayAction) {
            case "rest" -> { stamina(s,c.n("restRecovery")); condition(s,c.i("restCondition")); s.report.add("일요일 휴식 · 체력과 컨디션 회복"); }
            case "job" -> { s.money+=c.n("jobMoney"); stamina(s,-c.n("jobCost")); s.report.add("아르바이트 · "+(int)c.n("jobMoney")+"원"); }
            default -> { affinity(s,s.sundayAction,c.n("meetAffinity")); s.report.add("사람 만나기 · 관계도 상승"); triggerEvent(s,r,s.sundayAction); }
        }
    }
    private void finishDay(State s) {
        // Saturday condition is evaluated before nightly recovery.
        if(weekday(s)==5 && s.stamina<c.n("injuryThreshold")) condition(s,c.i("lowStaminaCondition"));
        stamina(s,c.n("nightRecovery")+(weekday(s)==6?c.n("sundayRecovery"):0));
        if(weekday(s)==6 && c.calendar.academicReviewWeeks.contains(week(s))) {
            boolean failed=s.academic<c.n("academicThreshold");
            if(week(s)<Collections.min(c.calendar.supplementWeeks)) s.supplementRequired=failed;
            else s.winterSupplementRequired=failed;
            s.report.add("학기 성적 확인 · "+(failed?"보충수업 대상":"기준 통과"));
        }
        int w=week(s)-1; s.weeklyStamina[w]+=s.stamina; s.weeklyDays[w]++;
        s.day++; s.activeDay=false; s.stage=0; s.completed=s.day>=c.totalDays();
        if(injured(s) && "exercise".equals(s.selections.get("dawn"))) s.selections.put("dawn","sleep");
        if(s.completed) s.report.add(c.i("weeks")+"주를 마쳤습니다. 시즌 기록을 확인하세요.");
    }
    public void stat(State s,String id,double delta) { s.stats.put(id,clamp(s.stats.get(id)+delta)); s.stamina=Math.min(s.stamina,maxStamina(s)); }
    public void stamina(State s,double delta) { s.stamina=Math.max(0,Math.min(maxStamina(s),s.stamina+delta)); }
    public void affinity(State s,String axis,double delta) { s.affinity.put(axis,clamp(s.affinity.get(axis)+delta)); }
    public void condition(State s,int delta) { s.condition=Math.max(0,Math.min(c.conditions.size()-1,s.condition+delta)); }
    private double clamp(double value) { return Math.max(c.n("statMin"),Math.min(c.n("statMax"),value)); }
    public int poisson(Rng r,double lambda) {
        double limit=Math.exp(-lambda), product=1; int k=0;
        do { k++; product*=r.next(); } while(product>limit);
        return k-1;
    }
    public double expectedGoals(double own,double opponent) {
        return Math.max(c.n("poissonMin"),Math.min(c.n("poissonMax"),c.n("poissonBase")+(own-opponent)*c.n("poissonSlope")));
    }
    public String role(State s) {
        if(injured(s)) return "bench";
        double score=s.affinity.get("coach")*c.n("startCoachWeight")+averageStats(s)*c.n("startStatWeight");
        double power=c.school(s.schoolId).power();
        return score>=power-c.n("starterOffset")?"starter":score>=power-c.n("subOffset")?"sub":"bench";
    }
    public double passiveBonus(State s,String opponent,int minute,int ownGoals,int opponentGoals,Boolean previousSuccess) {
        double sum=passive(s,s.defenders.get(opponent));
        if(Boolean.TRUE.equals(previousSuccess)) sum+=Math.max(0,passive(s,"momentum"));
        if(minute>=c.i("clutchMinute") && Math.abs(ownGoals-opponentGoals)<=c.i("clutchScoreGap")) sum+=passive(s,"clutch");
        if(Boolean.FALSE.equals(previousSuccess) || ownGoals<opponentGoals) sum+=passive(s,"mental");
        return Math.max(-c.n("passiveLimit"),Math.min(c.n("passiveLimit"),sum));
    }
    private double passive(State s,String id) {
        return Math.max(-c.n("passiveLimit"),Math.min(c.n("passiveLimit"),(s.stats.get(id)-c.n("passiveReference"))/c.n("passiveDivisor")));
    }
    public double successChance(State s,Config.Scene scene,String opponent,int minute,int ownGoals,int opponentGoals,Boolean previous,double boost) {
        double stat=scene.stats().stream().mapToDouble(x->s.stats.get(x)).average().orElseThrow();
        double value=c.n(scene.effect().equals("goal")?"goalBaseChance":"otherBaseChance")
            +(stat-c.school(opponent).power())*c.n("statChanceSlope")+c.conditions.get(s.condition).match()
            +passiveBonus(s,opponent,minute,ownGoals,opponentGoals,previous)+boost;
        return Math.max(c.n("chanceMin"),Math.min(c.n("chanceMax"),value));
    }
    public Match match(State s,Rng r,String home,String away,String competition,boolean knockout) {
        Match m=new Match(); m.day=s.day; m.home=home; m.away=away; m.competition=competition;
        m.homeGoals=poisson(r,expectedGoals(c.school(home).power(),c.school(away).power()));
        m.awayGoals=poisson(r,expectedGoals(c.school(away).power(),c.school(home).power()));
        boolean player=home.equals(s.schoolId)||away.equals(s.schoolId), atHome=home.equals(s.schoolId);
        if(player) {
            m.role=role(s); s.lastMatchRole=m.role;
            int count=switch(m.role) {
                case "starter"->r.integer(c.i("starterScenesMin"),c.i("starterScenesMax"));
                case "sub"->r.integer(c.i("subScenesMin"),c.i("subScenesMax")); default->0;
            };
            if(!m.role.equals("bench")) {
                String opponent=atHome?away:home;
                var queue=new ArrayList<Config.Scene>(); var minutes=new ArrayList<Integer>();
                for(int i=0;i<count;i++) { queue.add(c.scenes.get(r.integer(0,c.scenes.size()-1))); minutes.add((i+1)*c.i("matchMinutes")/(count+1)); }
                Boolean previous=null; boolean boosted=false; double rating=c.n("ratingBase");
                for(int i=0;i<queue.size();i++) {
                    var scene=queue.get(i); int minute=minutes.get(i);
                    int own=atHome?m.homeGoals:m.awayGoals,other=atHome?m.awayGoals:m.homeGoals;
                    boolean goalScene=scene.effect().equals("goal");
                    double chance=successChance(s,scene,opponent,minute,own,other,previous,goalScene&&boosted?c.n("dribbleBonus"):0);
                    if(goalScene) boosted=false;
                    boolean success=r.chance(chance/100); String outcome="실패";
                    if(success) {
                        switch(scene.effect()) {
                            case "goal" -> { m.goals++; addGoal(m,atHome); rating+=c.n("ratingGoal"); outcome="골!"; }
                            case "assist" -> { m.assists++; addGoal(m,atHome); rating+=c.n("ratingAssist"); outcome="도움!"; }
                            case "extra" -> { queue.add(i+1,c.scene("oneOnOne")); minutes.add(i+1,Math.min(c.i("matchMinutes"),minute+1)); rating+=c.n("ratingSuccess"); outcome="침투 성공 · 1대1 장면 추가"; }
                            case "boost" -> { boosted=true; rating+=c.n("ratingSuccess"); outcome="돌파 성공 · 다음 슈팅 +"+c.i("dribbleBonus"); }
                            case "press" -> { boolean scored=r.chance(c.n("pressGoalChance")); if(scored) addGoal(m,atHome); rating+=c.n("ratingSuccess"); outcome=scored?"압박 성공 · 팀 득점!":"압박 성공"; }
                            default -> throw new IllegalStateException("장면 효과 오류");
                        }
                    } else rating+=c.n("ratingFail");
                    m.moments.add(new Moment(minute,scene.id(),minute+"분 · "+scene.label()+" → "+outcome,chance,success,outcome)); previous=success;
                }
                m.rating=Math.max(c.n("ratingMin"),Math.min(c.n("ratingMax"),rating));
                stamina(s,-c.n(m.role.equals("starter")?"starterCost":"subCost"));
                List<String> growth=List.of("momentum","clutch","mental",s.defenders.get(opponent));
                stat(s,growth.get(r.integer(0,growth.size()-1)),c.n("passiveGrowth"));
                if(m.rating>=c.n("goodRating")) affinity(s,"coach",c.n("ratingCoachGain"));
                else if(m.rating<c.n("badRating")) affinity(s,"coach",c.n("ratingCoachLoss"));
                double reward=m.rating>=c.n("greatRating")?c.n("reputationGreat"):m.rating>=c.n("goodRating")?c.n("reputationGood"):0;
                s.reputation+=(reward+m.goals*c.n("reputationGoal")+m.assists*c.n("reputationAssist"))*c.n("reputationYearMultiplier");
            }
        }
        if(m.homeGoals!=m.awayGoals) m.winner=m.homeGoals>m.awayGoals?home:away;
        else if(knockout) { boolean won=r.chance(c.n("penaltyChance")); m.penaltiesHome=won?1:0; m.penaltiesAway=won?0:1; m.winner=won?home:away; }
        if(player) { s.matches.add(m); s.todayMatches.add(m); s.report.add(competition+" · "+c.school(home).name()+" "+m.homeGoals+" : "+m.awayGoals+" "+c.school(away).name()+(knockout&&m.homeGoals==m.awayGoals?" (승부차기)":"")); }
        s.competitionMatches.add(m); return m;
    }
    private void addGoal(Match m,boolean atHome) { if(atHome) m.homeGoals++; else m.awayGoals++; }
    private void playLeagueRound(State s,Rng r) {
        int round=c.calendar.leagueWeeks.indexOf(week(s))+1;
        for(var f:s.leagueFixtures) if(f.round()==round) {
            Match m=match(s,r,f.home(),f.away(),"주말리그 "+round+"R",false);
            updateStanding(s,m.home,m.homeGoals,m.awayGoals); updateStanding(s,m.away,m.awayGoals,m.homeGoals);
        }
    }
    private void updateStanding(State s,String school,int scored,int conceded) {
        Standing row=s.standings.stream().filter(x->x.schoolId.equals(school)).findFirst().orElseThrow();
        row.played++; row.goalsFor+=scored; row.goalsAgainst+=conceded;
        if(scored>conceded) { row.won++; row.points+=c.i("leagueWinPoints"); }
        else if(scored==conceded) { row.drawn++; row.points+=c.i("leagueDrawPoints"); }
        else { row.lost++; row.points+=c.i("leagueLossPoints"); }
    }
    public List<Standing> standings(State s) {
        int region=c.school(s.schoolId).region();
        return s.standings.stream().filter(x->c.school(x.schoolId).region()==region)
            .sorted(Comparator.comparingInt((Standing x)->x.points).reversed()
              .thenComparing(Comparator.comparingInt(Standing::goalDifference).reversed())
              .thenComparing(Comparator.comparingInt((Standing x)->x.goalsFor).reversed())
              .thenComparing(x->x.schoolId)).toList();
    }
    public int leagueRank(State s) { var rows=standings(s); for(int i=0;i<rows.size();i++) if(rows.get(i).schoolId.equals(s.schoolId)) return i+1; throw new IllegalStateException(); }
    private void playCupRound(State s,Rng r) {
        var survivors=new ArrayList<String>();
        for(int i=0;i<s.cupAlive.size();i+=2) {
            Match m=match(s,r,s.cupAlive.get(i),s.cupAlive.get(i+1),"춘계배 "+(c.calendar.cupDays.indexOf(s.day)+1)+"R",true);
            survivors.add(m.winner);
        }
        s.cupAlive=survivors;
        if(survivors.contains(s.schoolId)) {
            String reward=switch(survivors.size()) { case 8->"cupQuarterReputation"; case 4->"cupSemiReputation"; case 1->"cupWinnerReputation"; default->null; };
            if(reward!=null) s.reputation+=c.n(reward)*c.n("reputationYearMultiplier");
        }
    }
    private void triggerEvent(State s,Rng r,String axis) {
        var candidates=c.events.stream().filter(event->axis==null||event.axis().equals(axis))
            .filter(event->eligible(s,event)).toList();
        if(candidates.isEmpty()) return;
        var event=candidates.get(r.integer(0,candidates.size()-1));
        s.pendingEvent=event.id(); s.eventLastWeek.put(event.id(),week(s));
        s.report.add("이벤트 · "+event.title()+" · 선택 대기");
    }
    public boolean eligible(State s,Config.Event event) {
        Integer last=s.eventLastWeek.get(event.id());
        if(last!=null && (event.once() || week(s)-last<c.i("eventCooldownWeeks"))) return false;
        for(var entry:event.trigger().entrySet()) {
            String key=entry.getKey();Object value=entry.getValue();
            if(key.equals("minWeek") && week(s)<((Number)value).intValue()) return false;
            if(key.equals("maxWeek") && week(s)>((Number)value).intValue()) return false;
            if(key.equals("lastMatchRole") && !s.lastMatchRole.equals(value)) return false;
            if(key.equals("injured") && injured(s)!=(Boolean)value) return false;
            if(key.equals("requiredFlags") && !s.flags.containsAll((List<?>)value)) return false;
            if(key.equals("excludedFlags") && ((List<?>)value).stream().anyMatch(s.flags::contains)) return false;
            if(key.endsWith("Min") || key.endsWith("Max")) {
                String resource=key.substring(0,key.length()-3);
                if(resource.equals("stat")) {
                    for(var stat:((Map<?,?>)value).entrySet()) if(!threshold(s.stats.get(stat.getKey()),((Number)stat.getValue()).doubleValue(),key.endsWith("Min"))) return false;
                } else if(!threshold(resource(s,resource),((Number)value).doubleValue(),key.endsWith("Min"))) return false;
            }
        }
        return true;
    }
    private boolean threshold(double actual,double boundary,boolean minimum) { return minimum?actual>=boundary:actual<=boundary; }
    private double resource(State s,String key) {
        return switch(key) {
            case "academic"->s.academic;case "stamina"->s.stamina;case "condition"->s.condition;
            case "reputation"->s.reputation;case "money"->s.money;
            case "coachAffinity"->s.affinity.get("coach");case "teammateAffinity"->s.affinity.get("teammate");
            case "familyAffinity"->s.affinity.get("family");case "schoolAffinity"->s.affinity.get("school");
            default->throw new IllegalArgumentException("알 수 없는 이벤트 조건: "+key);
        };
    }
    @SuppressWarnings("unchecked")
    private void chooseEvent(State s,Config.Event event,int index) {
        var choice=event.choices().get(index);
        for(var entry:choice.effects().entrySet()) {
            String key=entry.getKey();Object value=entry.getValue();
            if(key.equals("stat")) { for(var change:((Map<String,Number>)value).entrySet()) stat(s,change.getKey(),change.getValue().doubleValue());continue; }
            double delta=((Number)value).doubleValue();
            switch(key) {
                case "stamina"->stamina(s,delta);case "condition"->condition(s,(int)delta);
                case "academic"->s.academic=clamp(s.academic+delta);case "money"->s.money=Math.max(0,s.money+delta);
                case "reputation"->s.reputation=Math.max(0,s.reputation+delta);
                case "coachAffinity","teammateAffinity","familyAffinity","schoolAffinity"->affinity(s,key.replace("Affinity",""),delta);
                default->throw new IllegalArgumentException("알 수 없는 이벤트 효과: "+key);
            }
        }
        s.flags.addAll(event.setFlags());s.report.add("선택 · "+choice.text());s.pendingEvent=null;
    }
}
