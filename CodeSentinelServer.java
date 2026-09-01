import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CodeSentinelServer {

    private static final int PORT =
            Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

    private static final String GEMINI_MODEL = "gemini-2.5-flash";
    private static final String GEMINI_API_KEY = System.getenv("GEMINI_API_KEY");

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);

        // Pre-warm ping endpoint
        server.createContext("/api/ping", exchange -> {
            sendJsonResponse(exchange, 200, "{\"status\":\"ok\"}");
        });

        server.createContext("/api/analyze", new FastAnalyzeHandler());
        server.createContext("/api/chat", new GeminiChatHandler());
        server.createContext("/api/run", new CodeRunHandler());
        server.createContext("/", new StaticFileHandler());

        server.setExecutor(Executors.newCachedThreadPool());
        System.out.println(">>> Server running at http://0.0.0.0:" + PORT);
        server.start();
    }

    private static void sendJsonResponse(HttpExchange exchange, int status, String json) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "86400");
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\r' -> sb.append("\\r");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String extractJsonString(String json, String key) {
        String marker = "\"" + key + "\"";
        int p = json.indexOf(marker);
        if (p < 0) return "";
        p = json.indexOf(':', p + marker.length());
        if (p < 0) return "";
        p++;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        if (p >= json.length() || json.charAt(p) != '"') return "";
        p++;

        StringBuilder out = new StringBuilder();
        boolean escaped = false;
        for (; p < json.length(); p++) {
            char c = json.charAt(p);
            if (escaped) {
                switch (c) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    default -> out.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // ============================================================
    // STATIC FILE SERVER
    // ============================================================
    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String rawPath = exchange.getRequestURI().getPath();
            String path = "/".equals(rawPath) ? "/index.html" : rawPath;
            String clean = path.startsWith("/") ? path.substring(1) : path;

            Path file = Paths.get(clean).normalize();
            if (!Files.exists(file) || Files.isDirectory(file)) {
                file = Paths.get("..", clean).normalize();
            }

            if (!Files.exists(file) || Files.isDirectory(file)) {
                sendJsonResponse(exchange, 404, "{\"error\":\"File not found\"}");
                return;
            }

            String type = clean.endsWith(".html") ? "text/html; charset=UTF-8"
                    : clean.endsWith(".css") ? "text/css; charset=UTF-8"
                    : clean.endsWith(".js") ? "application/javascript; charset=UTF-8"
                    : "application/octet-stream";

            byte[] bytes = Files.readAllBytes(file);
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    // ============================================================
    // FAST CODE ANALYZER
    // ============================================================
    static class FastAnalyzeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 204, "");
                return;
            }

            String body = readBody(exchange);
            String code = extractJsonString(body, "code");
            String lang = extractJsonString(body, "language");
            if (lang.isEmpty()) lang = "java";

            List<String> issueJson = new ArrayList<>();
            int vulnerabilities = 0;
            int bugs = 0;
            int smells = 0;

            String[] lines = code.split("\\R", -1);

            for (int i = 0; i < lines.length; i++) {
                String t = lines[i].trim();
                int line = i + 1;

                if (t.matches(".*(=|\\+=|-=|\\*=|/=|==)\\s*$")) {
                    bugs++;
                    issueJson.add(issue("Syntax / Incomplete Statement", line, "danger", lang,
                            "Statement ends with a trailing operator.", "Complete the expression."));
                }

                if ("java".equalsIgnoreCase(lang) || "c".equalsIgnoreCase(lang) || "cpp".equalsIgnoreCase(lang)) {
                    if (!t.isEmpty() && !t.startsWith("//") && !t.startsWith("/*") && !t.startsWith("*")
                            && !t.endsWith(";") && !t.endsWith("{") && !t.endsWith("}") && !t.startsWith("#")
                            && !t.startsWith("public ") && !t.startsWith("class ") && !t.startsWith("if")
                            && !t.startsWith("for") && !t.startsWith("while") && !t.startsWith("else")) {
                        bugs++;
                        issueJson.add(issue("Missing Semicolon", line, "danger", lang,
                                "Statement is missing a terminating semicolon.", "Add ';' at the end of the line."));
                    }
                }

                if (t.contains("shell=True") || t.contains("Runtime.getRuntime().exec(") || t.contains("ProcessBuilder(")) {
                    vulnerabilities++;
                    issueJson.add(issue("Command Execution Risk", line, "danger", lang,
                            "Unsanitized process execution detected.", "Validate input and avoid raw shell execution."));
                }

                if (t.contains("eval(") || t.contains("innerHTML")) {
                    vulnerabilities++;
                    issueJson.add(issue("Dynamic Code / XSS Risk", line, "danger", lang,
                            "Dynamic evaluation can execute attacker payload.", "Use safe DOM APIs or textContent."));
                }

                if (t.contains("SELECT") && (t.contains(" + ") || t.contains("f\"") || t.contains("f'"))) {
                    vulnerabilities++;
                    issueJson.add(issue("SQL Injection Risk", line, "danger", lang,
                            "SQL string concatenation detected.", "Use Parameterized Queries / PreparedStatements."));
                }

                if (t.equals("catch (Exception e)") || t.equals("catch(Exception e)") || t.equals("except:")) {
                    smells++;
                    issueJson.add(issue("Generic Exception Catch", line, "info", lang,
                            "Broad catch blocks hide distinct system failures.", "Catch specific exception types."));
                }
            }

            int opens = count(code, '{');
            int closes = count(code, '}');
            if (opens != closes) {
                bugs++;
                issueJson.add(issue("Mismatched Braces", Math.max(1, lines.length), "danger", lang,
                        "Opening '{' and closing '}' count do not match.", "Fix mismatched block braces."));
            }

            int score = Math.max(0, 100 - vulnerabilities * 25 - bugs * 15 - smells * 8);

            String json = "{\"qualityScore\":" + score
                    + ",\"vulnerabilities\":" + vulnerabilities
                    + ",\"bugs\":" + bugs
                    + ",\"codeSmells\":" + smells
                    + ",\"linesOfCode\":" + lines.length
                    + ",\"issues\":[" + String.join(",", issueJson) + "]}";

            sendJsonResponse(exchange, 200, json);
        }

        private String issue(String title, int line, String severity, String lang, String desc, String fix) {
            return "{\"title\":\"" + jsonEscape(title) + "\",\"line\":" + line
                    + ",\"severity\":\"" + severity + "\",\"lang\":\"" + jsonEscape(lang.toUpperCase())
                    + "\",\"description\":\"" + jsonEscape(desc) + "\",\"fix\":\"" + jsonEscape(fix) + "\"}";
        }

        private int count(String s, char c) {
            int n = 0;
            for (char x : s.toCharArray()) if (x == c) n++;
            return n;
        }
    }

    // ============================================================
    // GEMINI CHAT
    // ============================================================
    static class GeminiChatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 204, "");
                return;
            }

            String payload = readBody(exchange);
            String userMessage = extractJsonString(payload, "message");
            String codeContext = extractJsonString(payload, "codeContext");
            String lang = extractJsonString(payload, "language");
            if (lang.isEmpty()) lang = "code";

            try {
                if (GEMINI_API_KEY == null || GEMINI_API_KEY.isBlank()) {
                    sendJsonResponse(exchange, 503, "{\"reply\":\"GEMINI_API_KEY is not set.\"}");
                    return;
                }

                String reply = callGeminiAPI(userMessage, codeContext, lang);
                sendJsonResponse(exchange, 200, "{\"reply\":\"" + jsonEscape(reply) + "\"}");
            } catch (Exception e) {
                sendJsonResponse(exchange, 500, "{\"reply\":\"" + jsonEscape(e.getMessage()) + "\"}");
            }
        }

        private String callGeminiAPI(String prompt, String code, String lang) throws Exception {
            String fullPrompt = "You are CodeSentinel AI. Keep answers concise and direct. Language: " + lang
                    + "\nCODE:\n" + code + "\nQUESTION:\n" + prompt;

            String requestBody = "{\"contents\":[{\"parts\":[{\"text\":\"" + jsonEscape(fullPrompt) + "\"}]}],"
                    + "\"generationConfig\":{\"maxOutputTokens\":400,\"temperature\":0.1}}";

            String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" + GEMINI_MODEL
                    + ":generateContent?key=" + GEMINI_API_KEY;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(12))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                String text = extractFirstText(response.body());
                if (!text.isBlank()) return text;
            }

            throw new IOException("AI call returned status " + response.statusCode());
        }

        private String extractFirstText(String json) {
            int p = json.indexOf("\"text\"");
            if (p < 0) return "";
            p = json.indexOf(':', p);
            if (p < 0) return "";
            p++;
            while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
            if (p >= json.length() || json.charAt(p) != '"') return "";
            p++;
            StringBuilder out = new StringBuilder();
            boolean escaped = false;
            for (; p < json.length(); p++) {
                char c = json.charAt(p);
                if (escaped) {
                    switch (c) {
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case '"' -> out.append('"');
                        case '\\' -> out.append('\\');
                        default -> out.append(c);
                    }
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    break;
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
    }

    // ============================================================
    // CODE RUNNER
    // ============================================================
    static class CodeRunHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 204, "");
                return;
            }

            String payload = readBody(exchange);
            String code = extractJsonString(payload, "code");
            String lang = extractJsonString(payload, "language").toLowerCase(Locale.ROOT);

            if (code.isBlank()) {
                sendJsonResponse(exchange, 400, "{\"success\":false,\"output\":\"No code supplied.\"}");
                return;
            }

            try {
                RunResult result = switch (lang) {
                    case "java" -> runJava(code);
                    case "python" -> runProcess(code, "python", ".py");
                    case "javascript" -> runProcess(code, "node", ".js");
                    case "cpp" -> runCpp(code);
                    default -> new RunResult(false, "Run not supported for " + lang);
                };
                sendJsonResponse(exchange, 200, "{\"success\":" + result.success + ",\"output\":\"" + jsonEscape(result.output) + "\"}");
            } catch (Exception e) {
                sendJsonResponse(exchange, 200, "{\"success\":false,\"output\":\"" + jsonEscape(e.getMessage()) + "\"}");
            }
        }

        private RunResult runJava(String code) throws Exception {
            Path dir = Files.createTempDirectory("cs-java-");
            try {
                Matcher m = Pattern.compile("\\b(?:public\\s+)?class\\s+([A-Za-z_$][\\w$]*)").matcher(code);
                String className = m.find() ? m.group(1) : "Main";
                Path javaFile = dir.resolve(className + ".java");
                Files.writeString(javaFile, code, StandardCharsets.UTF_8);

                Process compile = new ProcessBuilder("javac", javaFile.toString()).redirectErrorStream(true).start();
                String compileOut = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!compile.waitFor(5, TimeUnit.SECONDS) || compile.exitValue() != 0) {
                    return new RunResult(false, "Compilation failed:\n" + compileOut);
                }

                ProcessBuilder runBuilder = new ProcessBuilder("java", "-cp", dir.toString(), className);
                runBuilder.environment().remove("JAVA_TOOL_OPTIONS");
                Process run = runBuilder.redirectErrorStream(true).start();
                String output = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!run.waitFor(5, TimeUnit.SECONDS)) {
                    run.destroyForcibly();
                    return new RunResult(false, "Execution timed out (5s).");
                }
                return new RunResult(run.exitValue() == 0, output.isBlank() ? "Program finished with no output." : output);
            } finally {
                deleteTree(dir);
            }
        }

        private RunResult runProcess(String code, String exec, String ext) throws Exception {
            Path file = Files.createTempFile("cs-run-", ext);
            try {
                Files.writeString(file, code, StandardCharsets.UTF_8);
                Process p = new ProcessBuilder(exec, file.toString()).redirectErrorStream(true).start();
                String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return new RunResult(false, "Execution timed out (5s).");
                }
                return new RunResult(p.exitValue() == 0, output.isBlank() ? "Program finished with no output." : output);
            } finally {
                Files.deleteIfExists(file);
            }
        }

        private RunResult runCpp(String code) throws Exception {
            Path dir = Files.createTempDirectory("cs-cpp-");
            try {
                Path src = dir.resolve("main.cpp");
                Path exe = dir.resolve("main.exe");
                Files.writeString(src, code, StandardCharsets.UTF_8);
                Process compile = new ProcessBuilder("g++", src.toString(), "-O2", "-o", exe.toString()).redirectErrorStream(true).start();
                String compileOut = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!compile.waitFor(5, TimeUnit.SECONDS) || compile.exitValue() != 0) {
                    return new RunResult(false, "Compilation failed:\n" + compileOut);
                }
                Process run = new ProcessBuilder(exe.toString()).redirectErrorStream(true).start();
                String output = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!run.waitFor(5, TimeUnit.SECONDS)) {
                    run.destroyForcibly();
                    return new RunResult(false, "Execution timed out (5s).");
                }
                return new RunResult(run.exitValue() == 0, output.isBlank() ? "Program finished with no output." : output);
            } finally {
                deleteTree(dir);
            }
        }

        private void deleteTree(Path root) {
            try (var stream = Files.walk(root)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            } catch (IOException ignored) {}
        }

        record RunResult(boolean success, String output) {}
    }
}
