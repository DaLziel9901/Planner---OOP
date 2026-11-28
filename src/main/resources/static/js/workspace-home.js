{
    const workspace = document.querySelector("[data-workspace]");

    if (workspace) {
        const panels = Array.from(workspace.querySelectorAll("[data-workspace-panel]"));
        const goals = Array.from(workspace.querySelectorAll("[data-workspace-goal]"));
        const goalPanels = workspace.querySelectorAll("[data-goal-panel]");
        const navigation = document.querySelectorAll("[data-workspace-nav]");
        const headerLabel = document.querySelector("[data-workspace-label]");
        const goalPrefix = "#muc-tieu-";
        const selectedGoalKey = "planner:selected-goal:" + location.pathname;

        let selectedGoal;
        try {
            selectedGoal = sessionStorage.getItem(selectedGoalKey);
        } catch {
            // The active page URL still supports choosing a goal when storage is disabled.
        }

        function showWorkspace() {
            const hash = location.hash;
            const requestedPanel = panels.find((panel) => "#" + panel.id === hash);
            const activePanel = requestedPanel ? requestedPanel.dataset.workspacePanel : "plans";
            const requestedGoal = hash.startsWith(goalPrefix) ? hash.slice(goalPrefix.length) : selectedGoal;
            const goal = goals.find((link) => link.dataset.planId === requestedGoal) || goals[0];

            for (const panel of panels) {
                panel.hidden = panel.dataset.workspacePanel !== activePanel;
            }
            for (const link of navigation) {
                if (link.dataset.workspaceNav === activePanel) {
                    link.setAttribute("aria-current", "page");
                    if (headerLabel) headerLabel.textContent = link.textContent.trim();
                } else {
                    link.removeAttribute("aria-current");
                }
            }

            if (!goal) return;

            selectedGoal = goal.dataset.planId;
            for (const link of goals) {
                if (link === goal) link.setAttribute("aria-current", "location");
                else link.removeAttribute("aria-current");
            }
            for (const panel of goalPanels) {
                panel.hidden = panel.dataset.goalPanel !== selectedGoal;
            }
            try {
                sessionStorage.setItem(selectedGoalKey, selectedGoal);
            } catch {
                // The selection remains usable for the current page.
            }
        }

        window.addEventListener("hashchange", () => {
            if (location.hash !== "#main-content") showWorkspace();
        });
        showWorkspace();
    }
}
