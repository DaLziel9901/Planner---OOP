{
    const widget = document.querySelector("[data-reminders]");

    if (widget) {
        const trigger = widget.querySelector(".reminder-trigger");
        const dueDot = widget.querySelector("[data-reminder-due]");
        const close = widget.querySelector("[data-reminder-close]");
        const feedback = widget.querySelector("[data-reminder-feedback]");
        const content = widget.querySelector("[data-reminder-content]");
        const millisecondsPerDay = 86_400_000;
        const dateLabel = new Intl.DateTimeFormat("vi-VN", { dateStyle: "medium", timeZone: "UTC" });
        const dayLabel = new Intl.DateTimeFormat("vi-VN", { dateStyle: "full", timeZone: "UTC" });
        let request;
        let reminderItems = [];

        function element(tag, className, text) {
            const node = document.createElement(tag);
            if (className) node.className = className;
            if (text !== undefined) node.textContent = text;
            return node;
        }

        function localWorkspace() {
            const workspace = document.querySelector("[data-workspace]");
            return workspace?.dataset.progressAvailable === "true" ? workspace : null;
        }

        async function readWorkspace(signal) {
            const workspace = localWorkspace();
            if (workspace) return workspace;

            const response = await fetch(widget.dataset.workspaceUrl, {
                cache: "no-store",
                signal,
                headers: { Accept: "text/html" }
            });
            if (!response.ok) throw new Error("Workspace unavailable");
            const page = new DOMParser().parseFromString(await response.text(), "text/html");
            const loaded = page.querySelector("[data-workspace]");
            if (loaded?.dataset.progressAvailable !== "true") throw new Error("Workspace incomplete");
            return loaded;
        }

        function readItems(workspace) {
            const plans = new Map();
            for (const goal of workspace.querySelectorAll("[data-workspace-goal]")) {
                plans.set(goal.dataset.planId, goal.dataset.title);
            }
            const items = [];
            for (const row of workspace.querySelectorAll("[data-workspace-leaf]")) {
                const link = row.querySelector(".goal-node-label");
                const day = Date.parse(row.dataset.dueDate + "T00:00:00Z");
                if (!link || Number.isNaN(day)) continue;
                items.push({
                    title: link.textContent.trim(),
                    href: link.getAttribute("href"),
                    goal: plans.get(row.dataset.planId),
                    day,
                    done: row.dataset.done === "true",
                    completion: row.querySelector(".completion-form")
                });
            }
            return items.sort((left, right) => left.day - right.day || left.title.localeCompare(right.title, "vi"));
        }

        function localDay(date) {
            return Date.UTC(date.getFullYear(), date.getMonth(), date.getDate());
        }

        function updateDueDot(today) {
            const hasDueWork = reminderItems.some((item) => !item.done && item.day <= today);
            dueDot.hidden = !hasDueWork;
            if (hasDueWork) trigger.setAttribute("aria-label", "Nhắc nhở: có việc đến hạn hoặc quá hạn");
            else trigger.removeAttribute("aria-label");
        }

        function completionForm(item) {
            const action = item.completion?.getAttribute("action");
            if (!action) return null;
            const form = element("form", "reminder-item__action");
            form.method = "post";
            form.action = action;
            for (const field of item.completion.querySelectorAll('input[type="hidden"][name]')) {
                form.append(document.importNode(field, false));
            }
            const submit = element("button", null, "Đánh dấu xong");
            submit.type = "submit";
            submit.name = "done";
            submit.value = "true";
            submit.setAttribute("aria-label", "Đánh dấu xong " + item.title);
            form.append(submit);
            return form;
        }

        function renderItem(item, today) {
            const row = element("li", "reminder-item");
            const main = element("div", "reminder-item__main");
            const title = element("a", "reminder-item__title", item.title);
            title.href = item.href;
            const meta = [item.goal, dateLabel.format(item.day)].filter(Boolean).join(" · ");
            main.append(title, element("p", "reminder-item__meta", meta));
            const status = element("span", "reminder-item__status", "Chưa xong");
            if (item.done) {
                status.classList.add("reminder-item__status--done");
                status.textContent = "Đã xong";
            } else if (item.day <= today) {
                status.classList.add("reminder-item__status--due");
                status.textContent = item.day === today
                    ? "Đến hạn hôm nay"
                    : "Trễ " + (today - item.day) / millisecondsPerDay + " ngày";
            }
            row.append(main, status);
            if (!item.done) {
                const action = completionForm(item);
                if (action) row.append(action);
            }
            return row;
        }

        function renderGroup(title, items, emptyMessage, today, groupByDay = false) {
            const section = element("section", "reminder-group");
            const heading = element("header", "reminder-group__header");
            heading.append(
                element("h3", null, title),
                element("span", "reminder-group__count", String(items.length))
            );
            section.append(heading);
            if (!items.length) {
                section.append(element("p", "reminder-empty", emptyMessage));
                return section;
            }

            let list;
            let previousDay;
            for (const item of items) {
                if (!list || (groupByDay && item.day !== previousDay)) {
                    if (groupByDay) section.append(element("h4", "reminder-day", dayLabel.format(item.day)));
                    list = element("ul", "reminder-list");
                    section.append(list);
                }
                list.append(renderItem(item, today));
                previousDay = item.day;
            }
            return section;
        }

        function render(workspace) {
            const now = new Date();
            const today = localDay(now);
            const daysUntilSunday = 6 - ((now.getDay() + 6) % 7);
            const endOfWeek = today + daysUntilSunday * millisecondsPerDay;
            const items = readItems(workspace);
            reminderItems = items;
            updateDueDot(today);
            content.replaceChildren(
                renderGroup("Hôm nay", items.filter((item) => item.day === today), "Hôm nay chưa có việc được xếp lịch.", today),
                renderGroup("Quá hạn", items.filter((item) => !item.done && item.day < today), "Bạn không có việc quá hạn.", today),
                renderGroup(
                    "Sắp tới trong tuần", items.filter((item) => item.day > today && item.day <= endOfWeek),
                    "Chưa có việc dự kiến cho các ngày còn lại trong tuần.", today, true
                )
            );
        }

        async function openReminders() {
            const controller = new AbortController();
            request = controller;
            content.replaceChildren();
            feedback.textContent = "Đang tải nhắc nhở…";
            feedback.hidden = false;
            try {
                const workspace = await readWorkspace(controller.signal);
                if (!widget.open || controller.signal.aborted) return;
                render(workspace);
                feedback.hidden = true;
            } catch {
                if (!widget.open || controller.signal.aborted) return;
                reminderItems = [];
                updateDueDot(localDay(new Date()));
                feedback.textContent = "Chưa tải được nhắc nhở. Hãy mở lại để thử.";
            }
        }

        function closeReminders() {
            widget.open = false;
            trigger.focus({ preventScroll: true });
        }

        const workspace = localWorkspace();
        if (workspace) reminderItems = readItems(workspace);
        updateDueDot(localDay(new Date()));
        window.addEventListener("focus", () => updateDueDot(localDay(new Date())));
        close.hidden = false;
        close.addEventListener("click", closeReminders);
        widget.addEventListener("toggle", () => {
            request?.abort();
            if (widget.open) openReminders();
        });
        widget.addEventListener("keydown", (event) => {
            if (event.key === "Escape" && widget.open) {
                event.preventDefault();
                closeReminders();
            }
        });
        document.addEventListener("pointerdown", (event) => {
            if (widget.open && !widget.contains(event.target)) widget.open = false;
        });
    }
}
