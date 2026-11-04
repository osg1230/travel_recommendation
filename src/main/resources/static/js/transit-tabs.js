// 대중교통 안내의 버스·기차 탭. 누른 탭의 내용만 보여 준다(안내용이라 일정 시간은 바뀌지 않는다).
// 자바스크립트가 없으면 두 탭 내용이 모두 보인다. 처음에는 일정에 쓴 수단(data-first)의 탭을 연다.
document.addEventListener('DOMContentLoaded', function () {
    var tabs = document.querySelector('[data-transit-tabs]');
    if (!tabs) return;
    var buttons = tabs.querySelectorAll('[data-transit-tab]');
    var panels = document.querySelectorAll('[data-transit-panel]');

    function show(name) {
        buttons.forEach(function (button) {
            var selected = button.getAttribute('data-transit-tab') === name;
            button.setAttribute('aria-selected', selected ? 'true' : 'false');
            button.tabIndex = selected ? 0 : -1;
            if (selected) button.setAttribute('aria-current', 'true'); else button.removeAttribute('aria-current');
        });
        panels.forEach(function (panel) { panel.hidden = panel.getAttribute('data-transit-panel') !== name; });
    }

    buttons.forEach(function (button, index) {
        button.addEventListener('click', function () { show(button.getAttribute('data-transit-tab')); });
        // 탭 목록의 일반적인 키보드 사용법: 왼쪽·오른쪽 화살표로 옆 탭으로 옮긴다.
        button.addEventListener('keydown', function (event) {
            if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') return;
            var next = buttons[(index + (event.key === 'ArrowRight' ? 1 : buttons.length - 1)) % buttons.length];
            show(next.getAttribute('data-transit-tab'));
            next.focus();
        });
    });
    show(tabs.getAttribute('data-first') || 'bus');
});
