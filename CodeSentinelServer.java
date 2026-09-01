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

    private static final int PORT =
            Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

    private static final String GEMINI_MODEL = "gemini-2.5-flash";
    private static final String[] GEMINI_FALLBACK_MODELS = {
            "gemini-2.5-flash-lite"
    };

    private static final String GEMINI_API_KEY =
            System.getenv("GEMINI_API_KEY");

    // Reusable pooled HTTP Client with HTTP/2 and connection reuse
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    public static void main(String[] args) throws IOException {
        if (GEMINI_API_KEY == null || GEMINI_API_KEY.isBlank()) {
            System.out.println("WARNING: GEMINI_API_KEY is not configured.");
            System.out.println("Set it with:");
            System.out.println("setx GEMINI_API_KEY \"YOUR_KEY\"");
            System.out.println("Then restart VS Code.");
        } else {
            System.out.println(">>> Gemini API key loaded successfully.");
        }

        HttpServer server =
                HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);

        server.createContext("/api/analyze",
                new MultiLangAnalyzeHandler());

        server.createContext("/api/chat",
                new GeminiChatHandler());

        server.createContext("/api/run",
                new CodeRunHandler());

        server.createContext("/",
                new StaticFileHandler());

        server.setExecutor(Executors.newCachedThreadPool());

        System.out.println(
                ">>> CodeSentinel AI running at http://localhost:" + PORT);

        server.start();
    }

    // ============================================================
    // COMMON RESPONSE
    // ============================================================

    private static void sendJsonResponse(
            HttpExchange exchange,
            int status,
            String json) throws IOException {

        exchange.getResponseHeaders()
                .set("Access-Control-Allow-Origin", "*");

        exchange.getResponseHeaders()
                .set("Access-Control-Allow-Methods",
                        "GET, POST, OPTIONS");

        exchange.getResponseHeaders()
                .set("Access-Control-Allow-Headers",
                        "Content-Type, Authorization");

        exchange.getResponseHeaders()
                .set("Content-Type",
                        "application/json; charset=UTF-8");

        if ("OPTIONS".equalsIgnoreCase(
                exchange.getRequestMethod())) {

            exchange.sendResponseHeaders(204, -1);
            return;
        }

        byte[] bytes =
                json.getBytes(StandardCharsets.UTF_8);

        exchange.sendResponseHeaders(
                status,
                bytes.length);

        try (OutputStream os =
                     exchange.getResponseBody()) {

            os.write(bytes);
        }
    }

    private static String readBody(
            HttpExchange exchange) throws IOException {

        return new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8);
    }

    // ============================================================
    // JSON ESCAPE
    // ============================================================

    private static String jsonEscape(String s) {
        if (s == null)
            return "";

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

    // ============================================================
    // SIMPLE JSON STRING EXTRACTOR
    // ============================================================

    private static String extractJsonString(
            String json,
            String key) {

        String marker = "\"" + key + "\"";

        int p = json.indexOf(marker);

        if (p < 0)
            return "";

        p = json.indexOf(
                ':',
                p + marker.length());

        if (p < 0)
            return "";

        p++;

        while (p < json.length()
                && Character.isWhitespace(
                json.charAt(p))) {

            p++;
        }

        if (p >= json.length()
                || json.charAt(p) != '"') {

            return "";
        }

        p++;

        StringBuilder out =
                new StringBuilder();

        boolean escaped = false;

        for (; p < json.length(); p++) {

            char c = json.charAt(p);

            if (escaped) {

                switch (c) {

                    case 'n' ->
                            out.append('\n');

                    case 'r' ->
                            out.append('\r');

                    case 't' ->
                            out.append('\t');

                    case '"' ->
                            out.append('"');

                    case '\\' ->
                            out.append('\\');

                    default ->
                            out.append(c);
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

    static class StaticFileHandler
            implements HttpHandler {

        @Override
        public void handle(
                HttpExchange exchange)
                throws IOException {

            String rawPath =
                    exchange.getRequestURI()
                            .getPath();

            String path =
                    "/".equals(rawPath)
                            ? "/index.html"
                            : rawPath;

            String clean =
                    path.startsWith("/")
                            ? path.substring(1)
                            : path;

            Path file =
                    Paths.get(clean).normalize();

            if (!Files.exists(file)
                    || Files.isDirectory(file)) {

                file = Paths.get("..", clean)
                        .normalize();
            }

            if (!Files.exists(file)
                    || Files.isDirectory(file)) {

                sendJsonResponse(
                        exchange,
                        404,
                        "{\"error\":\"File not found\"}");

                return;
            }

            byte[] bytes =
                    Files.readAllBytes(file);

            String type =
                    "application/octet-stream";

            if (clean.endsWith(".html"))
                type = "text/html; charset=UTF-8";

            else if (clean.endsWith(".css"))
                type = "text/css; charset=UTF-8";

            else if (clean.endsWith(".js"))
                type = "application/javascript; charset=UTF-8";

            exchange.getResponseHeaders()
                    .set("Content-Type", type);

            exchange.sendResponseHeaders(
                    200,
                    bytes.length);

            try (OutputStream os =
                         exchange.getResponseBody()) {

                os.write(bytes);
            }
        }
    }

    // ============================================================
    // FAST CODE ANALYZER (IN-MEMORY COMPILER CHECK)
    // ============================================================

    static class MultiLangAnalyzeHandler
            implements HttpHandler {

        @Override
        public void handle(
                HttpExchange exchange)
                throws IOException {

            if ("OPTIONS".equalsIgnoreCase(
                    exchange.getRequestMethod())) {

                sendJsonResponse(
                        exchange,
                        204,
                        "");

                return;
            }

            String body =
                    readBody(exchange);

            String code =
                    extractJsonString(
                            body,
                            "code");

            String lang =
                    extractJsonString(
                            body,
                            "language");

            if (lang.isEmpty())
                lang = "java";

            List<String> issueJson =
                    new ArrayList<>();

            int vulnerabilities = 0;
            int bugs = 0;
            int smells = 0;

            String[] lines =
                    code.split("\\R", -1);

            if ("java".equalsIgnoreCase(lang)) {
                bugs += collectJavaCompilerErrors(code, issueJson);
            }

            for (int i = 0;
                 i < lines.length;
                 i++) {

                String t =
                        lines[i].trim();

                int line = i + 1;

                if (t.matches(
                        ".*(=|\\+=|-=|\\*=|/=|==)\\s*$")) {

                    bugs++;

                    issueJson.add(
                            issue(
                                    "Incomplete Statement / Syntax Error",
                                    line,
                                    "danger",
                                    lang,
                                    "The statement ends with an operator.",
                                    "Complete the expression."));
                }

                if (t.contains("shell=True")
                        || t.contains(
                        "Runtime.getRuntime().exec(")
                        || t.contains(
                        "ProcessBuilder(")) {

                    vulnerabilities++;

                    issueJson.add(
                            issue(
                                    "Command Execution Risk",
                                    line,
                                    "danger",
                                    lang,
                                    "External command execution can become dangerous when input is not controlled.",
                                    "Validate input and avoid shell execution with untrusted data."));
                }

                if (t.contains("eval(")
                        || t.contains("innerHTML")) {

                    vulnerabilities++;

                    issueJson.add(
                            issue(
                                    "Dynamic Code / XSS Risk",
                                    line,
                                    "danger",
                                    lang,
                                    "Dynamic evaluation or unsafe HTML insertion can execute attacker-controlled content.",
                                    "Avoid eval() and prefer textContent or safe DOM APIs."));
                }

                if (t.contains("SELECT")
                        && (t.contains(" + ")
                        || t.contains("f\"")
                        || t.contains("f'"))) {

                    vulnerabilities++;

                    issueJson.add(
                            issue(
                                    "SQL Injection Risk",
                                    line,
                                    "danger",
                                    lang,
                                    "SQL is being built using string interpolation/concatenation.",
                                    "Use parameterized queries or PreparedStatement."));
                }

                if (t.equals("catch (Exception e)")
                        || t.equals(
                        "catch(Exception e)")
                        || t.equals("except:")) {

                    smells++;

                    issueJson.add(
                            issue(
                                    "Overly Broad Exception Handling",
                                    line,
                                    "info",
                                    lang,
                                    "A broad exception handler can hide unexpected failures.",
                                    "Catch specific exceptions and log useful diagnostic information."));
                }
            }

            int opens =
                    count(code, '{');

            int closes =
                    count(code, '}');

            if (opens != closes) {

                bugs++;

                issueJson.add(
                        issue(
                                "Mismatched Curly Braces",
                                Math.max(
                                        1,
                                        lines.length),
                                "danger",
                                lang,
                                "Opening and closing brace counts do not match.",
                                "Check the blocks and make sure every opening brace has a matching close."));
            }

            int score =
                    Math.max(
                            0,
                            100
                                    - vulnerabilities * 25
                                    - bugs * 15
                                    - smells * 8);

            String json =
                    "{\"qualityScore\":"
                            + score
                            + ",\"vulnerabilities\":"
                            + vulnerabilities
                            + ",\"bugs\":"
                            + bugs
                            + ",\"codeSmells\":"
                            + smells
                            + ",\"linesOfCode\":"
                            + lines.length
                            + ",\"issues\":["
                            + String.join(
                            ",",
                            issueJson)
                            + "]}";

            sendJsonResponse(
                    exchange,
                    200,
                    json);
        }

        private int collectJavaCompilerErrors(
                String code,
                List<String> issueJson) {

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                return 0;
            }

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            String className = findClassName(code);
            JavaSourceFromString fileObject = new JavaSourceFromString(className, code);

            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    null,
                    diagnostics,
                    List.of("-Xlint:none"),
                    null,
                    List.of(fileObject));

            task.call();

            int count = 0;
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    issueJson.add(
                            issue(
                                    "Java Compilation Error",
                                    (int) diagnostic.getLineNumber(),
                                    "danger",
                                    "java",
                                    diagnostic.getMessage(Locale.ENGLISH),
                                    "Fix the compiler error on this line."));
                    count++;
                }
            }
            return count;
        }

        private String findClassName(String code) {
            Matcher m = Pattern.compile("\\b(?:public\\s+)?class\\s+([A-Za-z_$][\\w$]*)").matcher(code);
            return m.find() ? m.group(1) : "Main";
        }

        private String issue(
                String title,
                int line,
                String severity,
                String lang,
                String desc,
                String fix) {

            return "{\"title\":\""
                    + jsonEscape(title)
                    + "\",\"line\":"
                    + line
                    + ",\"severity\":\""
                    + severity
                    + "\",\"lang\":\""
                    + jsonEscape(
                    lang.toUpperCase())
                    + "\",\"description\":\""
                    + jsonEscape(desc)
                    + "\",\"fix\":\""
                    + jsonEscape(fix)
                    + "\"}";
        }

        private int count(
                String s,
                char c) {

            int n = 0;

            for (char x :
                    s.toCharArray()) {

                if (x == c)
                    n++;
            }

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

    static class GeminiChatHandler
            implements HttpHandler {

        @Override
        public void handle(
                HttpExchange exchange)
                throws IOException {

            if ("OPTIONS".equalsIgnoreCase(
                    exchange.getRequestMethod())) {

                sendJsonResponse(
                        exchange,
                        204,
                        "");

                return;
            }

            String payload =
                    readBody(exchange);

            String userMessage =
                    extractJsonString(
                            payload,
                            "message");

            String codeContext =
                    extractJsonString(
                            payload,
                            "codeContext");

            String lang =
                    extractJsonString(
                            payload,
                            "language");

            if (lang.isEmpty())
                lang = "code";

            try {

                if (GEMINI_API_KEY == null
                        || GEMINI_API_KEY.isBlank()) {

                    sendJsonResponse(
                            exchange,
                            503,
                            "{\"reply\":\"Gemini is not configured. Set the GEMINI_API_KEY environment variable and restart the server.\"}");

                    return;
                }

                String reply =
                        callGeminiAPI(
                                userMessage,
                                codeContext,
                                lang);

                sendJsonResponse(
                        exchange,
                        200,
                        "{\"reply\":\""
                                + jsonEscape(reply)
                                + "\"}");

            } catch (Exception e) {

                e.printStackTrace();

                String message = e.getMessage() == null
                        ? "Unknown AI error."
                        : e.getMessage();

                int status = message.contains("rate-limited")
                        || message.contains("quota")
                        || message.contains("429")
                        ? 429
                        : 502;

                sendJsonResponse(
                        exchange,
                        status,
                        "{\"reply\":\"" + jsonEscape(message) + "\"}");
            }
        }

        private String callGeminiAPI(
                String prompt,
                String code,
                String lang) throws Exception {

            String fullPrompt =
                    "You are CodeSentinel AI, a fast expert programming assistant. "
                            + "Answer coding, debugging, security, Java, Python, JavaScript, "
                            + "C/C++, SQL, algorithms, APIs and general programming questions. "
                            + "Be accurate and practical. If code is supplied, reason from it. "
                            + "When giving corrected code, provide complete runnable code when practical. "
                            + "Language: " + lang
                            + "\n\nACTIVE CODE:\n" + code
                            + "\n\nUSER QUESTION:\n" + prompt;

            String requestBody =
                    "{\"contents\":[{\"parts\":[{\"text\":\""
                            + jsonEscape(fullPrompt)
                            + "\"}]}],"
                            + "\"generationConfig\":{\"maxOutputTokens\":600,\"temperature\":0.2}}";

            String[] models = new String[1 + GEMINI_FALLBACK_MODELS.length];
            models[0] = GEMINI_MODEL;
            System.arraycopy(GEMINI_FALLBACK_MODELS, 0, models, 1,
                    GEMINI_FALLBACK_MODELS.length);

            IOException lastError = null;

            for (String model : models) {
                try {
                    String endpoint =
                            "https://generativelanguage.googleapis.com/v1beta/models/"
                                    + model
                                    + ":generateContent?key="
                                    + GEMINI_API_KEY;

                    System.out.println(">>> Calling Gemini model: " + model);

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(endpoint))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    requestBody,
                                    StandardCharsets.UTF_8))
                            .build();

                    HttpResponse<String> response =
                            HTTP_CLIENT.send(
                                    request,
                                    HttpResponse.BodyHandlers.ofString(
                                            StandardCharsets.UTF_8));

                    System.out.println(">>> Gemini HTTP status: "
                            + response.statusCode());

                    if (response.statusCode() == 429) {
                        lastError = new IOException(
                                "Gemini quota exceeded for " + model
                                        + ". Trying the next available model.");
                        continue;
                    }

                    if (response.statusCode() < 200
                            || response.statusCode() >= 300) {
                        throw new IOException(
                                "Gemini HTTP "
                                        + response.statusCode()
                                        + ": "
                                        + response.body());
                    }

                    String responseBody = response.body();

                    if (responseBody == null || responseBody.isBlank()) {
                        throw new IOException(
                                "Gemini returned an empty response.");
                    }

                    String text = extractFirstText(responseBody);

                    if (!text.isEmpty()) {
                        System.out.println(">>> Gemini response received.");
                        return text;
                    }

                    throw new IOException("Gemini returned no text.");
                } catch (IOException e) {
                    lastError = e;
                    if (!e.getMessage().contains("quota exceeded")) {
                        throw e;
                    }
                }
            }

            throw new IOException(
                    "All configured Gemini models are currently rate-limited. "
                            + (lastError == null ? "" : lastError.getMessage()));
        }

        private String extractFirstText(
                String json) {

            int p =
                    json.indexOf("\"text\"");

            if (p < 0)
                return "";

            p =
                    json.indexOf(
                            ':',
                            p);

            if (p < 0)
                return "";

            p++;

            while (p < json.length()
                    && Character.isWhitespace(
                    json.charAt(p))) {

                p++;
            }

            if (p >= json.length()
                    || json.charAt(p) != '"') {

                return "";
            }

            p++;

            StringBuilder out =
                    new StringBuilder();

            boolean escaped = false;

            for (; p < json.length(); p++) {

                char c =
                        json.charAt(p);

                if (escaped) {

                    switch (c) {

                        case 'n' ->
                                out.append('\n');

                        case 'r' ->
                                out.append('\r');

                        case 't' ->
                                out.append('\t');

                        case '"' ->
                                out.append('"');

                        case '\\' ->
                                out.append('\\');

                        case '/' ->
                                out.append('/');

                        case 'b' ->
                                out.append('\b');

                        case 'f' ->
                                out.append('\f');

                        default ->
                                out.append(c);
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

    static class CodeRunHandler
            implements HttpHandler {

        @Override
        public void handle(
                HttpExchange exchange)
                throws IOException {

            if ("OPTIONS".equalsIgnoreCase(
                    exchange.getRequestMethod())) {

                sendJsonResponse(
                        exchange,
                        204,
                        "");

                return;
            }

            String payload =
                    readBody(exchange);

            String code =
                    extractJsonString(
                            payload,
                            "code");

            String lang =
                    extractJsonString(
                            payload,
                            "language")
                            .toLowerCase(
                                    Locale.ROOT);

            if (code.isBlank()) {

                sendJsonResponse(
                        exchange,
                        400,
                        "{\"success\":false,\"output\":\"No code supplied.\"}");

                return;
            }

            try {

                RunResult result =
                        switch (lang) {

                            case "java" ->
                                    runJava(code);

                            case "python" ->
                                    runProcess(
                                            code,
                                            "python");

                            case "javascript" ->
                                    runProcess(
                                            code,
                                            "node");

                            case "cpp" ->
                                    runCpp(code);

                            case "sql" ->
                                    new RunResult(
                                            false,
                                            "SQL execution needs a configured database connection. The code was analyzed, but not executed.");

                            default ->
                                    new RunResult(
                                            false,
                                            "Run is not configured for "
                                                    + lang
                                                    + ".");
                        };

                sendJsonResponse(
                        exchange,
                        200,
                        "{\"success\":"
                                + result.success
                                + ",\"output\":\""
                                + jsonEscape(
                                result.output)
                                + "\"}");

            } catch (Exception e) {

                sendJsonResponse(
                        exchange,
                        200,
                        "{\"success\":false,\"output\":\""
                                + jsonEscape(
                                e.getMessage())
                                + "\"}");
            }
        }

        private RunResult runJava(
                String code)
                throws Exception {

            Path dir =
                    Files.createTempDirectory(
                            "codesentinel-java-");

            try {

                String className =
                        findPublicOrTopClass(code);

                if (className == null)
                    className = "Main";

                String source = code;

                if (!code.contains(
                        "public class")
                        && !code.matches(
                        "(?s).*\\bclass\\s+"
                                + java.util.regex.Pattern
                                .quote(className)
                                + "\\b.*")) {

                    source =
                            "public class Main { "
                                    + "public static void main(String[] args) { "
                                    + "System.out.println(\"No main method found.\"); "
                                    + "} }";

                    className = "Main";
                }

                Path javaFile =
                        dir.resolve(
                                className
                                        + ".java");

                Files.writeString(
                        javaFile,
                        source,
                        StandardCharsets.UTF_8);

                Process compile =
                        new ProcessBuilder(
                                "javac",
                                javaFile.toString())
                                .redirectErrorStream(true)
                                .start();

                String compileOut =
                        readLimited(
                                compile.getInputStream());

                boolean compiled =
                        compile.waitFor(
                                6,
                                TimeUnit.SECONDS)
                                && compile.exitValue()
                                == 0;

                if (!compiled) {

                    return new RunResult(
                            false,
                            "Compilation failed:\n"
                                    + compileOut);
                }

                ProcessBuilder runBuilder =
                        new ProcessBuilder(
                                "java",
                                "-cp",
                                dir.toString(),
                                className);

                runBuilder.environment().remove("JAVA_TOOL_OPTIONS");

                Process run =
                        runBuilder
                                .redirectErrorStream(true)
                                .start();

                String output =
                        readLimited(
                                run.getInputStream());

                boolean finished =
                        run.waitFor(
                                5,
                                TimeUnit.SECONDS);

                if (!finished) {

                    run.destroyForcibly();

                    return new RunResult(
                            false,
                            "Execution timed out (5 seconds).");
                }

                return new RunResult(
                        run.exitValue() == 0,
                        output.isBlank()
                                ? "Program finished with no output."
                                : output);

            } finally {

                deleteTree(dir);
            }
        }

        private String findPublicOrTopClass(
                String code) {

            java.util.regex.Matcher m =
                    java.util.regex.Pattern
                            .compile(
                                    "\\bpublic\\s+class\\s+([A-Za-z_$][\\w$]*)|\\bclass\\s+([A-Za-z_$][\\w$]*)")
                            .matcher(code);

            if (!m.find())
                return null;

            return m.group(1) != null
                    ? m.group(1)
                    : m.group(2);
        }

        private RunResult runProcess(
                String code,
                String executable)
                throws Exception {

            Path file =
                    Files.createTempFile(
                            "codesentinel-",
                            executable.equals("python")
                                    ? ".py"
                                    : ".js");

            try {

                Files.writeString(
                        file,
                        code,
                        StandardCharsets.UTF_8);

                Process p =
                        new ProcessBuilder(
                                executable,
                                file.toString())
                                .redirectErrorStream(true)
                                .start();

                String output =
                        readLimited(
                                p.getInputStream());

                boolean finished =
                        p.waitFor(
                                5,
                                TimeUnit.SECONDS);

                if (!finished) {

                    p.destroyForcibly();

                    return new RunResult(
                            false,
                            "Execution timed out (5 seconds).");
                }

                return new RunResult(
                        p.exitValue() == 0,
                        output.isBlank()
                                ? "Program finished with no output."
                                : output);

            } finally {

                Files.deleteIfExists(file);
            }
        }

        private RunResult runCpp(
                String code)
                throws Exception {

            Path dir =
                    Files.createTempDirectory(
                            "codesentinel-cpp-");

            try {

                Path source =
                        dir.resolve(
                                "main.cpp");

                Path exe =
                        dir.resolve(
                                "main.exe");

                Files.writeString(
                        source,
                        code,
                        StandardCharsets.UTF_8);

                Process compile =
                        new ProcessBuilder(
                                "g++",
                                source.toString(),
                                "-o",
                                exe.toString())
                                .redirectErrorStream(true)
                                .start();

                String compileOut =
                        readLimited(
                                compile.getInputStream());

                if (!compile.waitFor(
                        6,
                        TimeUnit.SECONDS)
                        || compile.exitValue() != 0) {

                    return new RunResult(
                            false,
                            "Compilation failed:\n"
                                    + compileOut);
                }

                Process run =
                        new ProcessBuilder(
                                exe.toString())
                                .redirectErrorStream(true)
                                .start();

                String output =
                        readLimited(
                                run.getInputStream());

                if (!run.waitFor(
                        5,
                        TimeUnit.SECONDS)) {

                    run.destroyForcibly();

                    return new RunResult(
                            false,
                            "Execution timed out (5 seconds).");
                }

                return new RunResult(
                        run.exitValue() == 0,
                        output.isBlank()
                                ? "Program finished with no output."
                                : output);

            } finally {

                deleteTree(dir);
            }
        }

        private String readLimited(
                InputStream in)
                throws IOException {

            byte[] data =
                    in.readAllBytes();

            int max =
                    Math.min(
                            data.length,
                            20000);

            return new String(
                    data,
                    0,
                    max,
                    StandardCharsets.UTF_8);
        }

        private void deleteTree(
                Path root) {

            try (var stream =
                         Files.walk(root)) {

                stream.sorted(
                                Comparator.reverseOrder())
                        .forEach(p -> {

                            try {

                                Files.deleteIfExists(p);

                            } catch (IOException ignored) {
                            }
                        });

            } catch (IOException ignored) {
            }
        }

        record RunResult(
                boolean success,
                String output) {
        }
    }
}
