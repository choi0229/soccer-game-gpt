package game.domain;
import java.util.*;
public record Action(String kind, Map<String,String> changes, String sundayAction, Integer choice) {
    public static Action day(Map<String,String> changes,String sunday) { return new Action("day",changes,sunday,null); }
    public static Action event(int choice) { return new Action("event",Map.of(),null,choice); }
}
