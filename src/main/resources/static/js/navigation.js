const menuToggle = document.querySelector("[data-menu-toggle]");
const sidebar = document.getElementById("planner-sidebar");

if (menuToggle && sidebar) {
    const shell = sidebar.closest(".app-shell");

    function setMenuOpen(open) {
        menuToggle.setAttribute("aria-expanded", String(open));
        sidebar.classList.toggle("is-open", open);
    }

    menuToggle.addEventListener("click", () => {
        setMenuOpen(menuToggle.getAttribute("aria-expanded") !== "true");
    });

    sidebar.addEventListener("click", (event) => {
        if (event.target.closest("[data-workspace-nav]")
                && menuToggle.getClientRects().length > 0
                && menuToggle.getAttribute("aria-expanded") === "true") {
            setMenuOpen(false);
            menuToggle.focus();
        }
    });

    shell.addEventListener("keydown", (event) => {
        if (event.key === "Escape"
                && menuToggle.getClientRects().length > 0
                && menuToggle.getAttribute("aria-expanded") === "true") {
            setMenuOpen(false);
            menuToggle.focus();
        }
    });

    menuToggle.hidden = false;
    shell.classList.add("has-menu-toggle");
}
