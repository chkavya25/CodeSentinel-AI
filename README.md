# CodeSentinel AI - Updated

## What was fixed

1. Line numbers are now displayed in the left gutter instead of being inserted into the source code.
2. Pasting numbered code automatically removes old line numbers.
3. `Run Code` actually compiles/executes Java, Python, JavaScript and C/C++ when the required local compiler/runtime is installed.
4. `AI Analysis` performs static analysis and refreshes the results.
5. The AI chatbot uses Gemini through the Java backend.
6. Gemini requests use the current `gemini-3.7-flash` model and the `x-goog-api-key` header.
7. Chat JSON parsing is safer and handles quotes/newlines inside code.
8. Chat output is HTML-escaped before rendering.
9. Buttons show a busy state and requests have a timeout.
10. Saved projects keep clean source code without line-number prefixes.

## Folder structure

- CodeSentinelServer.java
- index.html
- js/app.js

## Setup

### 1. Get a Gemini API key

Create a key in Google AI Studio.

### 2. Set the key as an environment variable

Windows PowerShell:

```powershell
$env:GEMINI_API_KEY="YOUR_KEY_HERE"
```

Keep the key out of your source code and GitHub.

### 3. Compile

```powershell
javac CodeSentinelServer.java
```

### 4. Start

```powershell
java CodeSentinelServer
```

Open:

http://localhost:8080/

## Run support

- Java: requires `javac` and `java` in PATH.
- Python: requires `python` in PATH.
- JavaScript: requires `node` in PATH.
- C/C++: requires `g++` in PATH.
- SQL: currently analyzed but not executed because no database connection is configured.

Important: this executor runs code on your own computer. Do not use it with untrusted code.
