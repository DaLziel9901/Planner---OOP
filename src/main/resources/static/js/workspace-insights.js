{
    const calendar = document.querySelector("[data-calendar]");
    const stats = document.querySelector("[data-stats]");

    if (calendar || stats) {
        const formatter = new Intl.DateTimeFormat("vi-VN", { dateStyle: "medium", timeZone: "UTC" });
        const fullDate = new Intl.DateTimeFormat("vi-VN", { dateStyle: "full", timeZone: "UTC" });
        const monthLabel = new Intl.DateTimeFormat("vi-VN", { month: "long", year: "numeric", timeZone: "UTC" });
        const weekdays = ["T2", "T3", "T4", "T5", "T6", "T7", "CN"];
        const planNames = new Map();
        for (const goal of document.querySelectorAll("[data-workspace-goal]")) {
            planNames.set(goal.dataset.planId, goal.dataset.title);
        }

        const leaves = [];
        for (const row of document.querySelectorAll("[data-workspace-leaf]")) {
            const link = row.querySelector(".goal-node-label");
            const dueDate = row.dataset.dueDate;
            const parsedDate = new Date(dueDate + "T00:00:00Z");
            if (!link) continue;

            leaves.push({
                title: link.textContent.trim(),
                href: link.getAttribute("href"),
                planId: row.dataset.planId,
                date: Number.isNaN(parsedDate.getTime()) ? null : parsedDate,
                done: row.dataset.done === "true"
            });
        }

        function dayKey(date) {
            return date.toISOString().split("T", 1)[0];
        }

        function today() {
            const now = new Date();
            return new Date(Date.UTC(now.getFullYear(), now.getMonth(), now.getDate()));
        }

        function moveDay(date, amount) {
            const shifted = new Date(date.getTime());
            shifted.setUTCDate(shifted.getUTCDate() + amount);
            return shifted;
        }

        function monthStart(date) {
            const result = new Date(date.getTime());
            result.setUTCDate(1);
            return result;
        }

        function moveMonth(date, amount) {
            const shifted = monthStart(date);
            shifted.setUTCMonth(shifted.getUTCMonth() + amount);
            return shifted;
        }

        function weekStart(date) {
            return moveDay(date, -((date.getUTCDay() + 6) % 7));
        }

        function rangeLabel(start, end) {
            return formatter.format(start) + " – " + formatter.format(end);
        }

        function element(tag, className, label) {
            const node = document.createElement(tag);
            if (className) node.className = className;
            if (label !== undefined) node.textContent = label;
            return node;
        }

        function forPlan(planId) {
            return planId ? leaves.filter((item) => item.planId === planId) : leaves;
        }

        function indexDates(items) {
            const result = new Map();
            for (const item of items) {
                if (!item.date) continue;
                const key = dayKey(item.date);
                const bucket = result.get(key) ?? { done: 0, total: 0 };
                bucket.total += 1;
                if (item.done) bucket.done += 1;
                result.set(key, bucket);
            }
            return result;
        }

        function pendingDateLabel(date, currentDay) {
            if (!date || date > currentDay) return "";
            return date < currentDay ? "Quá hạn" : "Đến hạn hôm nay";
        }

        function setupCalendar(view) {
            const plan = view.querySelector("[data-calendar-goal]");
            const datePicker = view.querySelector("[data-calendar-date]");
            const grid = view.querySelector("[data-calendar-grid]");
            const month = view.querySelector("[data-calendar-month]");
            const includeDone = view.querySelector("[data-calendar-done]");
            const summary = view.querySelector("[data-calendar-summary]");
            const list = view.querySelector("[data-calendar-items]");
            const empty = view.querySelector("[data-calendar-empty]");
            const modes = view.querySelectorAll("[data-calendar-mode]");
            let selected = today();
            let renderedDay;
            let mode = "day";

            function renderCalendar(currentDay) {
                const first = monthStart(selected);
                const next = moveMonth(first, 1);
                const selectedKey = dayKey(selected);
                const todayKey = dayKey(currentDay);
                const load = indexDates(forPlan(plan.value));
                const fragment = document.createDocumentFragment();
                const offset = (first.getUTCDay() + 6) % 7;
                month.textContent = monthLabel.format(first);

                for (let index = 0; index < offset; index += 1) {
                    const blank = element("span", "calendar-day calendar-day--blank");
                    blank.setAttribute("aria-hidden", "true");
                    fragment.append(blank);
                }

                for (let date = first; date < next; date = moveDay(date, 1)) {
                    const key = dayKey(date);
                    const count = load.get(key);
                    const dueLabel = count && count.done < count.total ? pendingDateLabel(date, currentDay) : "";
                    const cell = element("button", "calendar-day");
                    cell.type = "button";
                    cell.dataset.day = key;
                    cell.tabIndex = key === selectedKey ? 0 : -1;
                    cell.setAttribute("aria-pressed", String(key === selectedKey));
                    if (key === todayKey) cell.setAttribute("aria-current", "date");
                    if (count) {
                        cell.dataset.level = count.done === count.total ? "done"
                            : dueLabel ? "overdue" : count.done > 0 ? "partial" : "scheduled";
                    }
                    const progressLabel = count
                        ? ", " + count.done + " trên " + count.total + " việc đã xong"
                        : ", không có việc";
                    cell.setAttribute("aria-label", fullDate.format(date) + progressLabel + (dueLabel ? ", " + dueLabel : ""));
                    if (dueLabel) cell.title = dueLabel;
                    cell.append(element("span", "calendar-day__number", String(date.getUTCDate())));
                    if (count) cell.append(element("span", "calendar-day__ratio", count.done + "/" + count.total));
                    fragment.append(cell);
                }
                grid.replaceChildren(fragment);
            }

            function renderItems(currentDay) {
                const start = mode === "week" ? weekStart(selected) : selected;
                const end = mode === "week" ? moveDay(start, 6) : selected;
                const shown = forPlan(plan.value).filter((item) => {
                    if (!includeDone.checked && item.done) return false;
                    if (mode === "unscheduled") return !item.date;
                    return item.date && item.date >= start && item.date <= end;
                });
                const fragment = document.createDocumentFragment();
                const period = mode === "week"
                    ? rangeLabel(start, end)
                    : mode === "day" ? formatter.format(selected) : "Chưa xếp lịch";
                shown.sort((left, right) => (left.date - right.date) || left.title.localeCompare(right.title, "vi"));

                for (const item of shown) {
                    const row = element("li", "insight-work-row");
                    const main = element("div");
                    const label = element("a", "item-title", item.title);
                    label.href = item.href;
                    const meta = [planNames.get(item.planId), item.date && formatter.format(item.date)].filter(Boolean);
                    main.append(label, element("p", "insight-caption", meta.join(" · ")));
                    const dueLabel = item.done ? "" : pendingDateLabel(item.date, currentDay);
                    const statusClass = item.done ? "insight-status--done" : dueLabel ? "insight-status--overdue" : "";
                    row.append(main, element(
                        "span",
                        statusClass ? "insight-status " + statusClass : "insight-status",
                        item.done ? "Đã xong" : dueLabel || "Chưa xong"
                    ));
                    fragment.append(row);
                }
                list.replaceChildren(fragment);
                summary.textContent = period + " · " + shown.length + " việc";
                empty.hidden = shown.length !== 0;
            }

            function render() {
                renderedDay = today();
                datePicker.valueAsDate = selected;
                for (const button of modes) {
                    button.setAttribute("aria-pressed", String(button.dataset.calendarMode === mode));
                }
                renderCalendar(renderedDay);
                renderItems(renderedDay);
            }

            function refreshCalendarDay() {
                if (today().getTime() === renderedDay.getTime()) return;
                const focusedInGrid = grid.contains(document.activeElement);
                render();
                if (focusedInGrid) grid.querySelector('[aria-pressed="true"]').focus({ preventScroll: true });
            }

            function selectDay(date, focusGrid) {
                selected = date;
                if (mode === "unscheduled") mode = "day";
                render();
                if (focusGrid) grid.querySelector('[aria-pressed="true"]').focus();
            }

            grid.addEventListener("click", (event) => {
                const button = event.target.closest("[data-day]");
                if (button) selectDay(new Date(button.dataset.day + "T00:00:00Z"), true);
            });
            grid.addEventListener("keydown", (event) => {
                const direction = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7 }[event.key];
                if (direction === undefined) return;
                event.preventDefault();
                selectDay(moveDay(selected, direction), true);
            });
            for (const button of modes) {
                button.addEventListener("click", () => {
                    mode = button.dataset.calendarMode;
                    render();
                });
            }
            for (const button of view.querySelectorAll("[data-calendar-shift]")) {
                button.addEventListener("click", () => selectDay(moveMonth(selected, Number(button.dataset.calendarShift))));
            }
            view.querySelector("[data-calendar-today]").addEventListener("click", () => selectDay(today()));
            datePicker.addEventListener("change", () => {
                const date = datePicker.valueAsDate;
                if (date) selectDay(date);
                else datePicker.valueAsDate = selected;
            });
            plan.addEventListener("change", render);
            includeDone.addEventListener("change", () => renderItems(renderedDay));
            render();
            view.hidden = false;
            return refreshCalendarDay;
        }

        function setupStats(view) {
            const plan = view.querySelector("[data-stats-goal]");
            const datePicker = view.querySelector("[data-stats-date]");
            const chart = view.querySelector("[data-stats-chart]");
            const period = view.querySelector("[data-stats-week]");
            const empty = view.querySelector("[data-stats-empty]");
            const metrics = view.querySelector("[data-stats-metrics]");
            let selected = today();
            const completed = leaves.filter((item) => item.done).length;
            const totals = { plans: planNames.size, total: leaves.length, completed, pending: leaves.length - completed };

            for (const metric of metrics.querySelectorAll("[data-metric]")) {
                metric.textContent = totals[metric.dataset.metric].toLocaleString("vi-VN");
            }

            function render() {
                const load = indexDates(forPlan(plan.value));
                const start = weekStart(selected);
                const currentDay = today();
                const days = weekdays.map((label, index) => {
                    const date = moveDay(start, index);
                    return { label, date, ...load.get(dayKey(date)) };
                });
                const peak = Math.max(...days.map((day) => day.total ?? 0));
                const fragment = document.createDocumentFragment();
                datePicker.valueAsDate = selected;
                period.textContent = rangeLabel(start, moveDay(start, 6));

                for (const day of days) {
                    const count = day.total ?? 0;
                    const done = day.done ?? 0;
                    const pending = count - done;
                    const dueLabel = pending > 0 ? pendingDateLabel(day.date, currentDay) : "";
                    const column = element("li", "weekly-chart__column");
                    if (dueLabel) {
                        column.dataset.level = "overdue";
                        column.title = pending + " việc " + dueLabel.toLocaleLowerCase("vi");
                    }
                    const measure = element("span", "weekly-chart__measure");
                    const bar = element("span", "weekly-chart__bar");
                    const complete = element("span", "weekly-chart__completed");
                    measure.setAttribute("aria-hidden", "true");
                    bar.style.height = count ? (count / peak * 100) + "%" : "0";
                    complete.style.height = count ? (done / count * 100) + "%" : "0";
                    bar.append(complete);
                    measure.append(bar);

                    const number = element("span", "weekly-chart__number", done + "/" + count);
                    number.setAttribute("aria-label", done + " trên " + count + " việc đã xong"
                        + (dueLabel ? ", " + column.title : ""));
                    const date = element("time", "weekly-chart__date", day.label);
                    date.dateTime = dayKey(day.date);
                    date.setAttribute("aria-label", fullDate.format(day.date));
                    column.append(number, measure, date);
                    fragment.append(column);
                }

                chart.replaceChildren(fragment);
                chart.hidden = peak === 0;
                empty.hidden = peak !== 0;
            }

            for (const button of view.querySelectorAll("[data-stats-shift]")) {
                button.addEventListener("click", () => {
                    selected = moveDay(selected, Number(button.dataset.statsShift) * 7);
                    render();
                });
            }
            view.querySelector("[data-stats-today]").addEventListener("click", () => {
                selected = today();
                render();
            });
            datePicker.addEventListener("change", () => {
                selected = datePicker.valueAsDate ?? today();
                render();
            });
            plan.addEventListener("change", render);
            render();
            metrics.hidden = false;
            view.querySelector("[data-stats-weekly]").hidden = false;
            return render;
        }

        const refreshCalendar = calendar ? setupCalendar(calendar) : null;
        const refreshStats = stats ? setupStats(stats) : null;
        let currentDayKey = dayKey(today());

        function refreshForNewDay() {
            const newDayKey = dayKey(today());
            if (newDayKey === currentDayKey) return;
            currentDayKey = newDayKey;
            refreshCalendar?.();
            refreshStats?.();
        }

        window.addEventListener("focus", refreshForNewDay);
        window.setInterval(refreshForNewDay, 60_000);
    }
}
