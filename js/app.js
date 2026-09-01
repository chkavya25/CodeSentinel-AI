// ===============================
// API CONFIG
// ===============================

const API_URL = "https://codesentinel-ai-4.onrender.com";


// ===============================
// FAST API FETCH
// ===============================

async function apiFetch(endpoint, options = {}) {
    const controller = new AbortController();

    let timeoutMs = 8000;

    if (endpoint.includes("/api/chat")) {
        timeoutMs = 20000;
    } else if (endpoint.includes("/api/analyze")) {
        timeoutMs = 15000;
    } else if (endpoint.includes("/api/run")) {
        timeoutMs = 15000;
    }

    const timeout = setTimeout(() => {
        controller.abort();
    }, timeoutMs);

    try {
        const response = await fetch(`${API_URL}${endpoint}`, {
            ...options,
            signal: controller.signal,
            headers: {
                "Content-Type": "application/json",
                ...(options.headers || {})
            }
        });

        clearTimeout(timeout);

        if (!response.ok) {
            const text = await response.text();
            throw new Error(text || `HTTP ${response.status}`);
        }

        return await response.json();

    } catch (error) {
        clearTimeout(timeout);

        if (error.name === "AbortError") {
            throw new Error("Request timed out. Please try again.");
        }

        throw error;
    }
}


// ===============================
// RENDER SERVER WAKE-UP
// ===============================

document.addEventListener("DOMContentLoaded", () => {

    // Wake Render server without blocking the UI
    fetch(`${API_URL}/health`, {
        method: "GET",
        cache: "no-store"
    }).catch(() => {});

});
