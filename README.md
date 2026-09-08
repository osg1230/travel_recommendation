# travel_recommendation

설계 자료와 이전 버전의 위치는 [문서 안내](docs/README.md), 현재 작업 상태는 [진행 문서](docs/progress.md)에서 확인합니다.

AI 국내 여행 일정 추천 서비스의 초기 Spring Boot 프로젝트입니다. 현재는 DB 없이 실행되는 `GET /` 확인 화면만 제공하며 실제 서비스 기능은 구현하지 않았습니다.

## 기술 구성

- Java 21, Spring Boot 4.1.1 (정식 안정 버전), Maven Wrapper 3.3.4 / Maven 3.9.16, Jar
- Group: `com.travel`, Artifact: `travel_recommendation`
- 기본 패키지: `com.travel.travel_recommendation`
- Spring Web MVC, Thymeleaf, Spring Data JPA, Validation, Lombok, SQL Server JDBC Driver, DevTools
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

`sqlserver` 프로필이 없으면 DataSource/JPA 자동 구성을 제외하므로 DB 접속 정보를 요구하지 않습니다. H2 등 대체 DB는 포함하지 않습니다.

## SQL Server 프로필

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

공통 설정의 `spring.jpa.hibernate.ddl-auto=none`, `spring.sql.init.mode=never`로 자동 테이블 생성, 스키마 변경 및 SQL 초기화를 비활성화했습니다. Entity, 실제 Repository 인터페이스, 테이블, 초기 데이터는 포함하지 않습니다.

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
