document.querySelector('[data-recommendation-form]')?.addEventListener('submit', async event => {
    event.preventDefault();
    const form = event.currentTarget;
    const button = form.querySelector('button');
    const status = form.querySelector('[data-submit-status]');
    button.disabled = true;
    status.textContent = '요청을 접수하고 있어요.';
    try {
        const response = await fetch(form.action, { method: 'POST', body: new FormData(form), headers: { Accept: 'application/json' } });
        if (!response.ok) {
            status.textContent = response.status === 409 ? '조건이 바뀌었거나 만료됐어요. 조건을 다시 확정해 주세요.' :
                response.status === 403 || response.status === 404 ? '요청이 만료되었거나 조회 권한이 없어요. 조건을 다시 입력해 주세요.' :
                response.status === 503 ? '추천 연결 설정을 확인해야 해요. 잠시 후 다시 이용해 주세요.' :
                '요청을 접수하지 못했어요. DB 연결과 서버 상태를 확인한 뒤 다시 시도해 주세요.';
            return;
        }
        const result = await response.json();
        window.location.assign(result.location);
    } catch { status.textContent = '연결이 끊겼어요. 다시 누르면 이미 접수된 요청을 확인해요.'; }
    finally { button.disabled = false; }
});

const job = document.querySelector('[data-job-pending="true"]');
if (job) {
    const deadline = Date.now() + 60000;
    const poll = async () => {
        try {
            const response = await fetch(job.dataset.statusUrl, { headers: { Accept: 'application/json' }, cache: 'no-store' });
            if (!response.ok) throw new Error();
            const result = await response.json();
            if (!result.pending) { window.location.reload(); return; }
            if (Date.now() >= deadline) {
                job.querySelector('[data-job-message]').textContent = '처리가 지연되고 있어요. 잠시 후 상태 새로고침으로 확인해 주세요.';
                return;
            }
            setTimeout(poll, 2000);
        } catch { job.querySelector('[data-job-message]').textContent = '상태를 확인하지 못했어요. 세션과 연결 상태를 확인하고 새로고침해 주세요.'; }
    };
    setTimeout(poll, 2000);
}
