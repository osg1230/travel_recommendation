// 일정 초안의 '일정 · 경비·가격 · 교통·주변 · 수정' 탭: 고른 묶음만 보여 줘 화면을 길게 내리지 않게 한다.
// 스크립트가 없으면 탭은 숨겨지고 모든 묶음이 그대로 이어서 보인다.
(function () {
    const bar = document.querySelector('[data-page-tabs]');
    if (!bar) return;
    const buttons = Array.from(bar.querySelectorAll('[data-page-tab]'));
    const panelOf = (name) => document.querySelector('[data-page-panel="' + name + '"]');

    // 내용이 없는 묶음(예: 자동차 여행에 주변 지역도 없을 때의 '교통·주변')은 탭도 숨긴다.
    buttons.forEach((button) => {
        const panel = panelOf(button.dataset.pageTab);
        if (!panel || panel.children.length === 0) button.hidden = true;
    });

    function select(name) {
        buttons.forEach((button) => {
            const selected = button.dataset.pageTab === name;
            button.setAttribute('aria-selected', String(selected));
            if (selected) button.setAttribute('aria-current', 'true'); else button.removeAttribute('aria-current');
            const panel = panelOf(button.dataset.pageTab);
            if (panel) panel.hidden = !selected;
        });
        // 숨어 있던 지도는 크기를 다시 재야 하므로 지도(trip-map.js)에 알린다.
        document.dispatchEvent(new CustomEvent('pagetab', { detail: name }));
    }

    // 주소(#prices-title 등)가 가리키는 곳이 들어 있는 묶음의 이름. 묶음 밖이면 null이다.
    function tabOfHash(hash) {
        const target = hash.length > 1 ? document.getElementById(decodeURIComponent(hash.slice(1))) : null;
        const panel = target ? target.closest('[data-page-panel]') : null;
        return panel ? panel.dataset.pagePanel : null;
    }

    buttons.forEach((button) => button.addEventListener('click', () => select(button.dataset.pageTab)));
    // 다른 묶음에 있는 절로 가는 링크('바로 가기' 등)는 그 묶음을 먼저 펼친다. 그래야 브라우저가 그 절로 이동할 수 있다.
    document.addEventListener('click', (event) => {
        const link = event.target.closest('a[href^="#"]');
        const name = link ? tabOfHash(link.getAttribute('href')) : null;
        if (name) select(name);
    });
    bar.hidden = false;
    // 가격 찾기·수정 요청 등을 마치고 돌아오면 주소가 그 절을 가리킨다. 그 묶음을 펼치고 그 절로 간다.
    const returned = tabOfHash(location.hash);
    select(returned || 'plan');
    if (returned && returned !== 'plan') document.getElementById(decodeURIComponent(location.hash.slice(1))).scrollIntoView();
})();
