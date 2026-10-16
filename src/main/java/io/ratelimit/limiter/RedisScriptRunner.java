package io.ratelimit.limiter;

import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Runs Lua scripts with EVALSHA, loading the script once if Redis has not seen it. */
public class RedisScriptRunner {

    private final Supplier<StatefulRedisConnection<String, String>> connection;

    public RedisScriptRunner(Supplier<StatefulRedisConnection<String, String>> connection) {
        this.connection = connection;
    }

    public List<Long> run(LuaScript script, String key, String... args) {
        var commands = connection.get().sync();
        String[] keys = {key};
        List<Object> raw;
        try {
            raw = commands.evalsha(script.sha1(), ScriptOutputType.MULTI, keys, args);
        } catch (RedisNoScriptException e) {
            commands.scriptLoad(script.source());
            raw = commands.evalsha(script.sha1(), ScriptOutputType.MULTI, keys, args);
        }
        List<Long> result = new ArrayList<>(raw.size());
        for (Object o : raw) result.add((Long) o);
        return result;
    }

    static Decision toDecision(List<Long> r, long limit) {
        return new Decision(r.get(0) == 1L, limit, r.get(1), r.get(2), r.get(3));
    }
}
