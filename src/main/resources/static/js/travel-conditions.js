// 입력 방법을 바꿔도 작성 중인 값은 유지한다. 제출할 때는 선택한 방법의 값만 보낸다.
const modeInputs = document.querySelectorAll('input[name="inputMode"]');
function showSelectedMode() {
    const mode = document.querySelector('input[name="inputMode"]:checked')?.value;
    document.querySelectorAll('[data-mode]').forEach(section => {
        const active = section.dataset.mode === mode;
        section.hidden = !active;
        section.querySelectorAll('input, select, textarea').forEach(field => field.disabled = !active);
    });
}
modeInputs.forEach(input => input.addEventListener('change', showSelectedMode));
showSelectedMode();
document.getElementById('error-summary')?.focus();
