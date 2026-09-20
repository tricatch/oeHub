# API 레퍼런스

이 문서는 `/api/*` 엔드포인트를 용도별로 정리한 목록이다. 정확한 요청/응답 바디, 상태 코드는
`OeHubApplication.java`의 라우트 등록부와 각 컨트롤러가 원본이다 — 이 문서는 "무엇이 있는지"를
빠르게 훑기 위한 것이지, 스펙 문서가 아니다.

## 인증

`/login`으로 로그인하면 발급되는 `oe_auth` 쿠키(JWT)로 인증한다. 상태를 바꾸는 요청
(`POST`/`PUT`/`PATCH`/`DELETE`)에는 CSRF 이중 제출 토큰(`X-CSRF-Token` 헤더 또는 `_csrf` 폼
필드, `oe_csrf` 쿠키와 대조)이 추가로 필요하다. `static/js/util.js`의 전역 `fetch` 래퍼가 같은
오리진 요청에는 이 헤더를 자동으로 붙여준다.

세션 쿠키가 없는 요청은 `/api/*` 전역 필터에서 `401 Unauthorized`로 거부된다. `self-hosted` /
`workspace` 표시가 없는 항목은 두 배포 모드 모두에서 동작한다.

> 브라우저 세션 없이(스크립트/CI 등에서) 호출하기 위한 개인 API 토큰(`Authorization: Bearer`)
> 기능은 스키마(`HUB_API_TOKEN`)와 백엔드 코드는 존재하지만, 실사용 시나리오가 아직 불명확해
> 라우팅을 비활성화해 두었다(2026-09-16) — 현재는 어떤 경로도 이 방식으로 인증되지 않는다.

### 역할별 URL 접두어

경로만 보고 누가 호출할 수 있는지 알 수 있도록, 관리 기능은 역할별 접두어 아래에 분리되어 있다.
필터는 접두어 하나당 역할 하나만 검사한다(`OeHubApplication`의 `requireRole`, 역할 정의는
`Role.java`).

| 접두어 | 접근 가능 | 비고 |
|---|---|---|
| `/adm/*`, `/api/adm/*` | `adm` | 설정, CA, 전역 프리셋, 워크스페이스 콘솔, H2 콘솔 |
| `/wsa/*`, `/api/wsa/*` | `wsa` (self-hosted에서는 `adm`도 가능) | 구성원, 승인, 초대, 팀, 감사 로그, 키 회전 |
| `/oehub/*`, 그 외 `/api/*` | 로그인 가능한 모든 역할 (`adm`, `wsa`, `usr`) | oeHosts, oeProxy, 내 정보 |

`workspace` 모드에서 `adm`은 `/wsa/*`에, `wsa`는 `/adm/*`에 접근할 수 없다(`403`). 세션이 없으면
`/api/*`는 `401`, 페이지(`/adm/*`, `/wsa/*`)는 로그인 페이지로 리다이렉트된다.

## 사용자 (`/api/user/*`)

로그인한 계정 자신에 대한 엔드포인트.

| Method | Path | 설명 |
|---|---|---|
| GET | `/api/user/crypto-keys` | 종단간 암호화 키 자료 조회 (로그인 직후 브라우저가 개인키를 언랩하기 위해 호출) |
| GET | `/api/user/backup` | hosts 프로필 + 설정 + proxy vhost를 JSON 파일로 내보내기 |
| POST | `/api/user/restore` | 위 백업 JSON을 복원 (`?merge=true`면 병합, 아니면 전체 교체) |
| POST | `/api/user/change-password` | 비밀번호 변경 (개인키 재래핑 포함) |
| POST | `/api/user/recovery-key` | 복구코드 재발급 |

## oeHosts (`/api/hosts/*`)

hosts 프로필 CRUD와 개인 프리셋. `self-hosted`/`workspace` 모두 동작(`workspace` 모드에서도
oeHosts는 그대로 사용 가능 — oeProxy만 꺼짐).

| Method | Path | 설명 |
|---|---|---|
| GET | `/api/hosts` | 내 hosts 프로필 목록 |
| POST | `/api/hosts` | 새 프로필 생성 |
| DELETE | `/api/hosts` | 내 프로필 전체 삭제 |
| PATCH | `/api/hosts/{hostsId}/content` | 프로필 내용 수정 |
| PATCH | `/api/hosts/{hostsId}/name` | 프로필 이름 변경 |
| PATCH | `/api/hosts/{hostsId}/selected` | 선택(활성) 상태 토글 |
| PATCH | `/api/hosts/{hostsId}/visibility` | 공개 범위 변경 (`private`/`collabo`/`public`) |
| GET | `/api/hosts/{hostsId}/view` | 단일 프로필 조회 (복호화된 내용 포함) |
| POST | `/api/hosts/{hostsId}/link` | "살아있는 공개 링크" 생성/갱신 |
| POST | `/api/hosts/{hostsId}/copy` | 프로필 복사 |
| POST | `/api/hosts/{hostsId}/register` | `collabo` 공유 프로필을 내 것으로 등록 |
| DELETE | `/api/hosts/{hostsId}` | 프로필 삭제 |
| GET | `/api/hosts/search` | 이름으로 프로필 검색 |
| PUT | `/api/hosts/order` | 정렬 순서 저장 |
| GET | `/api/hosts/export` | 내보내기 (JSON) |
| POST | `/api/hosts/import` | 가져오기 |
| GET | `/api/hosts/ua/presets` / `/api/hosts/url/presets` | 관리자 전역 UA/URL 프리셋 조회 |
| GET/POST/PATCH/DELETE | `/api/hosts/ua/my`, `/api/hosts/url/my` (`+/{id}`, `+/order`) | 내 개인 UA/URL 프리셋 CRUD |
| GET/PUT | `/api/hosts/conf/{name}` | 개인 설정값(`open_url`, `incognito` 등) 조회/저장 |
| POST | `/api/oid/domain/my` | 내 oeOID 도메인 오버라이드 저장 |
| GET | `/api/oid/download` | oeOID Chrome 확장 다운로드 |

## oeProxy (`/api/proxy/*`, `self-hosted` 전용)

`workspace` 모드에서는 oeProxy 자체가 비활성화되므로 이 그룹 전체가 존재하지 않는다
([04-deployment-modes.md](04-deployment-modes.md) 참고).

| Method | Path | 설명 |
|---|---|---|
| GET | `/api/proxy/monitor/event` | 실시간 트래픽 모니터 SSE 스트림 |
| GET | `/api/proxy/ca` | 루트 CA 인증서 다운로드 (인증 불필요 — 기기가 신뢰 설치를 해야 하므로) |
| POST | `/api/proxy/take-ip` | 현재 IP를 오너십 식별 폴백으로 8시간 동안 주장 |
| GET | `/api/proxy/vhosts` | 내 가상 호스트 목록 |
| POST | `/api/proxy/vhosts` | 새 가상 호스트 생성 |
| DELETE | `/api/proxy/vhosts` | 전체 삭제 |
| PATCH | `/api/proxy/vhosts/{vhostId}/content`\|`/name`\|`/selected`\|`/visibility` | 각 필드 수정 |
| POST | `/api/proxy/vhosts/{vhostId}/copy`\|`/register` | 복사 / `collabo` 등록 |
| DELETE | `/api/proxy/vhosts/{vhostId}` | 삭제 |
| GET | `/api/proxy/vhosts/search` | 검색 |
| PUT | `/api/proxy/vhosts/order` | 정렬 순서 저장 |
| GET | `/api/proxy/vhosts/export` | 내보내기 |
| POST | `/api/proxy/vhosts/import` | 가져오기 |
| GET/PUT | `/api/proxy/conf/{name}` | 개인 설정값 조회/저장 |

## 워크스페이스 관리자 — 사용자/팀 (`/api/wsa/*`, `wsa` 또는 self-hosted `adm`)

| Method | Path | 설명 |
|---|---|---|
| GET | `/api/wsa/users` | 멤버 검색/목록 (`?q=`) |
| GET | `/api/wsa/users/pending` | 승인 대기 목록 |
| POST | `/api/wsa/users/{userNo}/approve`\|`/reject` | 가입 승인/거부 |
| PATCH | `/api/wsa/users/{userNo}/role` | 역할 변경 (감사 로그 대상) |
| POST | `/api/wsa/users/{userNo}/reset-password` | 비밀번호 강제 재설정 (감사 로그 대상, 새 비밀번호는 응답에 1회만 노출) — **`self-hosted` 전용**. `workspace` 모드에서는 등록되지 않음(404): 대상자의 개인키를 재래핑할 수 없어 잠긴 계정을 되찾는 유일한 방법은 본인의 `/recover` 셀프서비스 플로우 |
| DELETE | `/api/wsa/users/{userNo}` | 계정 삭제 (감사 로그 대상) |
| PATCH | `/api/wsa/users/{userNo}/team` | 팀 재배정 (`workspace` 전용) |
| GET | `/api/wsa/workspace/rotation-rows` | 워크스페이스키 회전용 행 목록 |
| POST | `/api/wsa/workspace/rotate` | 워크스페이스키 회전 실행 (감사 로그 대상) |
| GET/POST | `/api/wsa/invites` | 초대 코드 목록/발급 (`workspace` 전용) |
| GET/POST/PATCH/DELETE | `/api/wsa/teams` (`+/{teamNo}`) | 팀 CRUD (`workspace` 전용) |

## 워크스페이스 관리자 — 감사 로그 (`/api/wsa/audit-log`)

| Method | Path | 설명 |
|---|---|---|
| GET | `/api/wsa/audit-log?page=` | 내 워크스페이스의 Tier-1 감사 로그 (50건씩 페이지네이션) |

기록되는 액션 종류와 스코프 규칙은 [07-database-schema.md](07-database-schema.md)의
`HUB_AUDIT_LOG`를 참고.

## 인스턴스 관리자 — 워크스페이스 콘솔 (`/api/adm/workspaces/*`, `workspace` 모드 전용)

격리 원칙에 따라 워크스페이스 이름/상태/멤버 수 같은 메타데이터만 다룬다 — 멤버 목록이나
콘텐츠는 노출하지 않는다.

| Method | Path | 설명 |
|---|---|---|
| GET | `/api/adm/workspaces` | 전체 워크스페이스 목록 |
| PATCH | `/api/adm/workspaces/{wsNo}/status` | 활성/정지 전환 (감사 로그 대상, 대상 워크스페이스에 기록). 호출자 자신이 속한 워크스페이스는 `400`으로 거부 — 정지하면 본인이 로그인할 수 없게 되어 되돌릴 사람이 없기 때문 |

## 인스턴스 관리자 — 설정·전역 프리셋 (`/api/adm/*`, 두 모드 모두)

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/adm/settings/oid-domain-default` | oeOID 기본 도메인 목록 저장 |
| POST | `/api/adm/settings/backup-interval` | H2 자동 백업 주기 저장 |
| POST | `/api/adm/settings/identifier` | IP 식별 폴백 on/off (`self-hosted` 전용) |
| POST | `/api/adm/settings/trust-internal-cert` | 내부망 백엔드 인증서 신뢰 on/off (`self-hosted` 전용) |
| POST | `/api/adm/settings/fwdproxy-whitelist` | 포워드 프록시 릴레이 화이트리스트 저장 (`self-hosted` 전용) |
| GET/POST/PATCH/DELETE | `/api/adm/hosts/ua`, `/api/adm/hosts/url` (`+/{id}`, `+/order`) | 전역 UA/URL 프리셋 CRUD |

`/adm/settings/ca/generate`, `/adm/settings/ca/import`(폼 POST, JSON 아님)도 이 그룹에
속하며 CA 재발급/가져오기는 감사 로그 대상이다. 최초 설치 마법사(`/setup/ca/generate`)의 CA
생성은 그 이전에 로그인 세션 자체가 없어 감사 로그 대상이 아니다.

## 공개 (인증 불필요)

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/recover/verify`\|`/api/recover/reset` | 비밀번호 찾기 복구 플로우 |
| GET | `/share/{hostsId}/oelink`\|`/text` | 로그인 필요 없는 워크스페이스 내부 공유 링크 |
| GET | `/link/{hostsId}/oelink`\|`/oelink/text` | 완전 공개(비로그인) "살아있는 링크" |
| GET | `/share/proxy/{vhostId}/view`\|`/text` | 가상 호스트 공유 링크 (self-hosted 전용) |

`/api/proxy/ca`(루트 CA 다운로드)도 인증이 필요 없다 — oeProxy 그룹 표 참고.
