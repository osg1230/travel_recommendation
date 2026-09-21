// 좁은 화면에서 상단 메뉴를 접고 펼친다. 스크립트가 없으면 메뉴는 항상 보인다.
(function () {
    const header = document.querySelector('.site-header');
    const toggle = header && header.querySelector('.nav-toggle');
    if (!toggle) return;
    header.classList.add('nav-collapsible');
    toggle.hidden = false;
    const set = (open) => { header.classList.toggle('nav-open', open); toggle.setAttribute('aria-expanded', String(open)); };
    toggle.addEventListener('click', () => set(!header.classList.contains('nav-open')));
    document.addEventListener('keydown', (event) => { if (event.key === 'Escape') set(false); });
})();
