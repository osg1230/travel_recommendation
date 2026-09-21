// '내 위치로 찾기': 사용자가 누를 때만 브라우저 위치 권한을 요청하고, 좌표를 폼에 담아 한 번 제출한다.
// 좌표는 서버에서 도시 이름을 찾는 데만 쓰이며 화면이나 저장소에 남기지 않는다.
document.addEventListener('DOMContentLoaded', function () {
    var form = document.querySelector('[data-bus-guide]');
    if (!form) return;
    var locate = form.querySelector('[data-bus-locate]');
    var status = form.querySelector('[data-bus-status]');
    if (!locate) return;
    if (!('geolocation' in navigator)) {
        locate.disabled = true;
        status.textContent = '이 브라우저에서는 위치를 확인할 수 없어요. 출발 도시를 직접 적어 주세요.';
        return;
    }
    locate.addEventListener('click', function () {
        locate.disabled = true;
        status.textContent = '위치를 확인하는 중이에요…';
        navigator.geolocation.getCurrentPosition(function (position) {
            var lon = form.querySelector('input[name=longitude]');
            var lat = form.querySelector('input[name=latitude]');
            // 도시만 찾으면 되므로 소수 셋째 자리(약 100m)까지만 보낸다.
            lon.value = position.coords.longitude.toFixed(3);
            lat.value = position.coords.latitude.toFixed(3);
            lon.disabled = false; lat.disabled = false;
            form.submit();
        }, function () {
            locate.disabled = false;
            status.textContent = '위치를 확인하지 못했어요(권한 거부 또는 시간 초과). 출발 도시를 직접 적어 주세요.';
        }, { enableHighAccuracy: false, timeout: 10000, maximumAge: 600000 });
    });
});
