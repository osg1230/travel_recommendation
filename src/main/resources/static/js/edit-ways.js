// 일정 초안 '수정' 탭의 '문장으로 요청 · 장소별로 고치기 · 전체 조건 바꾸기': 고른 방법의 칸만 보여 준다.
// 스크립트가 없으면 고르는 줄은 숨겨지고 세 칸이 이어서 보인다.
(function () {
    const root = document.querySelector('[data-edit-ways]');
    if (!root) return;
    const bar = root.querySelector('[data-edit-way-tabs]');
    const buttons = Array.from(bar.querySelectorAll('[data-edit-way]'));
    // 고를 방법이 하나뿐이면(관광지 후보를 확인하지 못해 문장 요청만 남았을 때) 고르는 줄을 보여 주지 않는다.
    if (buttons.length < 2) return;

    function select(name) {
        buttons.forEach((button) => {
            const selected = button.dataset.editWay === name;
            button.setAttribute('aria-selected', String(selected));
            const panel = root.querySelector('[data-edit-panel="' + button.dataset.editWay + '"]');
            if (panel) panel.hidden = !selected;
        });
    }

    buttons.forEach((button) => button.addEventListener('click', () => select(button.dataset.editWay)));
    bar.hidden = false;
    // 수정안을 만들지 못하고 돌아왔으면 보내던 칸(data-first)을 다시 연다. 그 칸이 없으면 문장 요청을 연다.
    const first = buttons.some((button) => button.dataset.editWay === root.dataset.first) ? root.dataset.first : 'ai';
    select(first);
})();
