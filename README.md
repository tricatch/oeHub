# oeHub

개발 및 테스트 환경을 구축하기 위한 도구 모음(허브)입니다.

oeHub는 하나의 로그인 아래 두 가지 브라우저용 도구를 묶어 제공합니다. **oeHosts**는 프로필별로 Chrome의 호스트 리졸버 규칙을 전환하고 `oelink://` 프로토콜 핸들러로 Chrome을 실행합니다. **oeProxy**는 로컬 가상 호스트를 위해 HTTPS를 종료하고 로컬 또는 원격 개발 서버로 리버스 프록시하며, 사용자별 추적을 위해 요청에 oeOID 헤더를 태깅합니다. 덕분에 팀은 OS 수준의 hosts 파일을 건드리거나 인증서를 수작업으로 관리하지 않고도, 실제와 똑같이 생긴 도메인을 로컬·스테이징·프로덕션 중 원하는 백엔드로 자유롭게 연결할 수 있습니다.

## 스크린샷

<a href="https://velog.velcdn.com/images/tricatch/post/7f21857d-9ac5-439b-b371-35c92faac773/image.png"><img src="https://velog.velcdn.com/images/tricatch/post/7f21857d-9ac5-439b-b371-35c92faac773/image.png" width="200" alt="oeHub screenshot 1"></a>
<a href="https://velog.velcdn.com/images/tricatch/post/7d645fc2-2625-48a9-9188-081cf4ab5ecf/image.png"><img src="https://velog.velcdn.com/images/tricatch/post/7d645fc2-2625-48a9-9188-081cf4ab5ecf/image.png" width="200" alt="oeHub screenshot 2"></a>
<a href="https://velog.velcdn.com/images/tricatch/post/9f2edf4a-1e8b-471f-a0e2-16a41faf8cbc/image.png"><img src="https://velog.velcdn.com/images/tricatch/post/9f2edf4a-1e8b-471f-a0e2-16a41faf8cbc/image.png" width="200" alt="oeHub screenshot 3"></a>
<a href="https://velog.velcdn.com/images/tricatch/post/ec606461-131d-4687-ad0f-41b442a08af0/image.png"><img src="https://velog.velcdn.com/images/tricatch/post/ec606461-131d-4687-ad0f-41b442a08af0/image.png" width="200" alt="oeHub screenshot 4"></a>

## 동작 방식

<img src="https://velog.velcdn.com/images/tricatch/post/daaf1f0b-006c-4369-9e96-b7cff1551ead/image.png" alt="oeHub request flow: browser configures a domain mapping in oeHosts, launches Chrome via oelink with --host-resolver-rules, which routes the request to oeProxy for TLS termination and destination routing, forwarding to a local or remote dev server">

## 도구

### oeHosts (+oelink)

- `oelink://` 프로토콜 핸들러를 통해 프로필의 규칙과 커스텀 플래그(`--user-agent`, `--user-data-dir`, 시크릿 모드, 추가 인수)를 적용해 Chrome을 바로 실행
    - **oelink**는 클라이언트 머신에 설치되는 커스텀 프로토콜 핸들러로, oeHosts는 이를 호출하여 프로필의 `--host-resolver-rules`와 기타 플래그로 Chrome을 실행합니다
    - Windows와 macOS를 모두 지원하며, 설치/제거 스크립트는 `oelink/win`과 `oelink/mac`에 있습니다
    - `oelink/` 아래의 설치 파일들(사전 빌드된 Windows용 `oelink.exe` 포함)은 빌드 시점에 생성되는 것이 아니라 그대로 커밋되어 있어서, 전체 Gradle 빌드 없이도 클론 직후 프로토콜 핸들러가 바로 동작합니다
- Chrome에서 여러 hosts 프로필 간 전환
- 여러 프로필을 동시에 선택하고, 실행 전에 병합된 호스트 리졸버 규칙을 미리보기
- 공유 링크로 팀과 hosts 프로필 공유
- 관리자 관리 프리셋 또는 개인 프리셋으로 "Open URL"과 "User-Agent" 필드를 빠르게 채우기

### oeProxy (+oeOID)

- 자체 서명된 루트 인증서로부터 TLS 인증서를 자동 생성 (직접 생성하거나 기존 것을 가져오기 가능)
- 로컬 가상 호스트를 위한 HTTPS 리버스 프록시로, 로컬 개발 서버와 원격/프로덕션 서버가 하나의 도메인을 공유할 수 있게 함
- HTTP 요청 실시간 모니터
- 프록시를 거치는 요청에 `X-OeHub-Oid` 헤더를 태깅하여 오리진 서비스가 요청을 보낸 oeHub 사용자를 식별할 수 있게 함
    - **oeOID**는 (DHCP 환경에서 신뢰할 수 없는) IP 주소 대신, oeProxy를 거치는 요청에 `X-OeHub-Oid` 헤더를 주입하는 Chrome 확장 프로그램입니다

### oeProxy 포워드 프록시

- 고정 포트 `36980`에서 항상 실행됨 (관리자가 켜고 끌 수 없음)
- 어떤 요청이든 중계하기 전에 oeHub 계정 자격 증명으로 인증이 필요함
- 인증되면, 해당 사용자가 현재 선택한 oeHosts 프로필에 있는 호스트로의 요청은 요청마다 DB를 조회하는 대신 사용자별 인메모리 맵에 기록된 IP로 라우팅됨
- oeHosts는 `--host-resolver-rules`의 대안으로 Chrome의 `--proxy-server` 플래그를 이 포워드 프록시로 향하게 할 수 있음 (둘 중 하나를 켜면 다른 하나는 자동으로 꺼짐)
- 관리자가 "허용 도메인" 화이트리스트(`*.` 와일드카드 하위 도메인 매칭 지원)에 등록한 도메인으로만 중계하며(목록이 비어 있으면 아무것도 중계하지 않음), 차단된 목적지에는 브랜드가 적용된 403 오류 페이지가 표시됨(포트 `36981`의 전용 루프백 TLS 응답기를 통해 HTTPS도 포함)
- 표준 HTTP(S) 프록시이므로, 모바일 기기의 Wi-Fi 프록시 설정(iOS 또는 Android)을 이곳으로 지정하는 방식으로 hosts를 리다이렉션할 수 있음 — 모바일에는 `oelink`/host-resolver-rules에 해당하는 것이 필요 없음
    - iOS의 Wi-Fi 프록시 설정은 인증을 지원하므로, 브라우저 트래픽과 앱 트래픽 모두에 사용 가능
    - Android의 Wi-Fi 프록시 설정은 인증을 지원하지 않으므로, (자체적으로 자격 증명을 요청하고 제출할 수 있는) 브라우저만 인증하여 사용할 수 있으며 그 외 앱의 트래픽은 프록시를 거치지 않음

## 모범 사례

- oeHosts를 사용할 때는 oeHub 웹 UI 자체는 Edge(또는 다른 비-Chrome 브라우저)에서 열고, 실제 테스트에 사용할 Chrome은 `oelink`이 실행하도록 두세요. `oelink`은 프로필 전용 `--user-data-dir`로 Chrome을 실행하는데, oeHub UI도 Chrome에서 실행 중이라면 두 인스턴스가 동일한 user-data-dir을 두고 충돌해 프로필 잠금이나 실행 충돌이 발생할 수 있습니다. 두 브라우저를 분리해 두면, 호스트 리졸버 규칙이 실제와 똑같이 생긴 도메인을 로컬 또는 스테이징 서버로 리다이렉션하는 테스트 환경과, 평소 작업 환경(이메일, 문서, 일반 브라우징)을 깔끔하게 분리할 수 있습니다.

## 계정

- `admin`과 `user` 역할을 가진 다중 사용자 지원
- 최초 실행 시 `/setup` 마법사가 초기 관리자 계정을 생성함
- 관리자는 설정 페이지에서 사용자 관리(관리자 권한 부여/해제, 계정 삭제, 사용자 비밀번호를 무작위로 재설정)와 전역 URL/UA 프리셋을 관리함
- 각 사용자는 자신의 프리셋, oeOID 도메인 목록, 계정 정보, 셀프 서비스 비밀번호 변경을 위한 개인 설정 페이지를 가짐
- 각 사용자는 자신의 hosts 프로필, hosts 설정, 프록시 가상 호스트를 JSON으로 백업하고 복원할 수 있음

## 아키텍처 & 설계

시스템이 어떻게 설계되었는지는 [docs/](docs/README.md)를 참고하세요: 번들된 두 도구, `self-hosted`/`workspace` 배포 모델, 그리고 `workspace` 모드에서 사용되는 종단간 암호화(무엇을 보호하고 무엇을 보호하지 않는지에 대한 솔직한 요약 포함)를 다룹니다.

## 시작하기

JDK 21이 필요합니다.

```bash
# 개발 모드 (jar 빌드 없이 src에서 템플릿/정적 파일을 직접 로드)
./gradlew run -Djava.net.preferIPv4Stack=true

# 프로덕션 빌드
./gradlew shadowJar
java -Djava.net.preferIPv4Stack=true -jar oeHub-<version>.jar
```

최초 실행 시 관리자 계정을 만들고 oeProxy가 사용할 루트 인증서를 구성하는 설치 마법사(`/setup`)로 리다이렉션됩니다.

### 실행 옵션

모든 옵션은 `-D<이름>=<값>` 형태의 JVM 시스템 프로퍼티이며, `java -D... -jar oeHub-<version>.jar`처럼 조합해서 사용합니다.

oeHub 고유 옵션은 모두 `-Doe.<이름>` 형식으로 통일되어 있습니다(`-Doe.mode`, `-Doe.home` 등). `-Djava.net.preferIPv4Stack`, `-Dlogback.configurationFile`처럼 JVM/라이브러리 표준 프로퍼티만 예외입니다.

| 옵션 | 기본값 | 설명 |
|---|---|---|
| `-Doe.port` | `36912` | 애플리케이션 포트. H2 웹 콘솔은 자동으로 `port + 1`에서 시작됩니다. |
| `-Doe.home` | `~/oeHub` (`~`는 OS 사용자 홈) | 애플리케이션 데이터(H2 데이터베이스, CA 인증서 등) 디렉터리. oeHub 디렉터리 자체를 가리키며, 그 상위 디렉터리가 아닙니다(`/oeHub`가 추가로 붙지 않습니다). |
| `-Doe.data.dir` | `<oe.home>/data` | H2 데이터베이스 파일, 생성되는 비밀번호 파일(`.h2-password`), 예약 백업(`backups/`)이 저장되는 디렉터리. 지정하면 이 디렉터리를 그대로 사용하며, `-Doe.home`과 별개로 DB 데이터만 다른 볼륨에 두고 싶을 때 사용하세요. 파일명 자체(`oeHub-h2` / workspace 모드의 `oeHub-h2-ws`)는 이 옵션으로 바꿀 수 없습니다. |
| `-Doe.mode` | `self-hosted` | 배포 모드 — `self-hosted` 또는 `workspace`. 아래 [배포 모드](#배포-모드) 참고. |
| `-Doe.dev` | `false` | 개발 모드. `true`이면 정적 파일과 Pebble 템플릿을 jar에 번들된 classpath 리소스 대신 `src/main/resources`의 실제 파일에서 직접 읽고 템플릿 캐시를 꺼서, 서버 재시작 없이 수정 내용이 바로 반영됩니다. `./gradlew run`이 자동으로 켜서 실행하며, 배포용 jar 실행 시에는 지정하지 마세요. |
| `-Doe.secure.cookie` | `false` | `true`이면 인증·CSRF 쿠키에 항상 `Secure`를 붙이고 응답에 `Strict-Transport-Security`를 보냅니다. 앞단 프록시가 TLS를 끝내서 oeHub가 요청을 항상 HTTP로 보는 배포(예: `workspace` 모드 클라우드 배포)에서 켜세요. 기본값에서는 HTTPS로 들어온 요청에만 붙습니다. |
| `-Djava.net.preferIPv4Stack` | JVM 기본값 | IPv4 우선 사용. 위 실행 예시처럼 항상 켜서 실행하기를 권장합니다. |
| `-Dlogback.configurationFile` | jar에 번들된 `logback.xml` | 재빌드 없이 로깅 설정을 재정의합니다. 자세한 내용은 아래 [로깅](#로깅) 참고. |

> [!IMPORTANT]
> Linux에서 oeProxy가 `443` 포트에 바인딩하려면 JVM을 `root`로 실행해야 하는데, 이 경우 `~`가 일반 사용자의 홈이 아니라 `/root`로 해석됩니다. 애플리케이션 데이터를 원하는 디렉터리에 고정하려면 `-Doe.home=/path/to/oeHub`를 전달하세요. 예:
> ```bash
> sudo java -Doe.home=/home/youruser/oeHub -Djava.net.preferIPv4Stack=true -jar oeHub-<version>.jar
> ```

### 배포 모드

`-Doe.mode`로 선택하는 두 가지 모드 중 하나로 실행됩니다(기본값 `self-hosted`). 둘은 같은 코드베이스와 스키마를 공유하며, 서버 시작 시 한 번 정해져 프로세스 수명 동안 정적으로 유지됩니다.

- **`self-hosted`** (기본값) — 단일 셀프 호스팅 인스턴스. oeHosts와 oeProxy가 모두 사용 가능하며, 워크스페이스가 정확히 하나 존재합니다.
- **`workspace`** — 하나의 실행 중인 인스턴스 뒤에서 여러 독립적인 워크스페이스(회사 또는 팀)를 호스팅하는 멀티테넌트 클라우드 서비스. oeHosts만 사용 가능하며 oeProxy는 아예 비활성화되고, 워크스페이스 콘텐츠에는 종단간 암호화가 적용됩니다.

역할(`adm`/`wsa`/`usr` 등), 가입·승인 흐름, 모드별로 달라지는 동작에 대한 자세한 내용은 [docs/04-deployment-modes.md](docs/04-deployment-modes.md)를 참고하세요.

### 로깅

로깅은 [Logback](https://logback.qos.ch/)으로 구성되며, 기본 `logback.xml`이 jar 안에 번들되어 있습니다. 재빌드 없이 이를 재정의하려면, 시작 시 클래스패스에 있는 외부 파일을 Logback이 가리키도록 지정하세요.

```bash
java -Dlogback.configurationFile=/path/to/logback.xml -jar oeHub-<version>.jar
```

Logback은 jar에 포함된 설정으로 넘어가기 전에 이 시스템 프로퍼티를 먼저 확인합니다.

## 기술 스택

- [Javalin](https://javalin.io/) + [Pebble](https://pebbletemplates.io/) 템플릿
- [MyBatis](https://mybatis.org/mybatis-3/) + [H2](https://www.h2database.com/) (내장, 파일 기반)
- 인증서 생성/파싱을 위한 [BouncyCastle](https://www.bouncycastle.org/)
- JWT 기반 세션 인증

## i18n

UI는 `lang` 쿠키로 전환되는 영어와 한국어를 지원합니다.

## 라이선스

[MIT](LICENSE) &copy; 2026 tricatch
