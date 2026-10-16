package io.ratelimit.limiter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** A Lua script from the classpath plus its SHA1, so it can be called with EVALSHA. */
public final class LuaScript {

    public static final LuaScript TOKEN_BUCKET = load("token_bucket");
    public static final LuaScript FIXED_WINDOW = load("fixed_window");
    public static final LuaScript SLIDING_LOG = load("sliding_log");
    public static final LuaScript SLIDING_COUNTER = load("sliding_counter");

    private final String source;
    private final String sha1;

    private LuaScript(String source) {
        this.source = source;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(source.getBytes(StandardCharsets.UTF_8));
            this.sha1 = HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static LuaScript load(String name) {
        try (InputStream in = LuaScript.class.getResourceAsStream("/lua/" + name + ".lua")) {
            if (in == null) throw new IllegalStateException("Missing Lua script: " + name);
            return new LuaScript(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read Lua script " + name, e);
        }
    }

    public String source() {
        return source;
    }

    public String sha1() {
        return sha1;
    }
}
