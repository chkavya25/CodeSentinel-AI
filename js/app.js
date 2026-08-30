
const API_URL = "http://localhost:8080/api";

/* =========================================================
   DEFAULT STARTER CODE FOR EACH LANGUAGE
   ========================================================= */

const defaultCode = {

    Python: `# Python Starter Code

def main():
    # Write your code here
    print("Hello, World!")


if __name__ == "__main__":
    main()
`,

    Java: `// Java Starter Code

public class Main {

    public static void main(String[] args) {

        // Write your code here
        System.out.println("Hello, World!");
    }
}
`,

    JavaScript: `// JavaScript Starter Code

function main() {

    // Write your code here
    console.log("Hello, World!");
}

main();
`,

    C: `// C Starter Code

#include <stdio.h>

int main() {

    // Write your code here
    printf("Hello, World!\\n");

    return 0;
}
`,

    "C++": `// C++ Starter Code

#include <iostream>
using namespace std;

int main() {

    // Write your code here
    cout << "Hello, World!" << endl;

    return 0;
}
`,

    SQL: `-- SQL Starter Code

CREATE TABLE students (
    id INT PRIMARY KEY,
    name VARCHAR(100),
    age INT
);

-- Insert sample data
INSERT INTO students (id, name, age)
VALUES (1, 'Student', 20);

-- View the data
SELECT * FROM students;
`
};


/* =========================================================
   GET DEFAULT CODE - WORKS WITH java / Java / JAVA
   ========================================================= */

function getDefaultCode(language) {

    if (!language) {
        return defaultCode.Java;
    }

    const value = String(language).trim();

    const exactMatch = Object.keys(defaultCode).find(
        key => key.toLowerCase() === value.toLowerCase()
    );

    return exactMatch
        ? defaultCode[exactMatch]
        : defaultCode.Java;
}


/* =========================================================
   DEFAULT PROJECTS
   ========================================================= */

const defaultProjects = {

    "My Project": {
        lang: "Java",
        code: defaultCode.Java
    }

};


/* =========================================================
   REMOVE LINE NUMBERS IF USER PASTES CODE
   ========================================================= */

function stripLineNumbers(text) {

    return text
        .split("\n")
        .map(line =>
            line.replace(
                /^\s*\d+\s*\|\s?/,
                ""
            )
        )
        .join("\n");
}


/* =========================================================
   UPDATE LINE NUMBERS
   ========================================================= */

function updateLineNumbers() {

    const codeInput =
        document.getElementById("code-input");

    const gutter =
        document.getElementById("line-numbers");

    if (!codeInput || !gutter) {
        return;
    }

    const count =
        Math.max(
            1,
            codeInput.value.split("\n").length
        );

    gutter.textContent =
        Array.from(
            { length: count },
            (_, i) => i + 1
        ).join("\n");

    gutter.scrollTop =
        codeInput.scrollTop;
}


/* =========================================================
   API FETCH
   ========================================================= */

async function apiFetch(path, options = {}) {

    const controller =
        new AbortController();

    const timeout =
        setTimeout(
            () => controller.abort(),
            50000
        );

    try {

        return await fetch(
            `${API_URL}${path}`,
            {
                ...options,
                signal: controller.signal
            }
        );

    } finally {

        clearTimeout(timeout);
    }
}


/* =========================================================
   MAIN APPLICATION
   ========================================================= */

document.addEventListener(
    "DOMContentLoaded",
    () => {

        const codeInput =
            document.getElementById("code-input");

        const langSelect =
            document.getElementById("lang-select");

        const projectSelect =
            document.getElementById("project-select");

        const newProjectBtn =
            document.getElementById("new-project-btn");

        const saveProjectBtn =
            document.getElementById("save-project-btn");

        const deleteProjectBtn =
            document.getElementById("delete-project-btn");

        const analyzeBtn =
            document.getElementById("analyze-btn");

        const runBtn =
            document.getElementById("run-btn");

        const loadSampleBtn =
            document.getElementById("load-sample-btn");

        const clearBtn =
            document.getElementById("clear-btn");

        const issuesList =
            document.getElementById("issues-list");

        const scoreBadge =
            document.getElementById("score-badge");

        const outputPanel =
            document.getElementById("run-output");

        const chatForm =
            document.getElementById("chat-form");

        const chatInput =
            document.getElementById("chat-input");

        const chatMessages =
            document.getElementById("chat-messages");


        /* =====================================================
           LOAD PROJECTS
           ===================================================== */

        let projects = {
            ...defaultProjects
        };


        try {

            const saved =
                localStorage.getItem(
                    "sentinel_projects"
                );

            if (saved) {

                const parsed =
                    JSON.parse(saved);

                if (
                    parsed &&
                    typeof parsed === "object" &&
                    Object.keys(parsed).length > 0
                ) {

                    projects = parsed;
                }
            }

        } catch (error) {

            console.warn(
                "Could not load saved projects:",
                error
            );
        }


        /* =====================================================
           SAVE PROJECTS
           ===================================================== */

        function saveProjectsToStorage() {

            try {

                localStorage.setItem(
                    "sentinel_projects",
                    JSON.stringify(projects)
                );

            } catch (error) {

                console.error(
                    "Could not save projects:",
                    error
                );
            }
        }


        /* =====================================================
           CLEAR RUN OUTPUT
           ===================================================== */

        function clearRunOutput() {

            if (!outputPanel) {
                return;
            }

            outputPanel.textContent =
                "Run your code to see output here.";

            outputPanel.className =
                "run-output";
        }


        /* =====================================================
           RENDER PROJECT DROPDOWN
           ===================================================== */

        function renderProjectDropdown() {

            if (!projectSelect) {
                return;
            }

            projectSelect.innerHTML = "";

            Object.keys(projects).forEach(
                name => {

                    const option =
                        document.createElement(
                            "option"
                        );

                    option.value = name;
                    option.textContent = name;

                    projectSelect.appendChild(
                        option
                    );
                }
            );


            loadSelectedProject();
        }


        /* =====================================================
           LOAD SELECTED PROJECT
           ===================================================== */

        function loadSelectedProject() {

            if (!projectSelect) {
                return;
            }

            const current =
                projects[
                    projectSelect.value
                ];

            if (!current) {
                return;
            }


            let language =
                current.lang || "Java";


            if (langSelect) {

                const options =
                    Array.from(
                        langSelect.options
                    );

                const matchingOption =
                    options.find(
                        option =>
                            option.value.toLowerCase()
                            ===
                            String(language).toLowerCase()
                    );

                if (matchingOption) {

                    langSelect.value =
                        matchingOption.value;

                    language =
                        matchingOption.value;

                } else {

                    langSelect.value = "Java";

                    language = "Java";
                }
            }


            if (
                current.code &&
                current.code.trim()
            ) {

                codeInput.value =
                    current.code;

            } else {

                codeInput.value =
                    getDefaultCode(language);
            }


            updateLineNumbers();

            clearRunOutput();
        }


        /* =====================================================
           BUTTON BUSY STATE
           ===================================================== */

        function setButtonBusy(
            button,
            busy,
            text,
            icon = "fa-spinner fa-spin"
        ) {

            if (!button) {
                return;
            }


            if (busy) {

                button.disabled = true;

                button.dataset.original =
                    button.innerHTML;

                button.innerHTML =
                    `<i class="fa-solid ${icon}"></i> ${text}`;

            } else {

                button.disabled = false;

                button.innerHTML =
                    button.dataset.original
                    ||
                    button.innerHTML;
            }
        }


        /* =====================================================
           CODE INPUT
           ===================================================== */

        codeInput?.addEventListener(
            "input",
            updateLineNumbers
        );


        codeInput?.addEventListener(
            "scroll",
            () => {

                const gutter =
                    document.getElementById(
                        "line-numbers"
                    );

                if (gutter) {

                    gutter.scrollTop =
                        codeInput.scrollTop;
                }
            }
        );


        /* =====================================================
           PASTE CODE
           ===================================================== */

        codeInput?.addEventListener(
            "paste",
            async event => {

                event.preventDefault();

                let text = "";

                try {

                    text =
                        await navigator.clipboard
                            .readText();

                } catch (_) {

                    text =
                        (
                            event.clipboardData
                            ||
                            window.clipboardData
                        )
                            .getData("text");
                }


                codeInput.value =
                    stripLineNumbers(text);

                updateLineNumbers();
            }
        );


        /* =====================================================
           NEW PROJECT
           ===================================================== */

        newProjectBtn?.addEventListener(
            "click",
            () => {

                const name =
                    prompt(
                        "Enter project name:"
                    );

                if (!name?.trim()) {
                    return;
                }


                const cleanName =
                    name
                        .trim()
                        .replace(
                            /\s+/g,
                            "-"
                        );


                const language =
                    langSelect?.value
                    ||
                    "Java";


                projects[cleanName] = {

                    lang: language,

                    code:
                        getDefaultCode(
                            language
                        )
                };


                saveProjectsToStorage();

                renderProjectDropdown();

                projectSelect.value =
                    cleanName;

                loadSelectedProject();
            }
        );


        /* =====================================================
           SAVE PROJECT
           ===================================================== */

        saveProjectBtn?.addEventListener(
            "click",
            () => {

                const name =
                    projectSelect?.value;

                if (!name) {
                    return;
                }


                projects[name] = {

                    lang:
                        langSelect?.value
                        ||
                        "Java",

                    code:
                        stripLineNumbers(
                            codeInput.value
                        )
                };


                saveProjectsToStorage();

                alert(
                    `Saved "${name}" successfully!`
                );
            }
        );


        /* =====================================================
           DELETE PROJECT
           ===================================================== */

        deleteProjectBtn?.addEventListener(
            "click",
            () => {

                const name =
                    projectSelect?.value;


                if (
                    !name ||
                    Object.keys(projects).length <= 1
                ) {

                    alert(
                        "You must keep at least one project."
                    );

                    return;
                }


                if (
                    confirm(
                        `Delete project "${name}"?`
                    )
                ) {

                    delete projects[name];

                    saveProjectsToStorage();

                    renderProjectDropdown();
                }
            }
        );


        /* =====================================================
           LANGUAGE CHANGE
           ===================================================== */

        langSelect?.addEventListener(
            "change",
            () => {

                const language =
                    langSelect.value;


                const current =
                    projects[
                        projectSelect?.value
                    ];


                if (current) {

                    current.lang =
                        language;

                    current.code =
                        getDefaultCode(
                            language
                        );
                }


                codeInput.value =
                    getDefaultCode(
                        language
                    );


                updateLineNumbers();

                clearRunOutput();
            }
        );


        /* =====================================================
           PROJECT CHANGE
           ===================================================== */

        projectSelect?.addEventListener(
            "change",
            () => {

                loadSelectedProject();
            }
        );


        /* =====================================================
           CLEAR BUTTON
           ===================================================== */

        clearBtn?.addEventListener(
            "click",
            () => {

                codeInput.value = "";

                updateLineNumbers();

                clearRunOutput();
            }
        );


        /* =====================================================
           SAMPLE BUTTON
           ===================================================== */

        loadSampleBtn?.addEventListener(
            "click",
            () => {

                const language =
                    langSelect?.value
                    ||
                    "Java";


                codeInput.value =
                    getDefaultCode(
                        language
                    );


                const current =
                    projects[
                        projectSelect?.value
                    ];


                if (current) {

                    current.lang =
                        language;

                    current.code =
                        codeInput.value;
                }


                updateLineNumbers();

                clearRunOutput();
            }
        );


        /* =====================================================
           ANALYZE CODE
           ===================================================== */

        async function analyzeCode() {

            const code =
                stripLineNumbers(
                    codeInput.value
                );


            if (!code.trim()) {

                alert(
                    "Please write or paste code first."
                );

                return;
            }


            setButtonBusy(
                analyzeBtn,
                true,
                "Analyzing..."
            );


            try {

                const response =
                    await apiFetch(
                        "/analyze",
                        {
                            method: "POST",

                            headers: {
                                "Content-Type":
                                    "application/json"
                            },

                            body:
                                JSON.stringify({
                                    language:
                                        langSelect.value,
                                    code
                                })
                        }
                    );


                if (!response.ok) {

                    throw new Error(
                        "Analysis server error"
                    );
                }


                const data =
                    await response.json();


                renderAnalysisResults(
                    data
                );


            } catch (error) {

                renderAnalysisResults({

                    qualityScore: 0,

                    vulnerabilities: 0,

                    bugs: 1,

                    codeSmells: 0,

                    linesOfCode:
                        code.split("\n").length,

                    issues: [

                        {

                            title:
                                "Backend Unavailable",

                            line: 1,

                            severity:
                                "danger",

                            description:
                                "Start CodeSentinelServer.java on port 8080.",

                            fix:
                                error.name ===
                                "AbortError"

                                    ? "The request timed out."

                                    : error.message
                        }
                    ]
                });

            } finally {

                setButtonBusy(
                    analyzeBtn,
                    false
                );
            }
        }


        analyzeBtn?.addEventListener(
            "click",
            analyzeCode
        );


        /* =====================================================
           RUN CODE
           ===================================================== */

        runBtn?.addEventListener(
            "click",
            async () => {

                const code =
                    stripLineNumbers(
                        codeInput.value
                    );


                if (!code.trim()) {

                    alert(
                        "Please write or paste code first."
                    );

                    return;
                }


                setButtonBusy(
                    runBtn,
                    true,
                    "Running..."
                );


                if (outputPanel) {

                    outputPanel.textContent =
                        "Compiling / executing...";

                    outputPanel.className =
                        "run-output running";
                }


                try {

                    const response =
                        await apiFetch(
                            "/run",
                            {

                                method:
                                    "POST",

                                headers: {
                                    "Content-Type":
                                        "application/json"
                                },

                                body:
                                    JSON.stringify({
                                        language:
                                            langSelect.value,
                                        code
                                    })
                            }
                        );


                    /*
                     * FIX:
                     * Check server status before JSON parsing.
                     */

                    if (!response.ok) {

                        const errorText =
                            await response.text();

                        throw new Error(
                            `Server error (${response.status}): ${
                                errorText.substring(0, 200)
                            }`
                        );
                    }


                    /*
                     * FIX:
                     * Prevent "Unexpected token '<'"
                     * when server sends HTML.
                     */

                    const contentType =
                        response.headers.get("content-type")
                        ||
                        "";


                    if (
                        !contentType
                            .toLowerCase()
                            .includes("application/json")
                    ) {

                        const text =
                            await response.text();

                        throw new Error(
                            `Expected JSON but received HTML/text: ${
                                text.substring(0, 200)
                            }`
                        );
                    }


                    const data =
                        await response.json();


                    if (outputPanel) {

                        outputPanel.textContent =
                            data.output
                            ||
                            "No output.";

                        outputPanel.className =
                            `run-output ${
                                data.success
                                    ? "success"
                                    : "error"
                            }`;
                    }


                    await analyzeCode();


                } catch (error) {

                    if (outputPanel) {

                        outputPanel.textContent =
                            `Run failed: ${error.message}`;

                        outputPanel.className =
                            "run-output error";
                    }

                } finally {

                    setButtonBusy(
                        runBtn,
                        false
                    );
                }
            }
        );


        /* =====================================================
           RENDER ANALYSIS RESULTS
           ===================================================== */

        function renderAnalysisResults(
            data
        ) {

            document.getElementById(
                "vuln-count"
            ).textContent =
                data.vulnerabilities ?? 0;


            document.getElementById(
                "bug-count"
            ).textContent =
                data.bugs ?? 0;


            document.getElementById(
                "smell-count"
            ).textContent =
                data.codeSmells ?? 0;


            document.getElementById(
                "loc-count"
            ).textContent =
                data.linesOfCode ?? 0;


            if (scoreBadge) {

                scoreBadge.textContent =
                    `Score: ${
                        data.qualityScore ?? 0
                    }/100`;


                scoreBadge.className =
                    data.qualityScore >= 75
                        ? "badge badge-good"
                        : "badge badge-warn";
            }


            if (!issuesList) {
                return;
            }


            issuesList.innerHTML = "";


            if (!data.issues?.length) {

                issuesList.innerHTML = `

                    <div class="empty-state">

                        <i class="fa-solid fa-circle-check text-info"></i>

                        <p>
                            No critical issues detected.
                        </p>

                    </div>
                `;

                return;
            }


            data.issues.forEach(
                issue => {

                    const card =
                        document.createElement(
                            "div"
                        );


                    card.className =
                        `issue-card ${
                            issue.severity
                            ||
                            "info"
                        }`;


                    card.innerHTML = `

                        <div class="issue-header">

                            <span class="issue-title"></span>

                            <span class="issue-line">
                                Line ${
                                    Number(issue.line)
                                    ||
                                    1
                                }
                            </span>

                        </div>


                        <p class="issue-desc"></p>


                        <div class="issue-fix">

                            <strong>
                                AI Fix:
                            </strong>

                            <span></span>

                        </div>
                    `;


                    card.querySelector(
                        ".issue-title"
                    ).textContent =
                        issue.title
                        ||
                        "Issue";


                    card.querySelector(
                        ".issue-desc"
                    ).textContent =
                        issue.description
                        ||
                        "";


                    card.querySelector(
                        ".issue-fix span"
                    ).textContent =
                        issue.fix
                        ||
                        "";


                    issuesList.appendChild(
                        card
                    );
                }
            );
        }


        /* =====================================================
           AI CHAT
           ===================================================== */

        chatForm?.addEventListener(
            "submit",
            async event => {

                event.preventDefault();


                const prompt =
                    chatInput.value.trim();


                if (!prompt) {
                    return;
                }


                appendChatMessage(
                    "user",
                    escapeHtml(prompt)
                );


                chatInput.value = "";


                const typingId =
                    appendChatMessage(
                        "bot",
                        `<i class="fa-solid fa-spinner fa-spin"></i> Thinking...`
                    );


                try {

                    const response =
                        await apiFetch(
                            "/chat",
                            {

                                method:
                                    "POST",

                                headers: {
                                    "Content-Type":
                                        "application/json"
                                },

                                body:
                                    JSON.stringify({

                                        message:
                                            prompt,

                                        language:
                                            langSelect.value,

                                        codeContext:
                                            stripLineNumbers(
                                                codeInput.value
                                            )
                                    })
                            }
                        );


                    const data =
                        await response.json();


                    updateChatMessage(
                        typingId,
                        formatMarkdown(
                            data.reply
                            ||
                            "No AI response."
                        )
                    );


                } catch (error) {

                    updateChatMessage(

                        typingId,

                        `<span class="text-danger">
                            AI unavailable:
                            ${escapeHtml(
                                error.message
                            )}
                        </span>`
                    );
                }
            }
        );


        /* =====================================================
           ADD CHAT MESSAGE
           ===================================================== */

        function appendChatMessage(
            role,
            html
        ) {

            const id =
                `msg-${Date.now()}-${Math.random()
                    .toString(16)
                    .slice(2)}`;


            const msg =
                document.createElement(
                    "div"
                );


            msg.className =
                `chat-msg ${role}`;


            msg.id = id;


            msg.innerHTML =
                `<div class="msg-bubble">
                    ${html}
                </div>`;


            chatMessages.appendChild(
                msg
            );


            chatMessages.scrollTop =
                chatMessages.scrollHeight;


            return id;
        }


        /* =====================================================
           UPDATE CHAT MESSAGE
           ===================================================== */

        function updateChatMessage(
            id,
            html
        ) {

            const msg =
                document.getElementById(
                    id
                );


            if (!msg) {
                return;
            }


            const bubble =
                msg.querySelector(
                    ".msg-bubble"
                );


            if (bubble) {

                bubble.innerHTML =
                    html;
            }


            chatMessages.scrollTop =
                chatMessages.scrollHeight;
        }


        /* =====================================================
           ESCAPE HTML
           ===================================================== */

        function escapeHtml(text) {

            return String(text).replace(
                /[&<>"']/g,
                character => ({

                    "&": "&amp;",

                    "<": "&lt;",

                    ">": "&gt;",

                    '"': "&quot;",

                    "'": "&#039;"

                }[character])
            );
        }


        /* =====================================================
           MARKDOWN FORMATTER
           ===================================================== */

        function formatMarkdown(
            text
        ) {

            let safe =
                escapeHtml(text);


            safe =
                safe.replace(
                    /```([a-zA-Z0-9+#-]*)\n([\s\S]*?)```/g,

                    (_, lang, code) =>

                        `<pre class="ai-code"><code>${code}</code></pre>`
                );


            safe =
                safe.replace(
                    /`([^`]+)`/g,
                    "<code>$1</code>"
                );


            return safe.replace(
                /\n/g,
                "<br>"
            );
        }


        /* =====================================================
           INITIALIZE
           ===================================================== */

        renderProjectDropdown();


        const initialLanguage =
            langSelect?.value
            ||
            "Java";


        if (
            !codeInput.value.trim()
        ) {

            codeInput.value =
                getDefaultCode(
                    initialLanguage
                );
        }


        updateLineNumbers();

    }
);
