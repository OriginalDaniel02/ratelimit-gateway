package io.ratelimit.dev;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** Minimal upstream for demos, tests and benchmarks. Echoes method, path and request body length. */
public final class DummyService {

    private DummyService() {
    }

    public static HttpServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            byte[] out = ("{\"method\":\"" + ex.getRequestMethod() + "\",\"path\":\"" + ex.getRequestURI()
                    + "\",\"bodyBytes\":" + in.length + "}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9000;
        start(port);
        System.out.println("Dummy service listening on :" + port);
    }
}
