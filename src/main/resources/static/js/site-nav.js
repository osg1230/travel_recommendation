// 상단 메뉴가 서비스 이름 옆 한 줄에 들어가지 않을 때만 '메뉴' 버튼으로 접는다.
// 로그인 여부·관리자 메뉴·아이디 길이에 따라 메뉴 폭이 달라서 고정된 화면 폭 대신 실제로 넘치는지 잰다.
// 스크립트가 없으면 메뉴는 항상 보인다.
(function () {
    const header = document.querySelector('.site-header');
    const toggle = header && header.querySelector('.nav-toggle');
    if (!toggle) return;
    const brand = header.querySelector('.brand');
    const nav = header.querySelector('.site-nav');
    toggle.hidden = false;
    const set = (open) => { header.classList.toggle('nav-open', open); toggle.setAttribute('aria-expanded', String(open)); };

    // 메뉴를 잠시 펼친 상태로 두고, 다음 줄로 밀려났거나 메뉴 자체가 두 줄이 되면 접는다.
    // 펼친 상태(세로 목록)로 재면 항상 넘치는 것으로 보이므로 잴 때는 펼침도 잠시 해제한다.
    const fit = () => {
        const wasOpen = header.classList.contains('nav-open');
        header.classList.remove('nav-collapsed', 'nav-open');
        const first = nav.firstElementChild;
        const wrapped = nav.getBoundingClientRect().top >= brand.getBoundingClientRect().bottom - 4
            || (first && nav.offsetHeight > first.offsetHeight * 1.5);
        header.classList.toggle('nav-collapsed', wrapped);
        set(wrapped && wasOpen);
    };

    toggle.addEventListener('click', () => set(!header.classList.contains('nav-open')));
    document.addEventListener('keydown', (event) => { if (event.key === 'Escape') set(false); });
    window.addEventListener('resize', fit);
    // 웹폰트가 늦게 적용되면 글자 폭이 바뀌므로 한 번 더 잰다.
    if (document.fonts) document.fonts.ready.then(fit);
    fit();
})();

// 같은 화면 안의 접힌 절(예: 일정 초안의 '여행 조건 확인 결과')로 가는 링크를 누르면 그 절을 펼친다.
document.addEventListener('click', (event) => {
    const link = event.target.closest('a[href^="#"]');
    const target = link && link.getAttribute('href').length > 1 && document.getElementById(link.getAttribute('href').slice(1));
    const details = target && (target.matches('details') ? target : target.querySelector('details'));
    if (details) details.open = true;
});
