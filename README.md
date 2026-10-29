# oeHub

개발·테스트 환경을 위한 도구 모음(허브)입니다.

하나의 로그인으로 두 도구를 씁니다. **oeHosts**는 프로필별 호스트 규칙으로 Chrome을 실행하고, **oeProxy**는 가상 호스트의 HTTPS를 받아 로컬 또는 원격 개발 서버로 프록시합니다. OS의 hosts 파일이나 인증서를 직접 관리하지 않고도 실제 도메인을 원하는 백엔드로 연결할 수 있습니다.

**서비스:** [https://oehub.dpdns.org](https://oehub.dpdns.org) — `workspace` 모드로 운영 중이며, oeHosts를 사용할 수 있습니다.

## 스크린샷

<a href="https://velog.velcdn.com/images/tricatch/post/7f21857d-9ac5-439b-b371-35c92faac773/image.png"><img src="https://velog.velcdn.com/images/tricatch/post/7f21857d-9ac5-439b-b371-35c92faac773/image.png" width="200" alt="oeHub screenshot 1"></a>
<a href="https://velog.velcdn.com/images/tricatch/post/7d645fc2-2625-48a9-9188-081cf4ab5ecf/image.png"><img src="https://velog.velcdn.com/images/tricatch/post/7d645fc2-2625-48a9-9188-081cf4ab5ecf/image.png" width="200" alt="oeHub screenshot 2"></a>
<a href="https://velog.velcdn.com/images/tricatch/post/9f2edf4a-1e8b-471f-a0e2-16a41faf8cbc/image.png"><img src="https://velog.velcdn.com/images/tricatch/post/9f2edf4a-1e8b-471f-a0e2-16a41faf8cbc/image.png" width="200" alt="oeHub screenshot 3"></a>
<a href="https://velog.velcdn.com/images/tricatch/post/ec606461-131d-4687-ad0f-41b442a08af0/image.png"><img src="https://velog.velcdn.com/images/tricatch/post/ec606461-131d-4687-ad0f-41b442a08af0/image.png" width="200" alt="oeHub screenshot 4"></a>

## 동작 방식

<img src="https://velog.velcdn.com/images/tricatch/post/daaf1f0b-006c-4369-9e96-b7cff1551ead/image.png" alt="oeHub request flow: browser configures a domain mapping in oeHosts, launches Chrome via oelink with --host-resolver-rules, which routes the request to oeProxy for TLS termination and destination routing, forwarding to a local or remote dev server">

## 도구

### oeHosts (+oelink)

- 프로필의 `--host-resolver-rules`와 플래그(User-Agent, `--user-data-dir`, 시크릿 모드 등)로 Chrome 실행
- 여러 프로필을 동시에 선택하고 병합 결과 미리보기
- 폴더로 프로필 정리, 공유 범위 지정, 공유 링크·공개 링크로 팀과 공유
- 워크스페이스/개인 프리셋으로 URL·User-Agent 빠르게 입력
- **oelink**: Chrome을 실행하는 `oelink://` 프로토콜 핸들러(Windows/macOS). 별도 저장소 [oeLink](https://github.com/tricatch/oeLink)에 있으며, oeHosts의 설치 안내에서 OS별 명령 한 줄을 복사해 설치합니다.

### oeProxy (+oeOID)

- 루트 인증서(직접 생성 또는 가져오기)로 가상 호스트의 TLS 인증서 자동 발급
- 하나의 도메인에서 로컬 개발 서버와 원격 서버를 섞어 쓰는 HTTPS 리버스 프록시
- 실시간 HTTP 요청 모니터
- 요청에 `X-OeHub-Oid` 헤더를 붙여 백엔드가 요청한 사용자를 식별 — **oeOID**는 이 헤더를 넣어 주는 Chrome 확장 프로그램입니다

### 포워드 프록시

- 포트 `36980`(`-Doe.fwdproxy.port`)에서 항상 실행되며, oeHub 계정으로 인증해야 사용 가능
- 선택한 oeHosts 프로필대로 요청을 라우팅 — Chrome의 `--proxy-server`로 `--host-resolver-rules` 대신 쓸 수 있음
- 관리자가 등록한 허용 도메인으로만 중계(목록이 비면 전부 차단)
- 모바일 기기의 Wi-Fi 프록시로 지정해 hosts 리다이렉션 가능(iOS는 앱까지, Android는 브라우저만)

## Best Practices

oeHub 화면은 Edge 등 Chrome이 아닌 브라우저에서 열고, 테스트용 Chrome은 oelink로 실행하세요. 같은 `--user-data-dir`을 두고 충돌하지 않고, 테스트 환경과 평소 브라우징도 분리됩니다.

## 계정

- 최초 실행 시 `/setup` 마법사가 관리자 계정과 루트 인증서를 구성
- 관리자: 사용자 관리, 워크스페이스 프리셋, 서버 설정, 감사 로그
- 사용자: 개인 프리셋, oeOID 도메인, 비밀번호 변경, 계정 삭제, 데이터 JSON 백업/복원

## 아키텍처 & 설계

설계 문서는 [docs/](docs/README.md)에 있습니다(배포 모드, 종단간 암호화, 보안 위협 모델, DB 스키마, API 레퍼런스 등).

## 시작하기

JDK 21이 필요합니다.

```bash
# 개발 모드 (src의 템플릿/정적 파일을 직접 로드)
./gradlew run -Djava.net.preferIPv4Stack=true

# 프로덕션 빌드
./gradlew shadowJar
java -Djava.net.preferIPv4Stack=true -jar oeHub-<version>.jar
```

최초 실행 시 설치 마법사(`/setup`)로 이동합니다.

### 실행 옵션

oeHub 옵션은 모두 `-Doe.<이름>=<값>` 형식의 JVM 시스템 프로퍼티입니다.

| 옵션 | 기본값 | 설명 |
|---|---|---|
| `-Doe.port` | `36912` | 애플리케이션 포트(H2 웹 콘솔은 `port + 1`) |
| `-Doe.fwdproxy.port` | `36980` | 포워드 프록시 포트 |
| `-Doe.home` | `~/oeHub` | 데이터 디렉터리(DB, CA 인증서 등) |
| `-Doe.data.dir` | `<oe.home>/data` | H2 DB·백업 디렉터리 |
| `-Doe.log.dir` | `<oe.home>/log` | 로그 디렉터리 |
| `-Doe.log.console` | `false` | 로그를 콘솔에도 출력 |
| `-Doe.mode` | `self-hosted` | 배포 모드(`self-hosted` / `workspace`) |
| `-Doe.dev` | `false` | 개발 모드(`./gradlew run`이 자동 지정, jar 실행에는 사용 금지) |
| `-Doe.secure.cookie` | `false` | 쿠키에 항상 `Secure` + HSTS. 앞단 프록시가 TLS를 처리할 때 사용 |
| `-Doe.client.ip.header` | (없음) | 같은 머신의 프록시/터널 뒤에서 실제 클라이언트 IP 헤더(예: `CF-Connecting-IP`). loopback 요청에서만 신뢰 |
| `-Doe.migrate` | (없음) | 앱을 시작하지 않고 스키마 마이그레이션 `<이름>`만 적용 후 종료 |
| `-Djava.net.preferIPv4Stack` | JVM 기본값 | IPv4 우선(항상 켜기 권장) |
| `-Dlogback.configurationFile` | 번들 `logback.xml` | 로깅 설정 재정의 |

> [!IMPORTANT]
> Linux에서 `443` 포트를 쓰려고 `root`로 실행하면 `~`가 `/root`가 됩니다. `-Doe.home`으로 경로를 고정하세요.
> ```bash
> sudo java -Doe.home=/home/youruser/oeHub -Djava.net.preferIPv4Stack=true -jar oeHub-<version>.jar
> ```

### 배포 모드

- **`self-hosted`** (기본값) — 단일 인스턴스, 워크스페이스 하나. oeHosts와 oeProxy 모두 사용.
- **`workspace`** — 여러 워크스페이스를 호스팅하는 멀티테넌트 서비스. oeHosts만 사용하며 콘텐츠는 종단간 암호화됩니다.

자세한 내용은 [docs/04-deployment-modes.md](docs/04-deployment-modes.md)를 참고하세요.

### 스키마 마이그레이션

새 버전이 기존 테이블을 바꾸면 시작 시 `-Doe.migrate=<name>` 안내와 함께 중단됩니다. oeHub를 중지하고, 평소 옵션 그대로 마이그레이션을 한 번 실행한 뒤 다시 시작하세요.

```bash
java -Doe.home=/path/to/oeHub -Doe.migrate=<이름> -jar oeHub-<version>.jar
```

실행 전 `<data>/backups/`에 백업 zip을 자동으로 만들며, 여러 번 실행해도 안전합니다.

### 로깅

로그는 `<oe.home>/log/oehub.log`에 기록되고 날짜별로 넘어가며 30일 뒤 삭제됩니다. 콘솔에는 개발 모드나 `-Doe.log.console=true`일 때만 출력됩니다.

### 스캔 차단

로그인하지 않은 같은 IP에서 1분 안에 404가 10번 나면 10분간 `403`으로 차단합니다. 로그인 사용자와 loopback 주소는 제외되며, 재시작하면 풀립니다.

## 기술 스택

- [Javalin](https://javalin.io/) + [Pebble](https://pebbletemplates.io/)
- [MyBatis](https://mybatis.org/mybatis-3/) + [H2](https://www.h2database.com/)
- [BouncyCastle](https://www.bouncycastle.org/) (인증서), JWT 세션 인증

## i18n

영어/한국어 UI(`lang` 쿠키로 전환).

## 라이선스

[MIT](LICENSE) &copy; 2026 tricatch
