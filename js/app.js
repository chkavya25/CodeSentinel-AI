// In your app.js:

async function apiFetch(path, options = {}) {
    const controller = new AbortController();
    // Reduce timeout from 15s down to 8s
    const timeout = setTimeout(() => controller.abort(), 8000);

    try {
        return await fetch(`${API_URL}${path}`, {
            ...options,
            signal: controller.signal
        });
    } finally {
        clearTimeout(timeout);
    }
}

// In the runBtn event listener:
// Remove or debounce the automatic background analysis on every run
runBtn?.addEventListener("click", async () => {
    // ... execution code ...
    const data = await response.json();
    if (outputPanel) {
        outputPanel.textContent = data.output || "No output.";
        outputPanel.className = `run-output ${data.success ? "success" : "error"}`;
    }
    
    // REMOVED: analyzeCode().catch(() => {});  <-- This was causing back-to-back 30s delays
});
