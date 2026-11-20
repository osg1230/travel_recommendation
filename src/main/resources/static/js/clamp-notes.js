// 일정 초안·저장한 일정의 긴 설명(장소 설명·AI 준비 안내)은 두 줄만 보여 주고 '더 보기'를 누르면 펼친다. 일정표를 덜 내려도 되게 하려는 것이다.
// 스크립트가 없으면 예전처럼 전부 보인다. 숨은 날짜의 글은 길이를 잴 수 없으므로 일차 버튼(day-tabs.js)보다 먼저 실행한다.
(function () {
    document.querySelectorAll('[data-clamp]').forEach((note) => {
        note.classList.add('clamped');
        // 두 줄 안에 다 들어가는 글에는 버튼을 붙이지 않는다.
        if (note.scrollHeight <= note.clientHeight + 1) { note.classList.remove('clamped'); return; }
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'note-more';
        button.textContent = '더 보기';
        button.setAttribute('aria-expanded', 'false');
        button.addEventListener('click', () => {
            const folded = note.classList.toggle('clamped');
            button.textContent = folded ? '더 보기' : '접기';
            button.setAttribute('aria-expanded', String(!folded));
        });
        // 일정 항목에서는 '더 보기'를 아래 링크 줄(위치 보기·출처)에 함께 두어 항목 높이를 한 줄 줄인다.
        const actions = note.parentElement.querySelector(':scope > .item-actions > .place-links');
        if (actions) actions.prepend(button); else note.after(button);
    });
})();
