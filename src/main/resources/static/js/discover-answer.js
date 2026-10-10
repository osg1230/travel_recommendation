// 취향 탐색: 그림(또는 상관없음·건너뛰기)을 누르면 선택 표시를 잠깐 보여 준 뒤 바로 다음 질문으로 넘어간다.
// 이미 고른 답을 다시 눌러도 넘어가도록 click을 쓰고, 키보드로 고른 경우(change)도 같이 받는다.
// 답을 고르면 바로 제출하므로 '다음 질문' 버튼은 숨긴다. 스크립트가 없으면 버튼이 보이고 버튼으로 제출한다.
(function () {
    const form = document.querySelector('[data-discover-form]');
    if (!form) return;
    const nextButton = form.querySelector('[data-next-button]');
    if (nextButton) nextButton.hidden = true;
    let sent = false;
    function submitSoon() {
        if (sent) return;
        sent = true;
        // 고른 답이 실제로 체크된 경우에만 보낸다. 아니면 다시 누를 수 있게 둔다.
        setTimeout(() => {
            if (form.checkValidity()) form.requestSubmit();
            else sent = false;
        }, 250);
    }
    form.querySelectorAll('input[name="answer"]').forEach(input => {
        input.addEventListener('click', submitSoon);
        input.addEventListener('change', submitSoon);
    });
})();
