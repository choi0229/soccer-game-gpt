package game.domain;

/** Optional, read-only observation port. Only immutable value snapshots cross this boundary. */
public interface EngineObserver {
    record Training(int day, int week, boolean vacation, String slot, String menu,
                    double stamina, double maxStamina, String outcome) {}
    record Change(int day, int week, String source, double before, double requested, double after) {}
    record Appearance(int day, int week, String competition, double coach, double trainable,
                      double primary, double score, double starterMargin, double substituteMargin, String role) {}
    record Scene(String role, String id, boolean goal, boolean success, double chance,
                 double ability, double opponentPower, double condition, double passive, double boost) {}
    record Score(String competition, String role, int basePoissonGoals, int playerGoals,
                 int assistAddedGoals, int pressingAddedGoals, int finalGoals,
                 int opponentBaseGoals, int opponentFinalGoals, Double rating) {}
    record Day(int day, int week, boolean injured, double academic, boolean review,
               boolean failed, double primary, double trainable, double coach) {}
    default void created(long seed, double academic, double coach) {}
    default void policySelection(int day, String slot, String menu) {}
    default void training(Training value) {}
    default void injuryRoll() {}
    default void injury(int durationDays) {}
    default void classroom(String attitude) {}
    default void academic(Change value) {}
    default void coach(Change value) {}
    default void appearance(Appearance value) {}
    default void baseScenes(String role, int count) {}
    default void addedScene() {}
    default void scene(Scene value) {}
    default void score(Score value) {}
    default void day(Day value) {}
    default void finished(long gameRngState, long choiceRngState) {}
    default boolean traceActions() { return false; }
    default void actionBoundary(String actionJson, String stateJson, long choiceRngState) {}
}
