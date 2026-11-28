(() => {
    const generateForm = document.querySelector('[data-ai-generate]');
    const confirmForm = document.querySelector('[data-ai-confirm]');
    if (generateForm) {
        for (const name of ['goal', 'level']) {
            const input = generateForm.elements.namedItem(name);
            const checkText = () => {
                input.setCustomValidity(input.value && !input.value.trim() ? 'Vui lòng nhập nội dung.' : '');
            };
            checkText();
            input.addEventListener('input', checkText);
        }
    }

    // Avoid sending a second generation request or confirming the same draft twice.
    for (const form of [generateForm, confirmForm].filter(Boolean)) {
        const button = form.querySelector('button[type="submit"]');
        const status = form.querySelector('[data-ai-pending]');
        form.addEventListener('submit', event => {
            if (button.disabled) {
                event.preventDefault();
                return;
            }
            button.disabled = true;
            if (status) status.hidden = false;
        });
        window.addEventListener('pageshow', event => {
            if (!event.persisted) return;
            button.disabled = false;
            if (status) status.hidden = true;
        });
    }

    const form = document.querySelector('[data-ai-draft]');
    if (!form || form.dataset.editError === 'true') return;

    const source = form.elements.namedItem('json');
    const original = source.defaultValue;
    let draft;
    try {
        draft = JSON.parse(original);
    } catch {
        return; // Keep the server-rendered JSON editor available.
    }

    const outline = form.querySelector('#ai-outline');
    const edit = form.querySelector('[data-ai-edit]');
    const advanced = form.querySelector('[data-ai-advanced]');
    const update = form.querySelector('[data-ai-update]');
    const confirmButton = confirmForm.querySelector('button[type="submit"]');
    let editing = false;
    const kinds = {plan: 'Mục tiêu', subPlans: 'Mục tiêu nhỏ', tasks: 'Task', subTasks: 'Sub Task'};

    function element(tag, className, text) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text) node.textContent = text;
        return node;
    }

    function displayDate(value) {
        return value.replace(/^(\d{4})-(\d{2})-(\d{2})$/, '$3/$2/$1');
    }

    function showContent(node, kind, container) {
        const description = kind === 'plan' ? node.goal : node.description;
        if (description) container.append(element('p', 'ai-node-description', description));
        const dates = [];
        if (kind === 'subTasks') {
            dates.push(node.scheduledDate ? 'Dự kiến ' + displayDate(node.scheduledDate) : 'Chưa xếp lịch');
        } else {
            if (node.startDate) dates.push('Bắt đầu ' + displayDate(node.startDate));
            if (node.endDate) dates.push('Kết thúc ' + displayDate(node.endDate));
        }
        if (dates.length) container.append(element('p', 'ai-node-date', dates.join(' · ')));
    }

    function field(node, key, label, type) {
        const wrapper = element('label', '', label);
        const input = element(type === 'textarea' ? 'textarea' : 'input');
        if (type === 'textarea') input.rows = 2;
        else input.type = type;
        if (key === 'title') input.required = true;
        if (type === 'date') {
            input.min = '0001-01-01';
            input.max = '9999-12-31';
        }
        input.value = node[key] ?? '';
        input.addEventListener('input', () => {
            node[key] = input.value || null;
            if (type === 'date') return;
            const limit = key === 'title' ? 255 : 2000;
            let error = '';
            if (key === 'title' && !input.value.trim()) error = 'Vui lòng nhập tên có nội dung.';
            else if (Array.from(input.value).length > limit) error = 'Vui lòng nhập tối đa ' + limit + ' ký tự.';
            input.setCustomValidity(error);
        });
        wrapper.append(input);
        return wrapper;
    }

    function showEditor(node, kind, container) {
        const fields = element('div', 'ai-node-fields');
        const descriptionKey = kind === 'plan' ? 'goal' : 'description';
        fields.append(field(node, 'title', 'Tên ' + kinds[kind].toLowerCase(), 'text'),
            field(node, descriptionKey, 'Mô tả', 'textarea'));
        if (kind === 'subTasks') {
            fields.append(field(node, 'scheduledDate', 'Ngày dự kiến', 'date'));
        } else {
            const dates = element('div', 'ai-field-pair');
            const start = field(node, 'startDate', 'Ngày bắt đầu', 'date');
            const end = field(node, 'endDate', 'Ngày kết thúc', 'date');
            const startInput = start.querySelector('input');
            const endInput = end.querySelector('input');
            const checkDates = () => {
                const reversed = startInput.value && endInput.value &&
                    startInput.valueAsNumber > endInput.valueAsNumber;
                endInput.setCustomValidity(reversed ? 'Ngày kết thúc phải bằng hoặc sau ngày bắt đầu.' : '');
                endInput.min = startInput.value || '0001-01-01';
            };
            checkDates();
            startInput.addEventListener('input', checkDates);
            endInput.addEventListener('input', checkDates);
            dates.append(start, end);
            fields.append(dates);
        }
        container.append(fields);
    }

    function addChildren(parent, container, depth) {
        const list = element('ul', 'ai-node-list');
        for (const kind of ['subPlans', 'tasks', 'subTasks']) {
            for (const node of parent[kind] || []) {
                const item = element('li');
                const expandable = editing || kind !== 'subTasks';
                const branch = expandable ? element('details') : item;
                const heading = element(expandable ? 'summary' : 'div', 'ai-node-heading');
                heading.append(element('span', 'ai-node-kind', kinds[kind]), document.createTextNode(node.title));
                branch.append(heading);
                if (expandable) {
                    branch.open = !editing && depth === 0;
                    item.append(branch);
                }
                if (editing) showEditor(node, kind, branch);
                else showContent(node, kind, branch);
                // During editing a collapsed form should not hide the forms for its children.
                addChildren(node, editing ? item : branch, depth + 1);
                list.append(item);
            }
        }
        if (list.childElementCount) container.append(list);
    }

    function render() {
        outline.replaceChildren();
        if (editing) showEditor(draft, 'plan', outline);
        else showContent(draft, 'plan', outline);
        addChildren(draft, outline, 0);
    }

    function updateActions() {
        const rawChanged = source.value !== original;
        advanced.hidden = editing;
        edit.disabled = !editing && (advanced.open || rawChanged);
        edit.textContent = editing ? 'Hủy chỉnh sửa' : 'Sửa nội dung';
        edit.setAttribute('aria-pressed', String(editing));
        confirmButton.disabled = editing || rawChanged;
        update.hidden = !editing && !advanced.open && !rawChanged;
    }

    edit.addEventListener('click', () => {
        editing = !editing;
        draft = JSON.parse(original);
        render();
        updateActions();
        if (editing) outline.querySelector('input').focus();
    });
    advanced.addEventListener('toggle', updateActions);
    source.addEventListener('input', updateActions);
    const validation = form.querySelector('[data-ai-validation]');
    form.addEventListener('input', () => { validation.hidden = true; });
    edit.addEventListener('click', () => { validation.hidden = true; });
    form.addEventListener('submit', event => {
        if (!editing) return;
        const json = JSON.stringify(draft);
        if (json.length > source.maxLength) {
            event.preventDefault();
            validation.textContent = 'Bản nháp quá dài. Hãy rút gọn phần mô tả trước khi cập nhật.';
            validation.hidden = false;
            return;
        }
        source.value = json;
    });
    window.addEventListener('pageshow', updateActions);

    render();
    outline.hidden = false;
    edit.hidden = false;
    advanced.open = source.value !== original;
    updateActions();
})();
