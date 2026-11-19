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

// 출발일을 고르면 그 달의 계절을 자동으로 고른다. 3~5월 봄, 6~8월 여름, 9~11월 가을, 12~2월 겨울(서버 ConditionReviewService.seasonName과 같다).
// 사용자가 계절을 직접 바꾸면 그 뒤로는 자동으로 바꾸지 않는다.
const seasonInputs = document.querySelectorAll('input[name="season"]');
const seasonNote = document.querySelector('[data-season-note]');
let seasonPickedByUser = false;
function seasonName(month) {
    if (month >= 3 && month <= 5) return '봄';
    if (month >= 6 && month <= 8) return '여름';
    if (month >= 9 && month <= 11) return '가을';
    return '겨울';
}
function labelText(input) {
    return input.closest('label')?.querySelector('span')?.textContent.trim() ?? '';
}
function pickSeasonFromStartDate() {
    if (!startInput || !seasonNote || seasonInputs.length === 0) return;
    const start = startInput.value ? new Date(startInput.value + 'T00:00:00') : null;
    if (!start || Number.isNaN(start.getTime()) || seasonPickedByUser) {
        seasonNote.hidden = true;
        return;
    }
    const month = start.getMonth() + 1;
    const wanted = seasonName(month);
    const target = Array.from(seasonInputs).find(input => labelText(input) === wanted);
    if (!target) return;
    target.checked = true;
    seasonNote.textContent = `출발일이 ${month}월이라 '${wanted}'로 골랐어요. 다른 계절을 원하면 바꿔 주세요.`;
    seasonNote.hidden = false;
    updateSummary();
}
seasonInputs.forEach(input => input.addEventListener('change', () => {
    seasonPickedByUser = true;
    if (seasonNote) seasonNote.hidden = true;
}));
startInput?.addEventListener('input', pickSeasonFromStartDate);

// 고른 조건을 버튼 위에 한 줄로 모아 보여 준다. 조건을 바꿀 때마다 다시 만든다.
const summary = document.querySelector('[data-condition-summary]');
function checkedLabels(name) {
    return Array.from(document.querySelectorAll(`input[name="${name}"]:checked`))
        .map(labelText).filter(text => text && text !== '상관없음');
}
function durationText() {
    const days = Number(daysInput?.value);
    if (!Number.isInteger(days) || days < 1) return '';
    return days === 1 ? '당일' : `${days - 1}박 ${days}일`;
}
function startText() {
    const start = startInput?.value ? new Date(startInput.value + 'T00:00:00') : null;
    if (!start || Number.isNaN(start.getTime())) return '';
    return `${start.getMonth() + 1}월 ${start.getDate()}일 출발`;
}
function budgetText() {
    const budget = Number(document.getElementById('budget')?.value);
    return budget > 0 ? `예산 ${Math.round(budget).toLocaleString('ko-KR')}원` : '';
}
function updateSummary() {
    if (!summary) return;
    const keywordMode = document.querySelector('input[name="inputMode"]:checked')?.value === 'KEYWORD';
    summary.hidden = !keywordMode;
    if (!keywordMode) return;
    const parts = [
        ...checkedLabels('region'), durationText(), startText(), ...checkedLabels('season'), ...checkedLabels('companion'),
        checkedLabels('themes').join(', '), checkedLabels('moods').join(', '), ...checkedLabels('pace'),
        ...checkedLabels('transportation'), budgetText()
    ].filter(Boolean);
    summary.replaceChildren();
    if (parts.length === 0) {
        summary.append('아직 고른 조건이 없어요. 그대로 추천받아도 돼요.');
        return;
    }
    const label = document.createElement('strong');
    label.textContent = '지금 고른 조건: ';
    summary.append(label, parts.join(' · '), '. 정하지 않은 조건은 상관없음으로 추천해요.');
}
const conditionForm = document.querySelector('[data-analysis-form]');
if (conditionForm && summary) {
    conditionForm.addEventListener('input', updateSummary);
    conditionForm.addEventListener('change', updateSummary);
    pickSeasonFromStartDate();
    updateSummary();
}
