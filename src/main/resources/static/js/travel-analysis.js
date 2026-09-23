// 중복 클릭을 막고 처리 상태를 알린다. 서버에서도 세션별 중복 호출을 차단한다.
document.querySelector('[data-analysis-form]')?.addEventListener('submit', event => {
    const form = event.currentTarget;
    form.querySelector('button').disabled = true;
    form.setAttribute('aria-busy', 'true');
    status(form).hidden = false;
});
window.addEventListener('pageshow', () => {
    const form = document.querySelector('[data-analysis-form]');
    if (!form) return;
    form.querySelector('button').disabled = false;
    form.removeAttribute('aria-busy');
    status(form).hidden = true;
});
// 안내 문구는 폼 안에 두거나, 홈처럼 한 줄 입력 폼이면 폼 바로 아래에 둔다.
function status(form) {
    return form.querySelector('[data-analysis-status]') ?? document.querySelector('[data-analysis-status]');
}
