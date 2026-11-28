{
    const view = document.querySelector("[data-week-view]");

    if (view) {
        const controls = view.querySelector("[data-week-controls]");
        const anchor = controls.querySelector("input");
        const summary = controls.querySelector("[data-week-summary]");
        const empty = view.querySelector("[data-week-empty]");
        const rows = view.querySelectorAll(".work-row");
        const storageKey = "planner:subtask-week:" + location.pathname + ":" + view.dataset.taskId;
        const dateLabel = new Intl.DateTimeFormat("vi-VN", { dateStyle: "medium", timeZone: "UTC" });

        function selectedWeek(day) {
            const monday = new Date(day + "T00:00:00Z");
            if (Number.isNaN(monday.getTime())) return null;

            monday.setUTCDate(monday.getUTCDate() - ((monday.getUTCDay() + 6) % 7));
            const sunday = new Date(monday.getTime());
            sunday.setUTCDate(sunday.getUTCDate() + 6);
            return { monday, sunday };
        }

        function updateFilter() {
            const week = selectedWeek(anchor.value);
            let visibleCount = 0;

            for (const row of rows) {
                const scheduled = new Date(row.dataset.scheduledDate + "T00:00:00Z");
                row.hidden = week !== null && !(scheduled >= week.monday && scheduled <= week.sunday);
                if (!row.hidden) visibleCount += 1;
            }

            empty.hidden = visibleCount !== 0;
            summary.textContent = week
                ? `Tuần ${dateLabel.format(week.monday)} – ${dateLabel.format(week.sunday)} · ${visibleCount} việc nhỏ`
                : `Tất cả ${visibleCount} việc nhỏ`;
        }

        function applySelection() {
            try {
                sessionStorage.setItem(storageKey, anchor.value);
            } catch {
                // Filtering remains available when the browser blocks storage.
            }
            updateFilter();
        }

        try {
            anchor.value = sessionStorage.getItem(storageKey) || "";
        } catch {
            // Use the unfiltered list when storage is unavailable.
        }

        anchor.addEventListener("change", applySelection);
        controls.querySelector("[data-week-today]").addEventListener("click", () => {
            const now = new Date();
            const month = String(now.getMonth() + 1).padStart(2, "0");
            const day = String(now.getDate()).padStart(2, "0");
            anchor.value = `${now.getFullYear()}-${month}-${day}`;
            applySelection();
        });
        controls.querySelector("[data-week-reset]").addEventListener("click", () => {
            anchor.value = "";
            applySelection();
        });

        updateFilter();
        controls.hidden = false;
    }
}
