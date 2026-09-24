// 여러 날 일정의 '전체 · 1일차 · 2일차' 버튼: 고른 날의 일정만 보여 주고 지도(trip-map.js)에도 알린다.
// 처음에는 1일차만 보여 화면이 길어지지 않게 한다. 스크립트가 없으면 버튼은 숨겨지고 모든 날이 그대로 보인다.
(function () {
    const bar = document.querySelector('[data-day-tabs]');
    if (!bar) return;
    const sections = document.querySelectorAll('[data-day-section]');
    const buttons = bar.querySelectorAll('[data-day]');
    const anchor = document.createElement('div');
    bar.before(anchor);

    function select(day, scroll) {
        bar.dataset.selected = String(day);
        buttons.forEach((button) => button.setAttribute('aria-pressed', String(Number(button.dataset.day) === day)));
        sections.forEach((section) => { section.hidden = day !== 0 && Number(section.dataset.daySection) !== day; });
        document.dispatchEvent(new CustomEvent('tripday', { detail: day }));
        // 아래로 내려 읽다가 다른 날을 고르면 그날의 처음(지도)부터 보이게 올린다.
        if (scroll && anchor.getBoundingClientRect().top < 0) anchor.scrollIntoView({ behavior: 'smooth' });
    }

    buttons.forEach((button) => button.addEventListener('click', () => select(Number(button.dataset.day), true)));
    bar.hidden = false;
    select(1, false);
})();
