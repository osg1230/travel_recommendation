// 처리에 시간이 걸리는 동기 폼(참고 가격·버스·비행기·AI 수정안·저장 등)의 중복 제출 방지.
// data-submit-loading 속성이 있는 폼은 제출하는 순간 버튼을 잠그고 속성의 문구(예: '참고 가격 찾는 중…')로 바꿔,
// 응답을 기다리는 동안 같은 요청이 두 번 가지 않게 한다. 응답이 오면 페이지가 새로 그려지므로 따로 풀지 않는다.
document.addEventListener('DOMContentLoaded', function () {
    var forms = document.querySelectorAll('form[data-submit-loading]');
    forms.forEach(function (form) {
        form.addEventListener('submit', function (event) {
            if (form.dataset.submitting === 'true') {
                event.preventDefault();
                return;
            }
            form.dataset.submitting = 'true';
            form.setAttribute('aria-busy', 'true');
            // 버튼이 여러 개인 폼(장소 교체 이유 고르기)도 있어 실제로 누른 버튼을 잠근다.
            var button = event.submitter || form.querySelector('button[type=submit]');
            if (!button) return;
            // 잠긴 버튼의 name·value는 전송에서 빠지므로(예: reason=TASTE) 숨은 칸에 옮겨 담아 함께 보낸다.
            if (button.name) {
                var copy = document.createElement('input');
                copy.type = 'hidden';
                copy.name = button.name;
                copy.value = button.value;
                copy.dataset.submitCopy = 'true';
                form.appendChild(copy);
            }
            button.dataset.originalText = button.textContent;
            button.textContent = form.dataset.submitLoading;
            button.disabled = true;
        });
    });
    // 뒤로 가기로 돌아오면 브라우저가 잠긴 버튼을 그대로 복원할 수 있으므로 다시 누를 수 있게 되돌린다.
    window.addEventListener('pageshow', function (event) {
        if (!event.persisted) return;
        forms.forEach(function (form) {
            delete form.dataset.submitting;
            form.removeAttribute('aria-busy');
            // 남겨 두면 다른 버튼을 눌렀을 때 값이 두 번 가므로 옮겨 담은 숨은 칸도 지운다.
            form.querySelectorAll('input[data-submit-copy]').forEach(function (copy) { copy.remove(); });
            form.querySelectorAll('button[data-original-text]').forEach(function (button) {
                button.textContent = button.dataset.originalText;
                delete button.dataset.originalText;
                button.disabled = false;
            });
        });
    });
});
