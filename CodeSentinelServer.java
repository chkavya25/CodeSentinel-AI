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
import javax.tools.*;

public class CodeSentinelServer {

    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    private static final String GEMINI_MODEL = "gemini-2.5-flash";
    private static final String[] GEMINI_FALLBACK_MODELS = { "gemini-2.5-flash-lite" };
    private static final String GEMINI_API_KEY = System.getenv("GEMINI_API_KEY");

    // Single pooled HTTP Client for connection reuse & keep-alive
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);

        server.createContext("/api/analyze", new MultiLangAnalyzeHandler());
        server.createContext("/api/chat", new GeminiChatHandler());
        server.createContext("/api/run", new CodeRunHandler());
        server.createContext("/", new StaticFileHandler());

        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        System.out.println(">>> CodeSentinel AI running at http://localhost:" + PORT);
        server.start();
    }

    private static void sendJsonResponse(HttpExchange exchange, int status, String json) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
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
    // FAST IN-MEMORY CODE ANALYZER
    // ============================================================
    static class MultiLangAnalyzeHandler implements HttpHandler {
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

            // Single pass compiler check using javax.tools (no slow process spawn)
            if ("java".equalsIgnoreCase(lang)) {
                int compilerErrors = collectJavaCompilerErrors(code, issueJson);
                bugs += compilerErrors;
            }

            for (int i = 0; i < lines.length; i++) {
                String t = lines[i].trim();
                int line = i + 1;

                if (t.matches(".*(=|\\+=|-=|\\*=|/=|==)\\s*$")) {
                    bugs++;
                    issueJson.add(issue("Incomplete Statement / Syntax Error", line, "danger", lang,
                            "The statement ends with an operator.", "Complete the expression."));
                }

                if (t.contains("shell=True") || t.contains("Runtime.getRuntime().exec(") || t.contains("ProcessBuilder(")) {
                    vulnerabilities++;
                    issueJson.add(issue("Command Execution Risk", line, "danger", lang,
                            "External command execution can become dangerous when input is not controlled.",
                            "Validate input and avoid shell execution with untrusted data."));
                }

                if (t.contains("eval(") || t.contains("innerHTML")) {
                    vulnerabilities++;
                    issueJson.add(issue("Dynamic Code / XSS Risk", line, "danger", lang,
                            "Dynamic evaluation or unsafe HTML insertion can execute attacker-controlled content.",
                            "Avoid eval() and prefer textContent or safe DOM APIs."));
                }

                if (t.contains("SELECT") && (t.contains(" + ") || t.contains("f\"") || t.contains("f'"))) {
                    vulnerabilities++;
                    issueJson.add(issue("SQL Injection Risk", line, "danger", lang,
                            "SQL is being built using string interpolation/concatenation.",
                            "Use parameterized queries or PreparedStatement."));
                }

                if (t.equals("catch (Exception e)") || t.equals("catch(Exception e)") || t.equals("except:")) {
                    smells++;
                    issueJson.add(issue("Overly Broad Exception Handling", line, "info", lang,
                            "A broad exception handler can hide unexpected failures.",
                            "Catch specific exceptions and log useful diagnostic information."));
                }
            }

            int opens = count(code, '{');
            int closes = count(code, '}');
            if (opens != closes) {
                bugs++;
                issueJson.add(issue("Mismatched Curly Braces", Math.max(1, lines.length), "danger", lang,
                        "Opening and closing brace counts do not match.",
                        "Check the blocks and ensure matching pairs."));
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

        private int collectJavaCompilerErrors(String code, List<String> issueJson) {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) return 0;

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            String className = findClassName(code);
            JavaSourceFromString fileObject = new JavaSourceFromString(className, code);

            JavaCompiler.CompilationTask task = compiler.getTask(null, null, diagnostics, List.of("-Xlint:none"), null, List.of(fileObject));
            task.call();

            int count = 0;
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    issueJson.add(issue("Java Compilation Error", (int) diagnostic.getLineNumber(), "danger", "java",
                            diagnostic.getMessage(Locale.ENGLISH), "Fix the syntax error."));
                    count++;
                }
            }
            return count;
        }

        private String findClassName(String code) {
            Matcher m = Pattern.compile("\\b(?:public\\s+)?class\\s+([A-Za-z_$][\\w$]*)").matcher(code);
            return m.find() ? m.group(1) : "Main";
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

    static class JavaSourceFromString extends SimpleJavaFileObject {
        final String code;
        JavaSourceFromString(String name, String code) {
            super(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }
        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
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
                    sendJsonResponse(exchange, 503, "{\"reply\":\"Gemini API Key missing.\"}");
                    return;
                }

                String reply = callGeminiAPI(userMessage, codeContext, lang);
                sendJsonResponse(exchange, 200, "{\"reply\":\"" + jsonEscape(reply) + "\"}");
            } catch (Exception e) {
                sendJsonResponse(exchange, 502, "{\"reply\":\"" + jsonEscape(e.getMessage()) + "\"}");
            }
        }

        private String callGeminiAPI(String prompt, String code, String lang) throws Exception {
            String fullPrompt = "You are CodeSentinel AI, a fast programming assistant. Language: " + lang
                    + "\n\nACTIVE CODE:\n" + code + "\n\nQUESTION:\n" + prompt;

            String requestBody = "{\"contents\":[{\"parts\":[{\"text\":\"" + jsonEscape(fullPrompt) + "\"}]}],"
                    + "\"generationConfig\":{\"maxOutputTokens\":500,\"temperature\":0.2}}";

            String[] models = new String[]{ GEMINI_MODEL, GEMINI_FALLBACK_MODELS[0] };
            for (String model : models) {
                try {
                    String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" + model
                            + ":generateContent?key=" + GEMINI_API_KEY;

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(endpoint))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                            .build();

                    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (response.statusCode() == 200) {
                        String text = extractFirstText(response.body());
                        if (!text.isBlank()) return text;
                    }
                } catch (Exception ignored) {}
            }
            throw new IOException("AI services currently busy. Please retry.");
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
                    case "python" -> runProcess(code, "python");
                    case "javascript" -> runProcess(code, "node");
                    case "cpp" -> runCpp(code);
                    default -> new RunResult(false, "Run not supported for " + lang);
                };
                sendJsonResponse(exchange, 200, "{\"success\":" + result.success + ",\"output\":\"" + jsonEscape(result.output) + "\"}");
            } catch (Exception e) {
                sendJsonResponse(exchange, 200, "{\"success\":false,\"output\":\"" + jsonEscape(e.getMessage()) + "\"}");
            }
        }

        private RunResult runJava(String code) throws Exception {
            Path dir = Files.createTempDirectory("codesentinel-java-");
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

                Process run = new ProcessBuilder("java", "-cp", dir.toString(), className).redirectErrorStream(true).start();
                String output = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!run.waitFor(5, TimeUnit.SECONDS)) {
                    run.destroyForcibly();
                    return new RunResult(false, "Execution timed out (5s).");
                }
                return new RunResult(run.exitValue() == 0, output.isBlank() ? "Finished with no output." : output);
            } finally {
                deleteTree(dir);
            }
        }

        private RunResult runProcess(String code, String executable) throws Exception {
            Path file = Files.createTempFile("sentinel-", executable.equals("python") ? ".py" : ".js");
            try {
                Files.writeString(file, code, StandardCharsets.UTF_8);
                Process p = new ProcessBuilder(executable, file.toString()).redirectErrorStream(true).start();
                String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return new RunResult(false, "Timed out (5s).");
                }
                return new RunResult(p.exitValue() == 0, output.isBlank() ? "Finished with no output." : output);
            } finally {
                Files.deleteIfExists(file);
            }
        }

        private RunResult runCpp(String code) throws Exception {
            Path dir = Files.createTempDirectory("sentinel-cpp-");
            try {
                Path source = dir.resolve("main.cpp");
                Path exe = dir.resolve("main.exe");
                Files.writeString(source, code, StandardCharsets.UTF_8);
                Process compile = new ProcessBuilder("g++", source.toString(), "-o", exe.toString()).redirectErrorStream(true).start();
                String compileOut = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!compile.waitFor(5, TimeUnit.SECONDS) || compile.exitValue() != 0) {
                    return new RunResult(false, "Compilation failed:\n" + compileOut);
                }
                Process run = new ProcessBuilder(exe.toString()).redirectErrorStream(true).start();
                String output = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!run.waitFor(5, TimeUnit.SECONDS)) {
                    run.destroyForcibly();
                    return new RunResult(false, "Timed out (5s).");
                }
                return new RunResult(run.exitValue() == 0, output.isBlank() ? "Finished with no output." : output);
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
