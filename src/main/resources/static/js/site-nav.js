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

// 같은 화면 안의 접힌 절(예: 일정 초안의 '여행 조건 확인 결과')로 가는 링크를 누르면 그 절을 펼친다.
document.addEventListener('click', (event) => {
    const link = event.target.closest('a[href^="#"]');
    const target = link && link.getAttribute('href').length > 1 && document.getElementById(link.getAttribute('href').slice(1));
    const details = target && (target.matches('details') ? target : target.querySelector('details'));
    if (details) details.open = true;
});
