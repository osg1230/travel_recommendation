// 수정 요청 예시 버튼: 누르면 예시 문장을 입력 칸에 이어 붙인다. 바로 보내지 않아 사용자가 고친 뒤 보낼 수 있다.
(function () {
    const box = document.querySelector('[data-request-examples]');
    const field = document.getElementById('ai-request');
    if (!box || !field) return;
    box.hidden = false;
    box.querySelectorAll('[data-example]').forEach((button) => button.addEventListener('click', () => {
        const example = button.dataset.example;
        const current = field.value.trim();
        if (current.includes(example)) { field.focus(); return; }
        const next = current ? current + ' ' + example : example;
        // 300자를 넘기면 붙이지 않는다(서버도 같은 길이를 검사한다).
        if (next.length > field.maxLength) return;
        field.value = next;
        field.focus();
        field.setSelectionRange(next.length, next.length);
    }));
})();
