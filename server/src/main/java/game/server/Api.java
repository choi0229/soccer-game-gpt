package game.server;
import game.domain.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.*;
@RestController
@RequestMapping("/api")
public class Api {
    private final RunService runs;
    public Api(RunService runs) { this.runs=runs; }
    public record NewRun(long seed,String schoolId) {}
    public record Command(long expectedSeq,Action action) {}
    @GetMapping("/health") public Map<String,String> health() { return Map.of("status","ok"); }
    @GetMapping("/config") public Config config() { return runs.config(); }
    @PostMapping("/runs") public Map<String,Object> create(@RequestBody NewRun request) { return runs.create(request.seed,request.schoolId); }
    @GetMapping("/runs/{id}") public Map<String,Object> get(@PathVariable UUID id) { return runs.get(id); }
    @PostMapping("/runs/{id}/actions") public Map<String,Object> act(@PathVariable UUID id,@RequestBody Command command) { if(command.action==null) throw new IllegalArgumentException("action 필수");return runs.act(id,command.expectedSeq,command.action); }
    @GetMapping("/runs/{id}/replay") public Map<String,Object> replay(@PathVariable UUID id) { return runs.replay(id); }
    @ExceptionHandler({IllegalArgumentException.class,NoSuchElementException.class,RunService.Conflict.class})
    public ResponseEntity<Map<String,String>> error(RuntimeException ex) {
        var status=ex instanceof RunService.Conflict?HttpStatus.CONFLICT:ex instanceof NoSuchElementException?HttpStatus.NOT_FOUND:HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("message",ex.getMessage()));
    }
}
