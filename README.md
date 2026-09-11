# travel_recommendation

설계 자료와 이전 버전의 위치는 [문서 안내](docs/README.md), 현재 작업 상태는 [진행 문서](docs/progress.md)에서 확인합니다.

AI 국내 여행 일정 추천 서비스의 Spring Boot 프로젝트입니다. 현재 기본 확인 화면, 회원가입, 로그인·로그아웃, 사용자 정보 조회·수정과 여행 조건 입력·확인을 구현했습니다. AI 분석과 실제 여행 추천 기능은 이후 단계입니다.

현재 `dbo.users`, 회원가입 DTO·Service·Controller·화면과 BCrypt 저장·CSRF 보호를 구현했습니다. SQL Server 프로필로 실행한 뒤 `/signup`에서 가입할 수 있습니다. DB 없는 기본 실행에서는 회원가입을 제공하지 않습니다. 로그인·로그아웃과 가입 완료 후 로그인 화면 연결도 구현했습니다. 사용자 정보 조회·수정과 여행 조건 입력도 구현했으며 다음 단계는 키워드 DB 연결과 자연어 분석입니다. 회원 기능의 실제 SQL Server 통합 테스트는 데스크탑에서 검증했으며, 이번 여행 입력 단계는 Java 21에서 DB 없는 기본·웹 테스트를 확인했습니다.

## Java 21 선택

이 PC에는 Microsoft OpenJDK 21.0.12.1을 사용자 `.jdks`에 설치했습니다. 기존 Java 24와 전역 설정은 유지하며 프로젝트 터미널에서 다음을 먼저 실행합니다.

```powershell
. ./scripts/use-java21.ps1
# 다른 위치에 설치한 PC에서는:
# . ./scripts/use-java21.ps1 -JdkPath 'C:\path\to\jdk-21'
.\mvnw.cmd -version
```

다운로드는 [Microsoft 공식 OpenJDK 배포](https://learn.microsoft.com/en-us/java/openjdk/download)를 사용하고 SHA-256을 검증했습니다. PC별 JDK와 JDBC 인증 DLL은 Git으로 공유하지 않습니다.

## 회원 테이블과 테스트

새 DB에서 [회원 테이블 생성 SQL](docs/database/sql/001_create_users.sql)을 검토한 뒤 수동으로 실행합니다. 이미 테이블이 있으면 SQL은 중단하며 삭제·재생성하지 않습니다. Hibernate 자동 DDL은 계속 꺼져 있습니다. 현재 기준은 [DB 정의서 1절](docs/database/DB_테이블_정의서.md)입니다.

기본 `clean verify`는 DB 없이 실행되며 DB 통합 테스트는 건너뜁니다. 실제 SQL Server와 인증 DLL이 준비된 PC에서 다음과 같이 별도로 실행합니다.

```powershell
. ./scripts/use-java21.ps1
$env:RUN_SQLSERVER_TESTS = 'true'
$jdbcAuthPath = Join-Path $env:USERPROFILE '.m2\repository\com\microsoft\sqlserver\mssql-jdbc_auth\13.4.0.x64'
try {
    .\mvnw.cmd test '-Dtest=UserRepositoryIntegrationTests,SignupIntegrationTests,LoginIntegrationTests,ProfileIntegrationTests' "-DargLine=-Djava.library.path=$jdbcAuthPath"
} finally {
    Remove-Item Env:RUN_SQLSERVER_TESTS
}
```

테스트는 실제 스키마를 validate하고 한글 저장·수정, 아이디·닉네임 중복 차단, 탈퇴 회원 닉네임 제외, 회원가입 요청·비밀번호 해시·일반 회원 권한 저장을 확인합니다. 테스트 행은 롤백하지만 SQL Server IDENTITY 번호는 소비될 수 있습니다.

## 기술 구성

- Java 21, Spring Boot 4.1.1 (정식 안정 버전), Maven Wrapper 3.3.4 / Maven 3.9.16, Jar
- Group: `com.travel`, Artifact: `travel_recommendation`
- 기본 패키지: `com.travel.travel_recommendation`
- Spring Web MVC, Thymeleaf, Spring Security, Spring Data JPA, Validation, Lombok, SQL Server JDBC Driver, DevTools
- 의존성 버전은 Spring Boot가 관리합니다. Spring Boot 4에서는 Web MVC용 `spring-boot-starter-webmvc`를 사용합니다.

## 기본 실행 (DB 불필요)

JDK 21을 설치하고 `JAVA_HOME`을 JDK 21 경로로 지정합니다. 최초 빌드 시 Maven 및 의존성 다운로드를 위한 인터넷 연결이 필요하며 별도 Maven 설치는 필요하지 않습니다.

Windows PowerShell에서 프로젝트 루트 기준:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd clean verify
.\mvnw.cmd spring-boot:run
```

macOS/Linux에서는 `JAVA_HOME`을 JDK 21로 지정하고 `sh ./mvnw clean verify`, `sh ./mvnw spring-boot:run`을 실행합니다.

[첫 화면](http://localhost:8080/)에서 프로젝트명과 정상 실행 문구를 확인합니다. 서버는 `Ctrl+C`로 종료합니다.

Jar 실행도 가능합니다:

```powershell
java -jar target/travel_recommendation-0.0.1.jar
```

두 SQL Server 프로필이 모두 없으면 DataSource/JPA 자동 구성을 제외하므로 DB 접속 정보를 요구하지 않습니다. H2 등 대체 DB는 포함하지 않습니다.

## SQL Server 프로필

### 현재 PC의 Windows 인증

인스턴스는 `SQLEXPRESS`, DB 이름은 `travel_recommendation`이다. 로컬 TCP 포트 1433을 확인했으며 Windows 인증으로 접속한다. 다른 PC에서는 같은 포트라고 가정하지 말고 SQL Server 설정을 확인한다.

`sqlserver-windows` 프로필은 Java를 실행한 Windows 계정을 사용한다. `sqlserver` 프로필과 동시에 활성화하지 않는다. 기본 URL은 로컬 개발용으로 `integratedSecurity=true`, `encrypt=true`, `trustServerCertificate=true`를 사용한다. 신뢰할 수 있는 인증서가 있는 서버에서는 `DB_URL`을 지정해 `trustServerCertificate=false`를 사용한다.

64비트 Windows 및 현재 JDBC 13.4.0 기준 실행:

```powershell
.\mvnw.cmd dependency:get "-Dartifact=com.microsoft.sqlserver:mssql-jdbc_auth:13.4.0.x64:dll" "-Dtransitive=false"
.\mvnw.cmd clean verify
$jdbcAuthPath = Join-Path $env:USERPROFILE '.m2\repository\com\microsoft\sqlserver\mssql-jdbc_auth\13.4.0.x64'
java "-Djava.library.path=$jdbcAuthPath" -jar target/travel_recommendation-0.0.1.jar --spring.profiles.active=sqlserver-windows
```

Maven 저장소 위치를 변경한 PC에서는 `jdbcAuthPath`도 실제 경로에 맞춘다. JDBC 버전이나 Java 아키텍처가 바뀌면 인증 DLL도 맞춰야 한다. DLL과 비밀번호는 Git에 저장하지 않는다. Windows 인증용 네이티브 라이브러리 설정은 [Microsoft JDBC 문서](https://learn.microsoft.com/en-us/sql/connect/jdbc/building-the-connection-url?view=sql-server-ver17)를 따른다.

### SQL 로그인 또는 다른 서버

접속 가능한 SQL Server와 기존 데이터베이스를 별도로 준비하고 다음 환경 변수를 설정합니다.

| 환경 변수 | 용도 |
| --- | --- |
| `DB_URL` | 전체 JDBC URL: `jdbc:sqlserver://<host>:1433;databaseName=<database>;encrypt=true;trustServerCertificate=false` |
| `DB_USERNAME` | DB 사용자명 |
| `DB_PASSWORD` | DB 비밀번호 |

실제 값은 개인 실행 환경이나 비밀 관리 도구에서 주입하고 소스 파일에 저장하지 않습니다. `.env`는 Git에서 제외되지만 Spring Boot가 자동으로 읽지는 않습니다. 위 URL은 형식 예시이므로 실제 호스트/DB로 바꿔야 하며 서버 인증서가 JVM에서 신뢰되어야 합니다.

환경 변수를 주입한 뒤 다음 중 하나로 실행합니다:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=sqlserver"
# 또는 빌드된 Jar 사용
java -jar target/travel_recommendation-0.0.1.jar --spring.profiles.active=sqlserver
```

`SPRING_PROFILES_ACTIVE=sqlserver` 환경 변수로도 프로필을 선택할 수 있습니다. 기본 실행으로 돌아가려면 해당 설정을 해제합니다. 이 프로필은 DB 설정과 접속 가능한 서버가 필요합니다.

공통 설정의 `spring.jpa.hibernate.ddl-auto=none`, `spring.sql.init.mode=never`로 자동 테이블 생성, 스키마 변경 및 SQL 초기화를 비활성화했습니다. 회원 Entity·Repository와 수동 생성 SQL을 포함하며 초기 회원 데이터는 넣지 않습니다.

## 디렉터리

```text
src/main/java/com/travel/travel_recommendation/
  TravelRecommendationApplication.java
  controller/HomeController.java
  service/ repository/ entity/ dto/ config/
src/main/resources/
  templates/index.html
  static/css/ static/js/ static/images/
  application.properties
  application-sqlserver.properties
src/test/java/com/travel/travel_recommendation/
  TravelRecommendationApplicationTests.java
```

빈 패키지는 `package-info.java`, 빈 정적 리소스 디렉터리는 `.gitkeep`으로 보존합니다. 테스트는 DB 없이 애플리케이션을 로드하고 `GET /`의 200 응답과 Thymeleaf 렌더링 결과를 확인합니다.

## 회원가입 구현 기준

- 아이디: 영문·숫자·밑줄 4~50자. 닉네임: 2~50자. 앞뒤 공백을 제거합니다.
- 비밀번호: 영문과 숫자를 포함해 8~64자. 영문·숫자·특수문자를 허용하며 공백은 제외합니다. 비밀번호 확인과 일치해야 합니다. 세부 규칙은 [진행 문서](docs/progress.md)에 기록했습니다.
- 아이디와 탈퇴하지 않은 회원의 닉네임은 중복을 거부합니다. 권한·상태는 서버에서 MEMBER/ACTIVE로 정합니다.
- 비밀번호는 BCrypt 해시로 저장합니다. 실패 시 아이디와 닉네임만 유지하고 비밀번호는 다시 입력받습니다.
- [Spring Security CSRF 문서](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)에 따라 CSRF 보호를 유지하고 Thymeleaf POST 폼에 토큰을 포함합니다.
- `SignupControllerTests`와 `SignupFormTests`는 DB 없이 실행되며 입력·화면·보안·실패 안내를 검사합니다. 실제 저장은 `SignupIntegrationTests`에서 별도 검증합니다.

## 로그인·로그아웃 테스트

Windows 인증 프로필로 실행한 뒤 [로그인](http://localhost:8080/login)에 접속합니다. 프로그램을 이미 실행 중이었다면 해당 터미널에서 Ctrl+C로 종료하고 최신 Jar로 다시 실행합니다. 기존 회원가입 계정으로 로그인할 수 있습니다.

1. 가입한 아이디와 비밀번호로 로그인하면 홈에 로그인 안내와 로그아웃 버튼이 표시됩니다.
2. 잘못된 비밀번호로 시도하면 같은 화면에서 오류를 안내합니다. 이용 제한·탈퇴 계정도 로그인할 수 없습니다.
3. 로그인한 채 로그인 화면을 다시 열면 시작 화면으로 이동합니다.
4. 로그아웃 버튼을 누르면 비회원 홈으로 이동합니다.
5. 비회원이 /admin에 접근하면 로그인 화면으로 이동하고 일반 회원에게는 403을 반환합니다. 관리자는 별도 시작 화면으로 이동하며 회원 관리 기능은 아직 준비 중입니다.

세션 만료 설정은 30분입니다. 자동 테스트는 SQL Server 테스트 실행 명령의 대상에 `LoginIntegrationTests`를 포함합니다. 전체 테스트를 실행하려면 `-Dtest=...` 부분을 생략합니다. 전체 테스트 중 SQL Server 통합 테스트는 실제 DB와 Windows 인증 DLL이 필요합니다. 회원 SQL뿐 아니라 아래 키워드 SQL도 먼저 적용합니다.

## 여행 조건 입력

서버 실행 후 홈의 **여행 조건 입력하기** 또는 `/travel/conditions`에서 시작합니다. DB 없는 기본 프로필에서도 회원·비회원 모두 사용할 수 있습니다.

- **조건 고르기**: 지역·날짜 또는 일수·계절·동행자·여행 취향·예산·이동수단을 입력합니다. 미선택 항목은 ‘상관없음’입니다.
- **문장으로 적기**: 원하는 여행을 최대 2,000자로 적습니다. 현재는 원문 확인까지 제공하며 자연어 분석은 수행하지 않습니다.
- **입력 내용 확인하기**: 서버 검증 후 확인 화면으로 이동합니다. 수정 링크로 돌아오면 값이 유지됩니다. 입력은 현재 세션에 임시 보관하며 영구 저장하지 않습니다.

SQL Server 프로필에서는 DB의 활성 키워드를 사용하며, 기본 프로필은 화면 확인용 예시 목록을 사용합니다. DB 조회 실패 시 예시 데이터로 대체하지 않고 재시도 안내를 표시합니다. 추천 요청 확정은 다음 단계입니다. [구현 기준](docs/screens/여행_조건_입력_구현.md)을 참고하세요. `TravelConditionControllerTests`는 DB 없이 정상 입력·오류·수정 복귀·세션 분리·CSRF·문장 이스케이프를 검증합니다.

인증 구현은 [Spring Security DaoAuthenticationProvider](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/dao-authentication-provider.html)의 UserDetailsService·PasswordEncoder 흐름을 사용합니다. 실제 계정의 비밀번호를 로그·문서·테스트 코드에 넣지 않습니다.

## 내 정보 조회·수정 테스트

최신 Jar로 서버를 다시 실행한 뒤 로그인하고 홈의 **내 정보** 또는 [내 정보 화면](http://localhost:8080/account)을 엽니다.

| 확인 항목 | 방법 | 예상 결과 |
| --- | --- | --- |
| 본인 정보 조회 | 로그인 후 내 정보 열기 | 자신의 아이디와 닉네임 표시 |
| 닉네임 수정 | 다른 닉네임과 현재 비밀번호 입력, 새 비밀번호는 비움 | 저장 완료, 로그인 유지 |
| 본인 확인 실패 | 잘못된 현재 비밀번호 입력 | 오류 안내, 변경되지 않음 |
| 중복·변경 없음 | 다른 회원 닉네임 또는 원래 값을 그대로 제출 | 중복 또는 변경 없음 안내 |
| 비밀번호 수정 | 현재 비밀번호와 새 비밀번호·확인 입력 | 저장 후 현재 세션 로그아웃, 새 비밀번호로 로그인 가능 |
| 비회원 접근 | 로그아웃 후 내 정보 주소 열기 | 로그인 화면으로 이동 |

아이디는 변경할 수 없습니다. 새 비밀번호는 회원가입과 동일하게 영문·숫자를 포함한 8~64자이며 특수문자를 허용하고 공백은 제외합니다. 화면에서 저장한 값은 실제 계정에 반영됩니다. 자동 `ProfileIntegrationTests`의 변경은 테스트 후 롤백됩니다.

### 키워드 테이블 준비 (PC별 1회)

Git pull은 DB를 변경하지 않습니다. 노트북 등 다른 PC의 DB에는 아래 SQL을 순서대로 적용합니다. 인스턴스 이름은 각 PC 환경에 맞춥니다.

```powershell
sqlcmd -S .\SQLEXPRESS -E -d travel_recommendation -i docs/database/sql/002_create_keywords.sql -b -f 65001
# 첫 명령이 성공한 뒤 초기 선택지를 넣습니다.
sqlcmd -S .\SQLEXPRESS -E -d travel_recommendation -i docs/database/sql/003_seed_keywords.sql -b -f 65001
```

002는 기존 키워드 테이블이 있으면 중단합니다. 이미 적용했다면 다시 생성하지 마세요. 003은 없는 코드만 추가하며 기존 항목을 덮어쓰지 않습니다. 앱 시작 시 SQL을 자동 실행하지 않습니다. 이번 데스크탑의 SQLEXPRESS에는 적용을 완료했습니다.

`KeywordIntegrationTests`는 스키마 검증, 활성 항목 조회, 정렬, 실제 DB ID의 입력→확인→수정 흐름을 확인합니다. 기존 SQL Server 테스트 명령에서 `-Dtest=...`를 생략하면 키워드와 회원 테스트를 함께 실행합니다. `TravelKeywordControllerTests`는 DB 오류·빈 목록·위조 ID·비활성화 이후 재제출·선택 당시 값 유지 처리를 검증합니다.
