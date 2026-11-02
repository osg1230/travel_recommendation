// 여러 날 일정의 '전체 · 1일차 · 2일차' 버튼: 고른 날의 일정만 보여 주고 지도(trip-map.js)에도 알린다.
// 처음에는 1일차만 보여 화면이 길어지지 않게 한다. 스크립트가 없으면 버튼은 숨겨지고 모든 날이 그대로 보인다.
(function () {
    const bar = document.querySelector('[data-day-tabs]');
    if (!bar) return;
    const sections = document.querySelectorAll('[data-day-section]');
    const buttons = bar.querySelectorAll('[data-day]');
    // 버튼 줄 바로 위에 두는 스크롤 기준점. 일정 초안의 격자 배치에서 칸을 차지하지 않게 CSS에서 이름으로 다룬다.
    const anchor = document.createElement('div');
    anchor.className = 'day-tabs-anchor';
    bar.before(anchor);

    function select(day, scroll) {
        bar.dataset.selected = String(day);
        buttons.forEach((button) => button.setAttribute('aria-pressed', String(Number(button.dataset.day) === day)));
        sections.forEach((section) => { section.hidden = day !== 0 && Number(section.dataset.daySection) !== day; });
        document.dispatchEvent(new CustomEvent('tripday', { detail: day }));
        // 아래로 내려 읽다가 다른 날을 고르면 그날의 처음(지도)부터 보이게 올린다.
        if (scroll && anchor.getBoundingClientRect().top < 0) anchor.scrollIntoView({ behavior: 'smooth' });
    }

    // 일정 항목 주소(#stop-일차-순번)의 일차. 항목 주소가 아니면 0이다.
    function dayOfStop(hash) {
        const match = /^#stop-(\d+)-/.exec(hash);
        return match ? Number(match[1]) : 0;
    }

    buttons.forEach((button) => button.addEventListener('click', () => select(Number(button.dataset.day), true)));
    // 숨은 날의 항목으로 가는 링크(식사 안내 등)는 그날을 먼저 펼친다. 그래야 브라우저가 그 항목으로 이동할 수 있다.
    document.addEventListener('click', (event) => {
        const link = event.target.closest('a[href^="#stop-"]');
        if (link && dayOfStop(link.getAttribute('href'))) select(dayOfStop(link.getAttribute('href')), false);
    });
    bar.hidden = false;
    // 즐겨찾기·평가를 마치고 돌아오면 주소가 그 항목을 가리킨다. 보던 날을 펼치고 그 항목으로 간다.
    const returnedDay = dayOfStop(location.hash);
    select(returnedDay || 1, false);
    if (returnedDay) document.querySelector(location.hash)?.scrollIntoView();
})();
