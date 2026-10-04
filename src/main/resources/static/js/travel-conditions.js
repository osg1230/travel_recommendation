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

// 여행 일수 빠른 선택: 버튼을 누르면 일수 입력칸에 값을 넣는다. 같은 버튼을 다시 누르면 비운다.
// 입력칸 값과 같은 일수의 버튼은 선택된 모습으로 보여 준다.
const daysInput = document.getElementById('durationDays');
const dayButtons = document.querySelectorAll('[data-days]');
function markDayButton() {
    dayButtons.forEach(button => button.setAttribute('aria-pressed', String(button.dataset.days === daysInput.value)));
}
dayButtons.forEach(button => button.addEventListener('click', () => {
    daysInput.value = button.dataset.days === daysInput.value ? '' : button.dataset.days;
    markDayButton();
}));
// 출발일과 일수를 고르면 마지막 날을 계산해 안내한다. 실제 값은 서버(TravelConditionValidator)가 같은 방식으로 계산한다.
const startInput = document.getElementById('startDate');
const endPreview = document.querySelector('[data-end-preview]');
function showEndDate() {
    if (!startInput || !endPreview) return;
    const last = startInput.value ? new Date(startInput.value + 'T00:00:00') : null;
    const days = Number(daysInput.value);
    if (!last || Number.isNaN(last.getTime())) {
        endPreview.textContent = '마지막 날은 출발일과 여행 일수로 자동으로 정해져요.';
    } else if (!Number.isInteger(days) || days < 1) {
        endPreview.textContent = '위에서 여행 일수도 골라 주세요. 마지막 날을 계산하는 데 필요해요.';
    } else if (days === 1) {
        endPreview.textContent = '당일 여행이에요.';
    } else {
        last.setDate(last.getDate() + days - 1);
        const lastText = last.toLocaleDateString('ko-KR', { month: 'long', day: 'numeric', weekday: 'short' });
        endPreview.textContent = `${lastText}에 돌아오는 ${days - 1}박 ${days}일 일정이에요.`;
    }
}
if (daysInput) {
    daysInput.addEventListener('input', () => { markDayButton(); showEndDate(); });
    dayButtons.forEach(button => button.addEventListener('click', showEndDate));
    startInput?.addEventListener('input', showEndDate);
    markDayButton();
    showEndDate();
}
