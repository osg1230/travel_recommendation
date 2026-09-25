// 집 출발지 '내 위치로 찾기': 사용자가 누를 때만 브라우저 위치 권한을 요청하고, 좌표를 숨은 칸에 담는다.
// 좌표는 제출할 때 서버에서 여행지까지 이동시간과 시·구 이름을 찾는 데만 쓰이며 저장하지 않는다.
document.addEventListener('DOMContentLoaded', function () {
    var section = document.querySelector('[data-home-trip]');
    if (!section) return;
    var locate = section.querySelector('[data-home-locate]');
    var status = section.querySelector('[data-home-status]');
    var origin = section.querySelector('input[name=origin]');
    var lon = section.querySelector('input[name=originLongitude]');
    var lat = section.querySelector('input[name=originLatitude]');
    if (!('geolocation' in navigator)) {
        locate.disabled = true;
        status.textContent = '이 브라우저에서는 위치를 확인할 수 없어요. 출발지를 직접 적어 주세요.';
        return;
    }
    locate.addEventListener('click', function () {
        locate.disabled = true;
        status.textContent = '위치를 확인하는 중이에요…';
        navigator.geolocation.getCurrentPosition(function (position) {
            // 이동시간 계산에는 동네 수준이면 충분해 소수 셋째 자리(약 100m)까지만 보낸다.
            lon.value = position.coords.longitude.toFixed(3);
            lat.value = position.coords.latitude.toFixed(3);
            lon.disabled = false; lat.disabled = false;
            origin.value = '';
            origin.placeholder = '현재 위치로 정했어요';
            locate.disabled = false;
            status.textContent = '현재 위치를 출발지로 정했어요. 일정을 만들 때 시·구 이름과 이동시간을 확인해요. 다른 곳에서 출발하면 출발지를 적어 주세요.';
        }, function () {
            locate.disabled = false;
            status.textContent = '위치를 확인하지 못했어요(권한 거부 또는 시간 초과). 출발지를 직접 적어 주세요.';
        }, { enableHighAccuracy: false, timeout: 10000, maximumAge: 600000 });
    });
    // 출발지를 직접 적으면 앞서 찾은 현재 위치는 쓰지 않는다.
    origin.addEventListener('input', function () {
        if (origin.value.trim() === '') return;
        lon.disabled = true; lat.disabled = true; lon.value = ''; lat.value = '';
    });
});
