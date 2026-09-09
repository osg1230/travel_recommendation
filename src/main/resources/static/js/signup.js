const signupForm = document.querySelector('#signup-form');
const submitButton = signupForm.querySelector('button[type="submit"]');
const submitStatus = document.querySelector('#submit-status');

signupForm.addEventListener('submit', () => {
    submitButton.disabled = true;
    submitButton.textContent = '가입 처리 중…';
    submitStatus.textContent = '잠시만 기다려 주세요.';
});

// 뒤로 가기로 화면이 복원되어도 다시 제출할 수 있게 한다.
window.addEventListener('pageshow', () => {
    submitButton.disabled = false;
    submitButton.textContent = '가입하기';
    submitStatus.textContent = '';
});
